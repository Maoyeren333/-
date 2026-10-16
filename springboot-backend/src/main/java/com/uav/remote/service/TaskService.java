package com.uav.remote.service;

import com.uav.remote.entity.Interpretation;
import com.uav.remote.entity.RemoteImage;
import com.uav.remote.entity.Task;
import com.uav.remote.feign.InferenceClient;
import com.uav.remote.repository.InterpretationRepository;
import com.uav.remote.repository.RemoteImageRepository;
import com.uav.remote.repository.TaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class TaskService {
    private static final List<String> ALGORITHMS = List.of("camouflaged_object_detection");

    private final TaskRepository tasks;
    private final InterpretationRepository results;
    private final RemoteImageRepository images;
    private final InferenceClient ai;

    public TaskService(TaskRepository tasks, InterpretationRepository results, RemoteImageRepository images, InferenceClient ai) {
        this.tasks = tasks;
        this.results = results;
        this.images = images;
        this.ai = ai;
    }

    public List<Task> list() { return tasks.findAll(); }

    /** 任务列表：支持按名称/区域关键字与状态筛选。 */
    public List<Task> list(String keyword, String status) { return tasks.search(blankToNull(keyword), blankToNull(status)); }

    /** 分页任务列表，供任务页表格使用。 */
    public Map<String, Object> page(String keyword, String status, int page, int size) {
        int safePage = Math.max(1, page);
        Pageable pageable = PageRequest.of(safePage - 1, Math.max(1, Math.min(size, 100)));
        Page<Task> result = tasks.searchPaged(blankToNull(keyword), blankToNull(status), pageable);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", result.getContent());
        payload.put("total", result.getTotalElements());
        payload.put("page", safePage);
        payload.put("size", pageable.getPageSize());
        payload.put("pages", result.getTotalPages());
        return payload;
    }

    public Task get(Long id) { return tasks.findById(id).orElseThrow(() -> new IllegalArgumentException("任务不存在")); }

    /** 任务已上传的影像，按上传顺序。 */
    public List<RemoteImage> images(Long taskId) { return images.findByTaskIdOrderByIdAsc(taskId); }

    /** 任务详情：任务本身 + 已入库影像 + 该任务的解译记录。 */
    public Map<String, Object> detail(Long taskId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("task", get(taskId));
        detail.put("images", images(taskId));
        detail.put("interpretations", results.findByTaskIdOrderByIdDesc(taskId));
        return detail;
    }

    @Transactional
    public Task create(Task task) {
        if (!ALGORITHMS.contains(task.getAlgorithm())) throw new IllegalArgumentException("不支持的解译算法");
        task.setStatus("QUEUED");
        return tasks.save(task);
    }

    /** 删除任务：连带删除该任务的影像、解译记录与磁盘文件。 */
    @Transactional
    public void delete(Long taskId) {
        get(taskId);
        for (Interpretation result : results.findByTaskIdOrderByIdDesc(taskId)) {
            FileCleaner.deleteResultFiles(result.getMaskPath());
        }
        for (RemoteImage image : images(taskId)) {
            FileCleaner.deleteQuietly(image.getFilePath());
        }
        results.deleteByTaskId(taskId);
        images.deleteByTaskId(taskId);
        tasks.deleteById(taskId);
    }

    /** Resolve selected task images, or all uploaded images when no selection is supplied. */
    public List<String> resolveInputs(Long taskId, String algorithm, List<Long> imageIds, String imagePath) {
        if (!ALGORITHMS.contains(algorithm)) throw new IllegalArgumentException("仅支持 C2FNet 伪装目标检测");
        String explicitPath = blankToNull(imagePath);
        if (explicitPath != null) {
            if (imageIds != null) throw new IllegalArgumentException("图片路径与影像选择不能同时提供");
            return List.of(explicitPath);
        }
        List<RemoteImage> uploaded = images(taskId);
        if (imageIds == null) {
            if (uploaded.isEmpty()) throw new IllegalArgumentException("该任务还没有影像，请先上传影像");
            return uploaded.stream().map(RemoteImage::getFilePath).toList();
        }
        if (imageIds.isEmpty()) throw new IllegalArgumentException("请至少选择一张影像");
        Map<Long, String> paths = new LinkedHashMap<>();
        for (RemoteImage image : uploaded) paths.put(image.getId(), image.getFilePath());
        return new LinkedHashSet<>(imageIds).stream().map(id -> {
            if (id == null || !paths.containsKey(id)) throw new IllegalArgumentException("所选影像不存在或不属于该任务");
            return paths.get(id);
        }).toList();
    }

    @Async
    public void interpret(Long taskId, String algorithm, List<String> imagePaths) {
        Task task = get(taskId);
        String resolvedAlgorithm = (algorithm == null || algorithm.isBlank()) ? task.getAlgorithm() : algorithm;
        if (resolvedAlgorithm == null || !ALGORITHMS.contains(resolvedAlgorithm)) throw new IllegalArgumentException("不支持的解译算法");
        if (imagePaths == null || imagePaths.isEmpty()) throw new IllegalArgumentException("请至少选择一张影像");

        task.setAlgorithm(resolvedAlgorithm);
        task.setStatus("PROCESSING");
        tasks.save(task);

        boolean failed = false;
        for (String imagePath : imagePaths) {
            if (!interpretImage(taskId, resolvedAlgorithm, imagePath)) failed = true;
        }
        task.setStatus(failed ? "FAILED" : "COMPLETED");
        tasks.save(task);
    }

    // Repository calls commit each image's status, so completed results are visible during a batch.
    private boolean interpretImage(Long taskId, String algorithm, String imagePath) {
        Interpretation result = new Interpretation();
        result.setTaskId(taskId);
        result.setAlgorithm(algorithm);
        result.setSourcePath(imagePath);
        result.setStatus("RUNNING");
        result = results.save(result);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("image_path", imagePath);
            Map<String, Object> output = ai.infer(algorithm, body);
            if (!Boolean.TRUE.equals(output.get("weights_loaded")) || !Boolean.FALSE.equals(output.get("simulated"))) {
                throw new IllegalStateException("推理服务未返回已加载权重的真实结果");
            }
            Object maskPath = output.get("mask_path");
            if (maskPath == null || String.valueOf(maskPath).isBlank()) throw new IllegalStateException("推理服务未返回 mask_path");
            result.setMaskPath(String.valueOf(maskPath));
            result.setStatus("SUCCESS");
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(TaskService.class).error("C2FNet inference failed for task {}", taskId, ex);
            result.setStatus("FAILED");
        }
        results.save(result);
        return "SUCCESS".equals(result.getStatus());
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

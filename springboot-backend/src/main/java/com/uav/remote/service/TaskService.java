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
import java.util.List;
import java.util.Map;

@Service
public class TaskService {
    private static final List<String> ALGORITHMS = List.of("camouflaged_object_detection", "change_detection", "semantic_segmentation");
    private static final String CHANGE_DETECTION = "change_detection";

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

    /**
     * 解析本次解译实际要用的影像路径。
     *
     * 优先使用请求显式传入的路径；未传时回退到该任务已上传的影像：
     * 单期算法取第 1 张，变化检测取第 1、2 张作为双时相输入。
     */
    public String[] resolveInputs(Long taskId, String algorithm, String imagePath, String imagePathB) {
        String first = blankToNull(imagePath);
        String second = blankToNull(imagePathB);
        List<RemoteImage> uploaded = images(taskId);

        if (first == null && !uploaded.isEmpty()) first = uploaded.get(0).getFilePath();
        if (CHANGE_DETECTION.equals(algorithm)) {
            if (second == null && uploaded.size() >= 2) second = uploaded.get(1).getFilePath();
            if (first == null || second == null) {
                throw new IllegalArgumentException("变化检测需要两期影像：请为该任务上传两张影像，或同时传入 image_path 与 image_path_b");
            }
        } else if (first == null) {
            throw new IllegalArgumentException("该任务还没有影像：请先上传影像，或在请求中提供 image_path");
        }
        return new String[]{first, second};
    }

    @Async
    @Transactional
    public void interpret(Long taskId, String algorithm, String pathA, String pathB) {
        Task task = get(taskId);
        String resolvedAlgorithm = (algorithm == null || algorithm.isBlank()) ? task.getAlgorithm() : algorithm;
        if (resolvedAlgorithm == null || !ALGORITHMS.contains(resolvedAlgorithm)) throw new IllegalArgumentException("不支持的解译算法");

        task.setAlgorithm(resolvedAlgorithm);
        task.setStatus("PROCESSING");
        tasks.save(task);

        Interpretation result = new Interpretation();
        result.setTaskId(taskId);
        result.setAlgorithm(resolvedAlgorithm);
        result.setSourcePath(pathB == null || pathB.isBlank() ? pathA : pathA + "|" + pathB);
        result.setStatus("RUNNING");
        result = results.save(result);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("image_path", pathA);
            if (pathB != null && !pathB.isBlank()) body.put("image_path_b", pathB);
            Map<String, Object> output = ai.infer(resolvedAlgorithm, body);
            Object maskPath = output.get("mask_path");
            if (maskPath == null) throw new IllegalStateException("推理服务未返回 mask_path");
            result.setMaskPath(String.valueOf(maskPath));
            result.setStatus("SUCCESS");
            task.setStatus("COMPLETED");
        } catch (Exception ex) {
            result.setStatus("FAILED");
            task.setStatus("FAILED");
        }
        results.save(result);
        tasks.save(task);
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}

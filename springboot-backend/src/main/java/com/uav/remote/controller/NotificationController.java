package com.uav.remote.controller;

import com.uav.remote.entity.Interpretation;
import com.uav.remote.entity.Task;
import com.uav.remote.feign.InferenceClient;
import com.uav.remote.repository.InterpretationRepository;
import com.uav.remote.repository.RemoteImageRepository;
import com.uav.remote.repository.TaskRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通知中心：所有通知都由真实状态合成，不再有写死的引擎在线文案。
 *
 * 来源：推理服务可达性、算法权重就绪情况、失败与成功的解译记录、待解译任务、缺少影像的任务。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final TaskRepository tasks;
    private final InterpretationRepository interpretations;
    private final RemoteImageRepository images;
    private final InferenceClient inference;

    public NotificationController(TaskRepository tasks, InterpretationRepository interpretations,
                                 RemoteImageRepository images, InferenceClient inference) {
        this.tasks = tasks;
        this.interpretations = interpretations;
        this.images = images;
        this.inference = inference;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> items = new ArrayList<>();

        boolean inferenceUp = false;
        List<Map<String, Object>> algorithms = List.of();
        try {
            Map<String, Object> health = inference.health();
            inferenceUp = "UP".equals(String.valueOf(health.get("status"))) || "DEGRADED".equals(String.valueOf(health.get("status")));
            Object raw = inference.algorithms().get("algorithms");
            if (raw instanceof List<?> list) {
                algorithms = list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> (Map<String, Object>) item)
                        .collect(Collectors.toList());
            }
        } catch (Exception ignored) {
            inferenceUp = false;
        }

        if (!inferenceUp) {
            items.add(notice("DANGER", "!", "推理服务不可达", "解译请求会直接失败，请确认 python-inference 已在 8000 端口运行", null));
        } else {
            long missing = algorithms.stream().filter(item -> !Boolean.TRUE.equals(item.get("weights_ready"))).count();
            if (missing > 0) {
                items.add(notice("WARNING", "⚠", missing + " 个算法未加载权重",
                        "模型不可用时解译会失败；请检查 python-inference/weights 中的完整权重及服务日志", null));
            }
        }

        List<Interpretation> failed = interpretations.findByStatusOrderByIdDesc("FAILED");
        for (Interpretation result : failed.stream().limit(3).toList()) {
            items.add(notice("DANGER", "✕", "解译失败 · " + CommandController.algorithmLabel(result.getAlgorithm()),
                    "任务 #" + result.getTaskId() + " · 请检查影像文件与推理服务日志", result.getCreatedAt()));
        }

        long pending = tasks.countByStatus("QUEUED") + tasks.countByStatus("PROCESSING");
        if (pending > 0) {
            items.add(notice("WARNING", "◌", pending + " 个任务待解译",
                    "前往「航拍任务」点击开始解译", null));
        }

        List<Task> withoutImage = tasks.findAll().stream()
                .filter(task -> images.findByTaskIdOrderByIdAsc(task.getId()).isEmpty())
                .toList();
        if (!withoutImage.isEmpty()) {
            String ids = withoutImage.stream().limit(5).map(task -> "#" + task.getId()).collect(Collectors.joining("、"));
            items.add(notice("INFO", "⇧", withoutImage.size() + " 个任务还没有影像",
                    "任务 " + ids + " · 上传影像后才能启动解译", null));
        }

        for (Interpretation result : interpretations.findByStatusOrderByIdDesc("SUCCESS").stream().limit(3).toList()) {
            items.add(notice("SUCCESS", "✓", "解译完成 · " + CommandController.algorithmLabel(result.getAlgorithm()),
                    "任务 #" + result.getTaskId() + " · 可在结果中心查看，或在成果导出打包下载", result.getCreatedAt()));
        }

        if (items.isEmpty()) {
            items.add(notice("SUCCESS", "✓", "平台状态正常", "暂无待处理事项", null));
        }

        long actionable = items.stream().filter(item -> !"SUCCESS".equals(item.get("level"))).count();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", actionable);
        payload.put("items", items);
        return payload;
    }

    private Map<String, Object> notice(String level, String icon, String title, String detail, LocalDateTime at) {
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("level", level);
        notice.put("icon", icon);
        notice.put("title", title);
        notice.put("detail", detail);
        notice.put("time", at == null ? "实时" : at.format(MINUTE));
        return notice;
    }
}

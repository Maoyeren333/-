package com.uav.remote.controller;

import com.uav.remote.entity.Interpretation;
import com.uav.remote.entity.Task;
import com.uav.remote.repository.InterpretationRepository;
import com.uav.remote.repository.RemoteImageRepository;
import com.uav.remote.repository.TaskRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM/dd");
    private static final List<String> ALGORITHMS = List.of("camouflaged_object_detection", "change_detection", "semantic_segmentation");

    private final TaskRepository tasks;
    private final InterpretationRepository interpretations;
    private final RemoteImageRepository images;

    public DashboardController(TaskRepository tasks, InterpretationRepository interpretations, RemoteImageRepository images) {
        this.tasks = tasks;
        this.interpretations = interpretations;
        this.images = images;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        long total = tasks.count();
        long completed = tasks.countByStatus("COMPLETED");
        long success = interpretations.countByStatus("SUCCESS");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTasks", total);
        result.put("processingTasks", tasks.countByStatus("PROCESSING"));
        result.put("queuedTasks", tasks.countByStatus("QUEUED"));
        result.put("completedTasks", completed);
        result.put("failedTasks", tasks.countByStatus("FAILED"));
        result.put("images", images.count());
        result.put("interpretations", interpretations.count());
        result.put("successInterpretations", success);
        result.put("successRate", total == 0 ? 0 : Math.round(completed * 1000.0 / total) / 10.0);
        return result;
    }

    /** 最近 N 天的真实趋势：每天创建的任务数、提交的解译数、解译成功数。 */
    @GetMapping("/trend")
    public Map<String, Object> trend(@RequestParam(defaultValue = "7") int days) {
        int window = Math.max(1, Math.min(days, 30));
        LocalDate today = LocalDate.now();
        List<Task> allTasks = tasks.findAll();
        List<Interpretation> allResults = interpretations.findAll();

        List<String> labels = new ArrayList<>();
        List<Long> created = new ArrayList<>();
        List<Long> submitted = new ArrayList<>();
        List<Long> succeeded = new ArrayList<>();

        for (int offset = window - 1; offset >= 0; offset--) {
            LocalDate day = today.minusDays(offset);
            labels.add(day.format(DAY));
            created.add(allTasks.stream().filter(t -> t.getCreatedAt() != null && t.getCreatedAt().toLocalDate().equals(day)).count());
            submitted.add(allResults.stream().filter(r -> r.getCreatedAt() != null && r.getCreatedAt().toLocalDate().equals(day)).count());
            succeeded.add(allResults.stream().filter(r -> r.getCreatedAt() != null && r.getCreatedAt().toLocalDate().equals(day)
                    && "SUCCESS".equals(r.getStatus())).count());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("labels", labels);
        result.put("createdTasks", created);
        result.put("submittedInterpretations", submitted);
        result.put("successInterpretations", succeeded);
        return result;
    }

    /** 各算法的真实调用与成功情况。 */
    @GetMapping("/algorithms")
    public List<Map<String, Object>> algorithms() {
        List<Task> allTasks = tasks.findAll();
        List<Interpretation> allResults = interpretations.findAll();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String algorithm : ALGORITHMS) {
            long taskCount = allTasks.stream().filter(t -> algorithm.equals(t.getAlgorithm())).count();
            long total = allResults.stream().filter(r -> algorithm.equals(r.getAlgorithm())).count();
            long success = allResults.stream().filter(r -> algorithm.equals(r.getAlgorithm()) && "SUCCESS".equals(r.getStatus())).count();
            long failed = allResults.stream().filter(r -> algorithm.equals(r.getAlgorithm()) && "FAILED".equals(r.getStatus())).count();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("algorithm", algorithm);
            item.put("tasks", taskCount);
            item.put("interpretations", total);
            item.put("success", success);
            item.put("failed", failed);
            list.add(item);
        }
        return list;
    }
}

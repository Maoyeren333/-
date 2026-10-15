package com.uav.remote.controller;

import com.uav.remote.entity.Task;
import com.uav.remote.service.TaskService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@CrossOrigin
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) { this.service = service; }

    @GetMapping
    public List<Task> list(@RequestParam(required = false) String keyword,
                          @RequestParam(required = false) String status) {
        return service.list(keyword, status);
    }

    /** 分页任务列表。 */
    @GetMapping("/page")
    public Map<String, Object> page(@RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int size,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String status) {
        return service.page(keyword, status, page, size);
    }

    @GetMapping("/{id}")
    public Task get(@PathVariable Long id) { return service.get(id); }

    /** 任务详情：任务 + 影像 + 该任务的解译记录。 */
    @GetMapping("/{id}/detail")
    public Map<String, Object> detail(@PathVariable Long id) { return service.detail(id); }

    @PostMapping
    public Task create(@RequestBody Task task) { return service.create(task); }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        service.delete(id);
        return Map.of("status", "DELETED", "id", id);
    }

    /** 启动解译。未提供 image_path 时自动使用该任务已上传的影像（默认第一张）。 */
    @PostMapping("/{id}/interpretations")
    public Map<String, Object> run(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> payload = body == null ? Map.of() : body;
        Task task = service.get(id);
        String algorithm = payload.get("algorithm");
        if (algorithm == null || algorithm.isBlank()) algorithm = task.getAlgorithm();

        String input = service.resolveInput(id, algorithm, payload.get("image_path"));
        service.interpret(id, algorithm, input);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUBMITTED");
        response.put("algorithm", algorithm);
        response.put("imagePath", input);
        return response;
    }
}

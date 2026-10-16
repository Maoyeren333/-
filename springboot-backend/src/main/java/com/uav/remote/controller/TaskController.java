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
    public record InterpretationRequest(String algorithm, List<Long> image_ids, String image_path) { }
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

    /** Each selected image produces a separate interpretation; defaults to all task images. */
    @PostMapping("/{id}/interpretations")
    public Map<String, Object> run(@PathVariable Long id, @RequestBody(required = false) InterpretationRequest body) {
        InterpretationRequest payload = body == null ? new InterpretationRequest(null, null, null) : body;
        Task task = service.get(id);
        String algorithm = payload.algorithm();
        if (algorithm == null || algorithm.isBlank()) algorithm = task.getAlgorithm();

        List<String> inputs = service.resolveInputs(id, algorithm, payload.image_ids(), payload.image_path());
        service.interpret(id, algorithm, inputs);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUBMITTED");
        response.put("algorithm", algorithm);
        response.put("imagePath", inputs.get(0));
        response.put("imagePaths", inputs);
        response.put("imageCount", inputs.size());
        return response;
    }
}

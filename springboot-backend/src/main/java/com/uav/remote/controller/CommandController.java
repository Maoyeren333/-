package com.uav.remote.controller;

import com.uav.remote.entity.Interpretation;
import com.uav.remote.entity.Task;
import com.uav.remote.feign.InferenceClient;
import com.uav.remote.repository.InterpretationRepository;
import com.uav.remote.repository.RemoteImageRepository;
import com.uav.remote.repository.TaskRepository;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能指挥中心：平台状态与最近活动全部来自真实探测结果。
 *
 * - Python 推理服务：调用 /health，失败即 DOWN；
 * - MySQL：执行一次 count 查询并计时；
 * - 天气源：真实请求 Open-Meteo，带超时，避免拖慢页面；
 * - 最近活动：由任务与解译记录合成。
 */
@RestController
@RequestMapping("/api/command")
public class CommandController {
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final TaskRepository tasks;
    private final InterpretationRepository interpretations;
    private final RemoteImageRepository images;
    private final InferenceClient inference;
    private final RestClient weatherClient;

    public CommandController(TaskRepository tasks, InterpretationRepository interpretations, RemoteImageRepository images,
                            InferenceClient inference, RestClient.Builder builder) {
        this.tasks = tasks;
        this.interpretations = interpretations;
        this.images = images;
        this.inference = inference;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(4000);
        this.weatherClient = builder.clone().requestFactory(factory).build();
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        List<Map<String, Object>> services = new ArrayList<>();

        long dbStarted = System.currentTimeMillis();
        long taskCount = tasks.count();
        long imageCount = images.count();
        long interpretationCount = interpretations.count();
        long dbLatency = System.currentTimeMillis() - dbStarted;

        Map<String, Object> scheduler = new LinkedHashMap<>();
        scheduler.put("name", "Spring Boot Scheduler");
        scheduler.put("detail", "任务编排与元数据");
        scheduler.put("status", "ONLINE");
        scheduler.put("latencyMs", dbLatency);
        services.add(scheduler);

        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("name", "Python AI Engines");
        long engineStarted = System.currentTimeMillis();
        try {
            Map<String, Object> health = inference.health();
            boolean up = "UP".equals(String.valueOf(health.get("status")));
            engine.put("status", up ? "ONLINE" : "DOWN");
            engine.put("detail", "torch=" + health.get("torch_available") + " · device=" + health.get("device"));
        } catch (Exception ex) {
            engine.put("status", "DOWN");
            engine.put("detail", "推理服务不可达：" + (ex.getMessage() == null ? "未知错误" : ex.getMessage()));
        }
        engine.put("latencyMs", System.currentTimeMillis() - engineStarted);
        services.add(engine);

        Map<String, Object> database = new LinkedHashMap<>();
        database.put("name", "MySQL Metadata Store");
        database.put("detail", taskCount + " 任务 · " + imageCount + " 影像 · " + interpretationCount + " 成果");
        database.put("status", "ONLINE");
        database.put("latencyMs", dbLatency);
        services.add(database);

        Map<String, Object> weather = new LinkedHashMap<>();
        weather.put("name", "Weather & Air Quality");
        long weatherStarted = System.currentTimeMillis();
        try {
            Map<?, ?> body = weatherClient.get()
                    .uri(UriComponentsBuilder.newInstance().scheme("https").host("api.open-meteo.com")
                            .path("/v1/forecast").queryParam("latitude", 31.2304).queryParam("longitude", 121.4737)
                            .queryParam("current", "temperature_2m").build().toUri())
                    .retrieve().body(Map.class);
            weather.put("status", body == null ? "DEGRADED" : "LIVE");
            weather.put("detail", "Open-Meteo data source");
        } catch (Exception ex) {
            weather.put("status", "DOWN");
            weather.put("detail", "天气接口暂不可用");
        }
        weather.put("latencyMs", System.currentTimeMillis() - weatherStarted);
        services.add(weather);

        long online = services.stream().filter(s -> "ONLINE".equals(s.get("status")) || "LIVE".equals(s.get("status"))).count();

        boolean weightsMissing = false;
        if ("ONLINE".equals(engine.get("status"))) {
            try {
                Object raw = inference.algorithms().get("algorithms");
                if (raw instanceof List<?> list) {
                    weightsMissing = list.stream().filter(Map.class::isInstance).map(Map.class::cast)
                            .anyMatch(item -> !Boolean.TRUE.equals(item.get("weights_ready")));
                }
            } catch (Exception ignored) {
                weightsMissing = false;
            }
        }
        long pendingTasks = tasks.countByStatus("QUEUED") + tasks.countByStatus("PROCESSING");
        long failedResults = interpretations.countByStatus("FAILED");
        long tasksWithoutImage = tasks.findAll().stream()
                .filter(task -> images.findByTaskIdOrderByIdAsc(task.getId()).isEmpty())
                .count();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("tasks", taskCount);
        metrics.put("images", imageCount);
        metrics.put("interpretations", interpretationCount);
        metrics.put("successInterpretations", interpretations.countByStatus("SUCCESS"));
        metrics.put("completedTasks", tasks.countByStatus("COMPLETED"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", LocalDateTime.now().format(MINUTE));
        result.put("onlineCount", online);
        result.put("serviceCount", services.size());
        result.put("services", services);
        result.put("metrics", metrics);
        result.put("events", recentEvents());
        result.put("tips", tips(engine.get("status"), weightsMissing, tasksWithoutImage, pendingTasks, failedResults, interpretationCount));
        return result;
    }

    /** 今日工作建议：按真实状态生成，不再写死文案。 */
    private List<Map<String, Object>> tips(Object engineStatus, boolean weightsMissing, long tasksWithoutImage,
                                           long pendingTasks, long failedResults, long interpretationCount) {
        List<Map<String, Object>> tips = new ArrayList<>();

        if (!"ONLINE".equals(engineStatus)) {
            tips.add(tip(tips.size(), "启动推理服务", "在 python-inference 目录运行 .venv/bin/uvicorn main:app --port 8000，否则解译会直接失败"));
        } else if (weightsMissing) {
            tips.add(tip(tips.size(), "放入模型权重", "把 c2fnet.pth / stff_ga.pth / st_unet.pth 放进 python-inference/weights，当前为随机初始化权重"));
        } else {
            tips.add(tip(tips.size(), "模型已就绪", "三个算法都已加载权重，可直接批量解译"));
        }

        if (tasksWithoutImage > 0) {
            tips.add(tip(tips.size(), "先补影像", tasksWithoutImage + " 个任务还没有影像，变化检测需要上传两张"));
        } else if (pendingTasks > 0) {
            tips.add(tip(tips.size(), "启动待解译任务", pendingTasks + " 个任务处于排队或处理中，可在任务页点击开始解译"));
        } else if (failedResults > 0) {
            tips.add(tip(tips.size(), "排查失败解译", failedResults + " 条解译记录失败，可在结果中心查看并重跑"));
        } else if (interpretationCount > 0) {
            tips.add(tip(tips.size(), "导出成果", "解译成功后可在成果导出打包下载 GeoTIFF 与预览图"));
        } else {
            tips.add(tip(tips.size(), "跑通第一次解译", "上传影像 → 选择算法 → 开始解译 → 在结果中心查看掩膜"));
        }
        return tips;
    }

    private Map<String, Object> tip(int index, String title, String text) {
        Map<String, Object> tip = new LinkedHashMap<>();
        tip.put("number", String.format("%02d", index + 1));
        tip.put("title", title);
        tip.put("text", text);
        return tip;
    }

    private List<Map<String, Object>> recentEvents() {
        List<Event> events = new ArrayList<>();
        for (Task task : tasks.findAll()) {
            events.add(new Event(task.getCreatedAt(), "INFO", "创建任务 · " + task.getName(),
                    join(task.getRegion(), algorithmLabel(task.getAlgorithm())) + " · " + task.getStatus()));
        }
        for (Interpretation result : interpretations.findAll()) {
            boolean ok = "SUCCESS".equals(result.getStatus());
            String detail = "任务 #" + result.getTaskId() + " · " + algorithmLabel(result.getAlgorithm());
            events.add(new Event(result.getCreatedAt(), ok ? "SUCCESS" : "FAILED",
                    (ok ? "解译完成 · " : "解译失败 · ") + algorithmLabel(result.getAlgorithm()),
                    result.getMaskPath() == null ? detail : detail + " · " + result.getMaskPath()));
        }
        return events.stream()
                .filter(e -> e.at != null)
                .sorted(Comparator.comparing((Event e) -> e.at).reversed())
                .limit(6)
                .map(Event::toMap)
                .toList();
    }

    private String join(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? "" : second;
        return first + " · " + second;
    }

    static String algorithmLabel(String algorithm) {
        if (algorithm == null) return "未指定算法";
        return switch (algorithm) {
            case "camouflaged_object_detection" -> "伪装目标检测";
            case "change_detection" -> "变化检测";
            case "semantic_segmentation" -> "语义分割";
            default -> algorithm;
        };
    }

    private record Event(LocalDateTime at, String level, String title, String detail) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("time", at.format(MINUTE));
            map.put("level", level);
            map.put("title", title);
            map.put("detail", detail);
            return map;
        }
    }
}

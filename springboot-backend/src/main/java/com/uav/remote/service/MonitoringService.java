package com.uav.remote.service;

import com.uav.remote.entity.Task;
import com.uav.remote.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.Instant;
import java.util.*;

@Service
public class MonitoringService {
    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    private final RestClient client;
    private final TaskRepository tasks;

    public MonitoringService(RestClient.Builder builder, TaskRepository tasks) {
        this.client = builder.build();
        this.tasks = tasks;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> overview(double latitude, double longitude, String locationName) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> weatherRaw = getWeather(latitude, longitude, errors);
        Map<String, Object> airRaw = getAirQuality(latitude, longitude, errors);
        Map<String, Object> current = mapValue(weatherRaw, "current");
        Map<String, Object> air = mapValue(airRaw, "current");

        double wind = number(current, "wind_speed_10m");
        double gust = number(current, "wind_gusts_10m");
        double rain = number(current, "rain");
        double visibility = number(current, "visibility");
        double uv = number(current, "uv_index");
        double aqi = number(air, "us_aqi");

        List<String> reasons = new ArrayList<>();
        int score = 0;
        if (gust >= 45 || wind >= 30) { score += 40; reasons.add("风速或阵风较大，影响无人机稳定性"); }
        else if (wind >= 18) { score += 20; reasons.add("风力偏强，建议降低飞行高度"); }
        if (rain >= 2) { score += 30; reasons.add("当前有明显降雨，存在设备受潮风险"); }
        else if (rain > 0) { score += 12; reasons.add("有降雨，建议关注航线能见度"); }
        if (visibility > 0 && visibility < 3000) { score += 25; reasons.add("能见度偏低，影响图像质量与返航安全"); }
        else if (visibility > 0 && visibility < 6000) { score += 10; reasons.add("能见度一般，建议加强现场观察"); }
        if (aqi >= 150) { score += 15; reasons.add("空气质量较差，不建议长时间外场作业"); }
        else if (aqi >= 100) { score += 7; reasons.add("空气质量一般，建议做好人员防护"); }
        if (uv >= 8) { score += 5; reasons.add("紫外线较强，注意设备与人员防护"); }
        if (reasons.isEmpty()) reasons.add("当前气象和环境条件适合开展常规遥感作业");

        String level = score >= 70 ? "HIGH" : score >= 35 ? "MEDIUM" : "LOW";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("location", Map.of("name", locationName == null || locationName.isBlank() ? "当前作业区" : locationName, "latitude", latitude, "longitude", longitude));
        result.put("updatedAt", Instant.now().toString());
        result.put("source", errors.isEmpty() ? "Open-Meteo" : "Open-Meteo-PARTIAL");
        result.put("errors", errors);
        result.put("weather", weather(current));
        result.put("environment", environment(air));
        result.put("risk", Map.of("level", level, "score", Math.min(score, 100), "reasons", reasons, "advice", advice(level)));
        List<Map<String, Object>> situationPoints = situation(latitude, longitude, locationName);
        long estimatedPoints = situationPoints.stream().filter(item -> Boolean.TRUE.equals(item.get("estimated"))).count();
        result.put("situation", situationPoints);
        result.put("situationNote", estimatedPoints == 0
                ? "任务区点位使用任务自身录入的经纬度定位（图面为以作业区中心为原点、约 2 km 范围）"
                : estimatedPoints + " 个任务未录入经纬度，其点位为相对方位示意；新建任务时填写经纬度即可真实定位");
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getWeather(double latitude, double longitude, List<String> errors) {
        try {
            return client.get().uri(UriComponentsBuilder.newInstance().scheme("https").host("api.open-meteo.com").path("/v1/forecast").queryParam("latitude", latitude).queryParam("longitude", longitude).queryParam("current", "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,rain,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility,uv_index").queryParam("timezone", "auto").build().toUri()).retrieve().body(Map.class);
        } catch (RestClientException | IllegalArgumentException ex) {
            log.warn("weather api unavailable: {}", ex.getMessage()); errors.add("天气接口暂时不可用"); return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAirQuality(double latitude, double longitude, List<String> errors) {
        try {
            return client.get().uri(UriComponentsBuilder.newInstance().scheme("https").host("air-quality-api.open-meteo.com").path("/v1/air-quality").queryParam("latitude", latitude).queryParam("longitude", longitude).queryParam("current", "us_aqi,european_aqi,pm2_5,pm10,carbon_monoxide,nitrogen_dioxide,ozone,dust,uv_index").queryParam("timezone", "auto").build().toUri()).retrieve().body(Map.class);
        } catch (RestClientException | IllegalArgumentException ex) {
            log.warn("air quality api unavailable: {}", ex.getMessage()); errors.add("空气质量接口暂时不可用"); return Map.of();
        }
    }

    private Map<String, Object> mapValue(Map<String, Object> source, String key) { if (source == null) return Map.of(); Object value = source.get(key); return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    private double number(Map<String, Object> source, String key) { Object value = source.get(key); return value instanceof Number n ? n.doubleValue() : 0D; }
    private Map<String, Object> weather(Map<String, Object> current) { Map<String, Object> out = new LinkedHashMap<>(); out.put("temperature", number(current, "temperature_2m")); out.put("apparentTemperature", number(current, "apparent_temperature")); out.put("humidity", number(current, "relative_humidity_2m")); out.put("precipitation", number(current, "precipitation")); out.put("rain", number(current, "rain")); out.put("weatherCode", (int) number(current, "weather_code")); out.put("weatherText", weatherText((int) number(current, "weather_code"))); out.put("windSpeed", number(current, "wind_speed_10m")); out.put("windDirection", number(current, "wind_direction_10m")); out.put("windGusts", number(current, "wind_gusts_10m")); out.put("visibility", number(current, "visibility")); out.put("uvIndex", number(current, "uv_index")); return out; }
    private Map<String, Object> environment(Map<String, Object> air) { Map<String, Object> out = new LinkedHashMap<>(); out.put("usAqi", number(air, "us_aqi")); out.put("europeanAqi", number(air, "european_aqi")); out.put("pm25", number(air, "pm2_5")); out.put("pm10", number(air, "pm10")); out.put("ozone", number(air, "ozone")); out.put("nitrogenDioxide", number(air, "nitrogen_dioxide")); out.put("dust", number(air, "dust")); return out; }
    private String weatherText(int code) { if (code == 0) return "晴"; if (code <= 3) return "少云/多云"; if (code <= 48) return "雾"; if (code <= 67) return "降雨"; if (code <= 77) return "降雪"; if (code <= 82) return "阵雨"; if (code <= 99) return "雷雨"; return "未知"; }
    private String advice(String level) { return "HIGH".equals(level) ? "建议暂停起飞，等待气象条件改善" : "MEDIUM".equals(level) ? "建议缩短航线并安排现场安全员" : "可按计划作业，持续关注天气变化"; }
    /**
     * 态势点位：环境点为作业区中心；任务区点位优先用任务自身的经纬度真实定位，
     * 未录入坐标的任务退化为绕中心的相对方位示意（点位上带 estimated 标记）。
     */
    private List<Map<String, Object>> situation(double latitude, double longitude, String locationName) {
        List<Map<String, Object>> points = new ArrayList<>();
        points.add(point("ENV-AREA", locationName == null || locationName.isBlank() ? "当前作业区" : locationName,
                "ENV", "ONLINE", latitude, longitude, 50, 50, false));

        List<Task> recent = tasks.findAll().stream()
                .sorted(Comparator.comparing(Task::getId, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(3)
                .toList();

        double span = 0.02; // 图面半幅对应的经纬度跨度（约 2 km）
        for (int i = 0; i < recent.size(); i++) {
            Task task = recent.get(i);
            boolean hasCoordinate = task.getLatitude() != null && task.getLongitude() != null;
            if (hasCoordinate) {
                double x = 50 + (task.getLongitude() - longitude) / span * 50;
                double y = 50 - (task.getLatitude() - latitude) / span * 50;
                points.add(point("TASK-" + task.getId(), task.getName(), "TASK", task.getStatus(),
                        task.getLatitude(), task.getLongitude(), clamp(x), clamp(y), false));
            } else {
                double angle = Math.toRadians(-90 + i * 120);
                points.add(point("TASK-" + task.getId(), task.getName(), "TASK", task.getStatus(),
                        latitude + 0.004 * Math.sin(angle), longitude + 0.004 * Math.cos(angle),
                        clamp(50 + Math.cos(angle) * 24), clamp(50 + Math.sin(angle) * 22), true));
            }
        }
        return points;
    }

    private double clamp(double value) { return Math.max(8, Math.min(92, value)); }

    private Map<String, Object> point(String id, String name, String type, String status,
                                      double latitude, double longitude, double x, double y, boolean estimated) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id);
        point.put("name", name);
        point.put("type", type);
        point.put("status", status);
        point.put("latitude", latitude);
        point.put("longitude", longitude);
        point.put("x", Math.round(x * 10) / 10.0);
        point.put("y", Math.round(y * 10) / 10.0);
        point.put("estimated", estimated);
        return point;
    }
}

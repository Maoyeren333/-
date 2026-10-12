package com.uav.remote.controller;

import com.uav.remote.service.MonitoringService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/monitor")
public class MonitoringController {
    private final MonitoringService monitoring;
    public MonitoringController(MonitoringService monitoring) { this.monitoring = monitoring; }
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "31.2304") double latitude, @RequestParam(defaultValue = "121.4737") double longitude, @RequestParam(defaultValue = "上海作业区") String locationName) { return monitoring.overview(latitude, longitude, locationName); }
}

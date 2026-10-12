package com.uav.remote.controller;

import com.uav.remote.feign.InferenceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 算法中心接口：把 Python 推理服务的真实状态透传给前端，
 * 让“智能解译”页面显示的是实际就绪情况（是否安装 torch、权重是否就绪），而不是写死的健康度。
 */
@RestController
@RequestMapping("/api/algorithms")
public class AlgorithmController {
    private final InferenceClient inference;

    public AlgorithmController(InferenceClient inference) { this.inference = inference; }

    @GetMapping
    public Map<String, Object> list() {
        try {
            return inference.algorithms();
        } catch (Exception ex) {
            Map<String, Object> offline = new LinkedHashMap<>();
            offline.put("online", false);
            offline.put("torch_available", false);
            offline.put("device", "unknown");
            offline.put("algorithms", List.of());
            offline.put("message", ex.getMessage() == null ? "推理服务不可用" : ex.getMessage());
            return offline;
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        try {
            return inference.health();
        } catch (Exception ex) {
            return Map.of("status", "DOWN", "message", ex.getMessage() == null ? "推理服务不可用" : ex.getMessage());
        }
    }
}

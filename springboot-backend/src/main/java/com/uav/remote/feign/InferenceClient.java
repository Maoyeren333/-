package com.uav.remote.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "inference", url = "${inference.url}")
public interface InferenceClient {
    /** C2FNet 使用单张影像 image_path。 */
    @PostMapping("/inference/{algorithm}")
    Map<String, Object> infer(@PathVariable String algorithm, @RequestBody Map<String, Object> body);

    @GetMapping("/health")
    Map<String, Object> health();

    @GetMapping("/algorithms")
    Map<String, Object> algorithms();
}

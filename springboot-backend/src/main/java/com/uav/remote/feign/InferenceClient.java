package com.uav.remote.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "inference", url = "${inference.url}")
public interface InferenceClient {
    /** 变化检测需同时传 image_path 与 image_path_b，其余算法只用 image_path。 */
    @PostMapping("/inference/{algorithm}")
    Map<String, Object> infer(@PathVariable String algorithm, @RequestBody Map<String, Object> body);

    @GetMapping("/health")
    Map<String, Object> health();

    @GetMapping("/algorithms")
    Map<String, Object> algorithms();
}

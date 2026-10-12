package com.uav.remote.controller;

import com.uav.remote.entity.Interpretation;
import com.uav.remote.repository.InterpretationRepository;
import com.uav.remote.service.FileCleaner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/interpretations")
public class InterpretationController {
    private final InterpretationRepository repo;

    public InterpretationController(InterpretationRepository repo) { this.repo = repo; }

    /** 支持按 taskId / status / algorithm 过滤，按 id 倒序返回。 */
    @GetMapping
    public List<Interpretation> list(@RequestParam(required = false) Long taskId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String algorithm) {
        List<Interpretation> all = taskId != null ? repo.findByTaskIdOrderByIdDesc(taskId) : repo.findAllByOrderByIdDesc();
        return all.stream()
                .filter(item -> status == null || status.isBlank() || status.equals(item.getStatus()))
                .filter(item -> algorithm == null || algorithm.isBlank() || algorithm.equals(item.getAlgorithm()))
                .toList();
    }

    /** 分页解译记录，供结果中心表格使用。 */
    @GetMapping("/page")
    public Map<String, Object> page(@RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int size,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String algorithm) {
        int safePage = Math.max(1, page);
        Pageable pageable = PageRequest.of(safePage - 1, Math.max(1, Math.min(size, 100)));
        Page<Interpretation> result = repo.searchPaged(blankToNull(status), blankToNull(algorithm), pageable);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", result.getContent());
        payload.put("total", result.getTotalElements());
        payload.put("page", safePage);
        payload.put("size", pageable.getPageSize());
        payload.put("pages", result.getTotalPages());
        return payload;
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    @GetMapping("/{id}")
    public Interpretation get(@PathVariable Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("解译记录不存在"));
    }

    /** 结果预览图（PNG），供前端直接 <img> 展示。 */
    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> preview(@PathVariable Long id) {
        return fileResponse(FileCleaner.previewPathOf(get(id).getMaskPath()), MediaType.IMAGE_PNG, null);
    }

    /** 原始 GeoTIFF 成果下载。 */
    @GetMapping("/{id}/mask")
    public ResponseEntity<Resource> mask(@PathVariable Long id) {
        return fileResponse(get(id).getMaskPath(), MediaType.parseMediaType("image/tiff"), "result.tif");
    }

    /** 删除解译记录，同时清理磁盘上的结果文件。 */
    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable Long id) {
        Interpretation result = get(id);
        FileCleaner.deleteResultFiles(result.getMaskPath());
        repo.delete(result);
        return Map.of("status", "DELETED", "id", id);
    }

    private ResponseEntity<Resource> fileResponse(String path, MediaType mediaType, String downloadName) {
        if (path == null) return ResponseEntity.notFound().build();
        File file = new File(path);
        if (!file.isFile()) return ResponseEntity.notFound().build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentLength(file.length());
        if (downloadName != null) headers.setContentDispositionFormData("attachment", downloadName);
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file));
    }
}

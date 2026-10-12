package com.uav.remote.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.remote.entity.Interpretation;
import com.uav.remote.entity.Task;
import com.uav.remote.repository.InterpretationRepository;
import com.uav.remote.repository.TaskRepository;
import com.uav.remote.service.FileCleaner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 成果导出：清单来自真实解译记录，打包内容为磁盘上真实存在的成果文件。
 * 结果文件缺失（被清理过）时会标记 ready=false，前端据此禁用下载。
 */
@RestController
@RequestMapping("/api/exports")
public class ExportController {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final InterpretationRepository interpretations;
    private final TaskRepository tasks;
    private final ObjectMapper objectMapper;

    public ExportController(InterpretationRepository interpretations, TaskRepository tasks, ObjectMapper objectMapper) {
        this.interpretations = interpretations;
        this.tasks = tasks;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Interpretation result : interpretations.findByStatusOrderByIdDesc("SUCCESS")) {
            items.add(describe(result));
        }
        return items;
    }

    @GetMapping("/{id}/package")
    public ResponseEntity<byte[]> packageOne(@PathVariable Long id) throws IOException {
        Interpretation result = interpretations.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("解译记录不存在"));
        if (!"SUCCESS".equals(result.getStatus())) throw new IllegalArgumentException("只有成功的解译成果可以导出");
        Map<String, Object> item = describe(result);
        byte[] zip = buildZip(List.of(item), false);
        return attachment(zip, "uav_result_" + id + ".zip");
    }

    @GetMapping("/package")
    public ResponseEntity<byte[]> packageAll() throws IOException {
        List<Map<String, Object>> items = list().stream().filter(item -> Boolean.TRUE.equals(item.get("ready"))).toList();
        if (items.isEmpty()) throw new IllegalArgumentException("暂无可导出的成果文件");
        byte[] zip = buildZip(items, true);
        return attachment(zip, "uav_results_" + LocalDateTime.now().format(STAMP) + ".zip");
    }

    private Map<String, Object> describe(Interpretation result) {
        Task task = result.getTaskId() == null ? null : tasks.findById(result.getTaskId()).orElse(null);
        String maskPath = result.getMaskPath();
        String previewPath = FileCleaner.previewPathOf(maskPath);
        long maskSize = FileCleaner.sizeOf(maskPath);
        long previewSize = FileCleaner.sizeOf(previewPath);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", result.getId());
        item.put("taskId", result.getTaskId());
        item.put("taskName", task == null ? "任务已删除" : task.getName());
        item.put("algorithm", result.getAlgorithm());
        item.put("algorithmLabel", CommandController.algorithmLabel(result.getAlgorithm()));
        item.put("status", result.getStatus());
        item.put("sourcePath", result.getSourcePath());
        item.put("maskPath", maskPath);
        item.put("previewPath", previewPath);
        item.put("createdAt", result.getCreatedAt());
        item.put("maskSize", maskSize);
        item.put("previewSize", previewSize);
        item.put("maskExists", maskSize >= 0);
        item.put("previewExists", previewSize >= 0);
        item.put("ready", maskSize >= 0);
        return item;
    }

    private byte[] buildZip(List<Map<String, Object>> items, boolean withFolder) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map<String, Object> item : items) {
                String prefix = withFolder
                        ? item.get("algorithm") + "_" + item.get("id") + "/"
                        : "";
                addFile(zip, prefix + "result.tif", (String) item.get("maskPath"));
                addFile(zip, prefix + "preview.png", (String) item.get("previewPath"));
                addText(zip, prefix + "metadata.json", objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(metadataOf(item)));
            }
        }
        return buffer.toByteArray();
    }

    private Map<String, Object> metadataOf(Map<String, Object> item) {
        Map<String, Object> metadata = new HashMap<>(item);
        metadata.remove("previewPath");
        metadata.remove("maskPath");
        return metadata;
    }

    private void addFile(ZipOutputStream zip, String entryName, String path) throws IOException {
        if (path == null) return;
        File file = new File(path);
        if (!file.isFile()) return;
        zip.putNextEntry(new ZipEntry(entryName));
        Files.copy(file.toPath(), zip);
        zip.closeEntry();
    }

    private void addText(ZipOutputStream zip, String entryName, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(entryName));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private ResponseEntity<byte[]> attachment(byte[] body, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(body.length);
        headers.setContentDispositionFormData("attachment", filename);
        return ResponseEntity.ok().headers(headers).body(body);
    }
}

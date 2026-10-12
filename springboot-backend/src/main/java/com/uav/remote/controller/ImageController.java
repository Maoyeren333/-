package com.uav.remote.controller;

import com.uav.remote.entity.RemoteImage;
import com.uav.remote.repository.RemoteImageRepository;
import com.uav.remote.service.FileCleaner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/tasks/{taskId}/images")
public class ImageController {
    private static final Set<String> ALLOWED = Set.of("tif", "tiff", "jpg", "jpeg", "png");

    private final RemoteImageRepository repository;
    private final Path imageDir;

    /**
     * 影像落盘目录。必须以绝对路径参与写文件：MultipartFile.transferTo 对相对路径的解析基准是
     * Servlet 容器的临时工作目录，而不是进程工作目录。
     */
    public ImageController(RemoteImageRepository repository,
                          @Value("${storage.images-dir:./data/images}") String imagesDir) {
        this.repository = repository;
        this.imageDir = Path.of(imagesDir).toAbsolutePath().normalize();
    }

    @GetMapping
    public List<RemoteImage> list(@PathVariable Long taskId) { return repository.findByTaskIdOrderByIdAsc(taskId); }

    @PostMapping
    public RemoteImage upload(@PathVariable Long taskId, @RequestParam("file") MultipartFile file) throws IOException {
        String original = file.getOriginalFilename() == null ? "upload.tif" : file.getOriginalFilename();
        String safeName = new File(original).getName();
        String extension = safeName.contains(".") ? safeName.substring(safeName.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ALLOWED.contains(extension)) throw new IllegalArgumentException("仅支持 TIFF、JPG、PNG 影像");

        Files.createDirectories(imageDir);
        Path target = imageDir.resolve(taskId + "_" + System.currentTimeMillis() + "_" + safeName);
        file.transferTo(target.toFile());

        RemoteImage image = new RemoteImage();
        image.setTaskId(taskId);
        image.setFileName(safeName);
        image.setFilePath(target.toString());
        image.setFileSize(file.getSize());
        return repository.save(image);
    }

    @DeleteMapping("/{imageId}")
    @Transactional
    public Map<String, Object> delete(@PathVariable Long taskId, @PathVariable Long imageId) {
        RemoteImage image = repository.findById(imageId).orElseThrow(() -> new IllegalArgumentException("影像不存在"));
        if (!taskId.equals(image.getTaskId())) throw new IllegalArgumentException("影像不属于该任务");
        FileCleaner.deleteQuietly(image.getFilePath());
        repository.delete(image);
        return Map.of("status", "DELETED", "id", imageId);
    }
}

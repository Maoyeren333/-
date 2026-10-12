package com.uav.remote.service;

import java.io.File;

/** 磁盘文件清理的公共逻辑，避免各处重复处理路径与异常。 */
public final class FileCleaner {
    private FileCleaner() { }

    public static void deleteQuietly(String path) {
        if (path == null || path.isBlank()) return;
        try {
            File file = new File(path);
            if (file.isFile()) file.delete();
        } catch (RuntimeException ignored) {
            // 文件清理失败不影响业务结果
        }
    }

    /** 删除解译成果：结果 mask 与其同名预览图。 */
    public static void deleteResultFiles(String maskPath) {
        deleteQuietly(maskPath);
        if (maskPath == null) return;
        deleteQuietly(maskPath.replaceAll("(?i)\\.tiff?$", ".png"));
    }

    public static String previewPathOf(String maskPath) {
        if (maskPath == null) return null;
        if (maskPath.endsWith(".png")) return maskPath;
        return maskPath.replaceAll("(?i)\\.tiff?$", ".png");
    }

    public static long sizeOf(String path) {
        if (path == null || path.isBlank()) return -1L;
        File file = new File(path);
        return file.isFile() ? file.length() : -1L;
    }
}

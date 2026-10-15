package com.uav.remote.repository;

import com.uav.remote.entity.RemoteImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RemoteImageRepository extends JpaRepository<RemoteImage, Long> {
    /** 按上传顺序取任务影像：第 1 张用作 C2FNet 输入。 */
    List<RemoteImage> findByTaskIdOrderByIdAsc(Long taskId);

    void deleteByTaskId(Long taskId);
}

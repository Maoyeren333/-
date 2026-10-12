package com.uav.remote.repository;

import com.uav.remote.entity.Task;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {
    long countByStatus(String status);

    /** 关键字匹配任务名称或作业区域；keyword/status 为 null 时不过滤。 */
    @Query("select t from Task t where (:keyword is null or lower(t.name) like lower(concat('%', :keyword, '%')) "
            + "or lower(coalesce(t.region, '')) like lower(concat('%', :keyword, '%'))) "
            + "and (:status is null or t.status = :status) order by t.id desc")
    List<Task> search(@Param("keyword") String keyword, @Param("status") String status);

    @Query("select t from Task t where (:keyword is null or lower(t.name) like lower(concat('%', :keyword, '%')) "
            + "or lower(coalesce(t.region, '')) like lower(concat('%', :keyword, '%'))) "
            + "and (:status is null or t.status = :status) order by t.id desc")
    Page<Task> searchPaged(@Param("keyword") String keyword, @Param("status") String status, Pageable pageable);
}

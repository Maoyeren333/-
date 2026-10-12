package com.uav.remote.repository;

import com.uav.remote.entity.Interpretation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InterpretationRepository extends JpaRepository<Interpretation, Long> {
    List<Interpretation> findByTaskIdOrderByIdDesc(Long taskId);

    List<Interpretation> findByStatusOrderByIdDesc(String status);

    List<Interpretation> findAllByOrderByIdDesc();

    long countByStatus(String status);

    void deleteByTaskId(Long taskId);

    /** status/algorithm 为 null 时不过滤。 */
    @Query("select r from Interpretation r where (:status is null or r.status = :status) "
            + "and (:algorithm is null or r.algorithm = :algorithm) order by r.id desc")
    Page<Interpretation> searchPaged(@Param("status") String status, @Param("algorithm") String algorithm, Pageable pageable);
}

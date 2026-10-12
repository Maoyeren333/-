package com.uav.remote.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "uav_task")
@Data
@NoArgsConstructor
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String region;
    private String algorithm;
    private String status = "CREATED";
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 作业区中心坐标，用于实时监测页把任务点位画到真实位置；未填写时为空。 */
    private Double latitude;
    private Double longitude;
}

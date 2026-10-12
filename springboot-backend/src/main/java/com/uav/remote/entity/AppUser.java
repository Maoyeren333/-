package com.uav.remote.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "app_user")
@Data @NoArgsConstructor
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 60)
    private String username;
    @Column(nullable = false, unique = true, length = 120)
    private String email;
    @Column(nullable = false, length = 100)
    private String passwordHash;
    @Column(length = 80)
    private String displayName;
    @Column(length = 30)
    private String phone;
    @Column(length = 20)
    private String role = "USER";
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
}

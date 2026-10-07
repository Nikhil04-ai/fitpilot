package com.project.fitness.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private String id;
    private String email;
    // password intentionally omitted (Fix 3)
    private String firstName;
    private String lastName;

    // Feature 1: body metrics
    private Double weight;
    private Double height;
    private Integer age;

    // Feature 2: streak
    private Integer currentStreak;
    private Integer longestStreak;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

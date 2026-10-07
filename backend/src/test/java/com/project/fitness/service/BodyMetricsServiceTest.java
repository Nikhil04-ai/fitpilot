package com.project.fitness.service;

import com.project.fitness.model.User;
import com.project.fitness.repository.BodyMetricsLogRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BodyMetricsService — BMI feature
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BodyMetricsService Tests")
class BodyMetricsServiceTest {

    @Mock private BodyMetricsLogRepository bodyMetricsLogRepository;
    @Mock private UserRepository userRepository;
    // A weigh-in is the only thing that moves a TARGET_WEIGHT goal (PLAN P2-1).
    @Mock private GoalService goalService;
    @InjectMocks private BodyMetricsService bodyMetricsService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").weight(70.0).height(175.0).build();
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(bodyMetricsLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(goalService.refreshStateGoals(any())).thenReturn(java.util.List.of());
    }

    // ── BMI calculation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("70kg at 175cm: BMI = 22.9 (Normal weight)")
    void bmi_calculated_correctly() {
        // 70 / (1.75)^2 = 70 / 3.0625 = 22.857 ≈ 22.9
        Map<String, Object> result = bodyMetricsService.updateMetrics("user-1", 70.0, 175.0);

        assertThat(result.get("bmi")).isEqualTo(22.9);
        assertThat(result.get("bmiCategory")).isEqualTo("Normal weight");
    }

    @Test
    @DisplayName("50kg at 175cm: BMI = 16.3 (Underweight)")
    void bmi_underweight_category() {
        Map<String, Object> result = bodyMetricsService.updateMetrics("user-1", 50.0, 175.0);
        assertThat(result.get("bmiCategory")).isEqualTo("Underweight");
    }

    @Test
    @DisplayName("90kg at 175cm: BMI = 29.4 (Overweight)")
    void bmi_overweight_category() {
        Map<String, Object> result = bodyMetricsService.updateMetrics("user-1", 90.0, 175.0);
        assertThat(result.get("bmiCategory")).isEqualTo("Overweight");
    }

    @Test
    @DisplayName("110kg at 175cm: BMI = 35.9 (Obese)")
    void bmi_obese_category() {
        Map<String, Object> result = bodyMetricsService.updateMetrics("user-1", 110.0, 175.0);
        assertThat(result.get("bmiCategory")).isEqualTo("Obese");
    }

    // ── Healthy weight range ─────────────────────────────────────────────────

    @Test
    @DisplayName("updateMetrics returns healthy weight range for height")
    void updateMetrics_returnsHealthyWeightRange() {
        Map<String, Object> result = bodyMetricsService.updateMetrics("user-1", 70.0, 175.0);
        assertThat(result.get("healthyWeightRange")).asString().contains("kg");
    }

    // ── History is logged ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Every updateMetrics call saves a history log entry")
    void updateMetrics_savesHistoryLog() {
        bodyMetricsService.updateMetrics("user-1", 72.0, 175.0);
        bodyMetricsService.updateMetrics("user-1", 71.0, 175.0);

        verify(bodyMetricsLogRepository, times(2)).save(any());
    }

    // ── Partial update: only weight changed ───────────────────────────────────

    @Test
    @DisplayName("Partial update (weight only): height unchanged, BMI recalculated")
    void partialUpdate_weightOnly_heightUnchanged() {
        user.setHeight(175.0);
        Map<String, Object> result = bodyMetricsService.updateMetrics("user-1", 80.0, null);

        // BMI should be recalculated with new weight + existing height
        assertThat(result.get("bmi")).isNotNull();
        assertThat(result.get("height")).isEqualTo(175.0);
    }
}

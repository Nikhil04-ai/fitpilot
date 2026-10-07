package com.project.fitness.service;

import com.project.fitness.model.User;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.repository.WaterLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WaterIntakeService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaterIntakeService Tests")
class WaterIntakeServiceTest {

    @Mock private WaterLogRepository waterLogRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private WaterIntakeService waterIntakeService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").weight(70.0).build();
    }

    // ── Goal calculation from weight ──────────────────────────────────────────

    @Test
    @DisplayName("70kg user: daily goal = 70 * 35 = 2450ml")
    void dailyGoal_calculatedFromWeight() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(waterLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(waterLogRepository.sumByUserIdAndDate(eq("user-1"), any()))
                .thenReturn(500.0);

        Map<String, Object> result = waterIntakeService.logWater("user-1", 500, LocalDate.now());

        // 70 * 35 = 2450
        assertThat(result.get("goalMl")).isEqualTo(2450.0);
        assertThat(result.get("totalMl")).isEqualTo(500.0);
    }

    // ── Minimum goal enforced ─────────────────────────────────────────────────

    @Test
    @DisplayName("Very light user: minimum goal is 2000ml regardless")
    void minimumGoal_2000ml() {
        user.setWeight(40.0); // 40 * 35 = 1400 which is below minimum
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(waterLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(waterLogRepository.sumByUserIdAndDate(eq("user-1"), any())).thenReturn(0.0);

        // Any real drink will do: the test is about the goal. A 0 ml entry is refused now
        // (PLAN P4-17), and it only ever stood in for "no particular amount" here.
        Map<String, Object> result = waterIntakeService.logWater("user-1", 250, LocalDate.now());

        assertThat((Double) result.get("goalMl")).isGreaterThanOrEqualTo(2000.0);
    }

    // ── Goal met flag ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Total intake >= goal: goalMet is true")
    void goalMet_whenTotalReachesGoal() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(waterLogRepository.sumByUserIdAndDate(eq("user-1"), any()))
                .thenReturn(2500.0); // above 2450 goal

        Map<String, Object> result = waterIntakeService.getDailySummary("user-1", LocalDate.now());

        assertThat(result.get("goalMet")).isEqualTo(true);
        assertThat(result.get("remainingMl")).isEqualTo(0.0);
    }

    // ── Remaining water calculation ───────────────────────────────────────────

    @Test
    @DisplayName("Partial intake: remainingMl = goal - total")
    void remainingMl_correctlyCalculated() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(waterLogRepository.sumByUserIdAndDate(eq("user-1"), any()))
                .thenReturn(1000.0);

        Map<String, Object> result = waterIntakeService.getDailySummary("user-1", LocalDate.now());

        double remaining = (Double) result.get("remainingMl");
        double goal = (Double) result.get("goalMl");
        assertThat(remaining).isEqualTo(goal - 1000.0);
    }

    // ── Progress percentage capped at 100 ────────────────────────────────────

    @Test
    @DisplayName("Over-hydrated: progress capped at 100%")
    void progressPercent_cappedAt100() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(waterLogRepository.sumByUserIdAndDate(eq("user-1"), any()))
                .thenReturn(5000.0); // way over goal

        Map<String, Object> result = waterIntakeService.getDailySummary("user-1", LocalDate.now());

        assertThat((Double) result.get("progressPercent")).isLessThanOrEqualTo(100.0);
    }

    // ── Status messages ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Zero intake: status is 'Very low — drink water now!'")
    void zeroIntake_veryLowStatus() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(waterLogRepository.sumByUserIdAndDate(eq("user-1"), any())).thenReturn(0.0);

        Map<String, Object> result = waterIntakeService.getDailySummary("user-1", LocalDate.now());

        assertThat(result.get("status").toString()).contains("Very low");
    }

    @Test
    @DisplayName("Full goal met: status is 'Fully hydrated'")
    void fullGoalMet_fullyHydratedStatus() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(waterLogRepository.sumByUserIdAndDate(eq("user-1"), any())).thenReturn(3000.0);

        Map<String, Object> result = waterIntakeService.getDailySummary("user-1", LocalDate.now());

        assertThat(result.get("status").toString()).contains("Fully hydrated");
    }
}

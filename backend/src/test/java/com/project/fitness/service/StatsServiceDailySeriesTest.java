package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.PersonalBestRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The per-day series behind the volume chart (PLAN P1-3).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatsService daily series")
class StatsServiceDailySeriesTest {

    @Mock private ActivityRepository activityRepository;
    @Mock private UserRepository userRepository;
    @Mock private PersonalBestRepository personalBestRepository;
    @InjectMocks private StatsService statsService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").build();
    }

    private Activity at(LocalDateTime when, int calories, int minutes) {
        return Activity.builder()
                .user(user).type(ActivityType.RUNNING)
                .startTime(when).caloriesBurned(calories).duration(minutes)
                .build();
    }

    private void given(List<Activity> activities) {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                .thenReturn(activities);
    }

    @Test
    @DisplayName("returns one row per day including days with nothing logged")
    void fillsRestDaysWithZeroes() {
        LocalDate today = LocalDate.now();
        given(List.of(at(today.atTime(7, 0), 300, 30)));

        List<Map<String, Object>> series = statsService.getDailySeries("user-1", 7);

        assertThat(series).hasSize(7);
        assertThat(series.get(0).get("date")).isEqualTo(today.minusDays(6).toString());
        assertThat(series.get(6).get("date")).isEqualTo(today.toString());

        // Six rest days, all present and all zero — not omitted.
        assertThat(series.subList(0, 6))
                .allSatisfy(row -> assertThat(row.get("calories")).isEqualTo(0));
        assertThat(series.get(6).get("calories")).isEqualTo(300);
        assertThat(series.get(6).get("minutes")).isEqualTo(30);
        assertThat(series.get(6).get("workouts")).isEqualTo(1);
    }

    @Test
    @DisplayName("sums several activities logged on the same day")
    void sumsWithinADay() {
        LocalDate today = LocalDate.now();
        given(List.of(
                at(today.atTime(6, 30), 200, 20),
                at(today.atTime(18, 0), 450, 40)));

        List<Map<String, Object>> series = statsService.getDailySeries("user-1", 3);

        Map<String, Object> last = series.get(series.size() - 1);
        assertThat(last.get("calories")).isEqualTo(650);
        assertThat(last.get("minutes")).isEqualTo(60);
        assertThat(last.get("workouts")).isEqualTo(2);
    }

    @Test
    @DisplayName("counts a workout with no calorie figure without tripping on the null")
    void toleratesNullMetrics() {
        LocalDate today = LocalDate.now();
        given(List.of(Activity.builder()
                .user(user).type(ActivityType.YOGA)
                .startTime(today.atTime(9, 0))
                .build()));

        Map<String, Object> last = statsService.getDailySeries("user-1", 2).get(1);
        assertThat(last.get("calories")).isEqualTo(0);
        assertThat(last.get("minutes")).isEqualTo(0);
        assertThat(last.get("workouts")).isEqualTo(1);
    }

    @Test
    @DisplayName("clamps the window so a client can't ask for an unbounded range")
    void clampsSpan() {
        given(List.of());
        assertThat(statsService.getDailySeries("user-1", 100_000)).hasSize(365);
        assertThat(statsService.getDailySeries("user-1", 0)).hasSize(1);
        assertThat(statsService.getDailySeries("user-1", -5)).hasSize(1);
    }

    @Test
    @DisplayName("a valid token whose user row is gone is a dead session, not an empty month")
    void deadSessionThrows() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> statsService.getDailySeries("ghost", 30))
                .isInstanceOf(AccountNotFoundException.class);
    }
}

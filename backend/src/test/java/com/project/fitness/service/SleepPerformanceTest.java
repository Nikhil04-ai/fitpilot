package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.SleepLog;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.SleepLogRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Sleep paired against the next day's training — the scatter chart's data (PLAN P1-3).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SleepService sleep-vs-performance")
class SleepPerformanceTest {

    @Mock private SleepLogRepository sleepLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private ActivityRepository activityRepository;
    @InjectMocks private SleepService sleepService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").build();
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    }

    private SleepLog night(LocalDate date, double hours, int quality) {
        return SleepLog.builder().user(user).sleepDate(date)
                .hoursSlept(hours).qualityRating(quality).build();
    }

    private Activity workout(LocalDate date, int calories, int minutes) {
        return Activity.builder().user(user).type(ActivityType.RUNNING)
                .startTime(date.atTime(8, 0))
                .caloriesBurned(calories).duration(minutes).build();
    }

    private void given(List<SleepLog> nights, List<Activity> activities) {
        when(sleepLogRepository.findByUserIdAndSleepDateBetweenOrderBySleepDateAsc(
                eq("user-1"), any(), any())).thenReturn(nights);
        when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                .thenReturn(activities);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pointsOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("points");
    }

    @Test
    @DisplayName("pairs a night with the NEXT day's session, not the same date")
    void pairsWithFollowingDay() {
        LocalDate monday = LocalDate.now().minusDays(5);
        given(List.of(night(monday, 8.0, 4)),
              List.of(workout(monday, 111, 11),            // before the sleep — must not be used
                      workout(monday.plusDays(1), 500, 45)));

        Map<String, Object> point = pointsOf(sleepService.getSleepPerformance("user-1", 30)).get(0);

        assertThat(point.get("sleepDate")).isEqualTo(monday.toString());
        assertThat(point.get("workoutDate")).isEqualTo(monday.plusDays(1).toString());
        assertThat(point.get("calories")).isEqualTo(500);
        assertThat(point.get("minutes")).isEqualTo(45);
        assertThat(point.get("trained")).isEqualTo(true);
    }

    @Test
    @DisplayName("keeps rest nights as zero-calorie points but leaves them out of the correlation")
    void restDaysShownButNotCorrelated() {
        LocalDate start = LocalDate.now().minusDays(10);
        List<SleepLog> nights = new ArrayList<>();
        List<Activity> activities = new ArrayList<>();
        // Four trained days with a clean positive relationship...
        for (int i = 0; i < 4; i++) {
            nights.add(night(start.plusDays(i), 6.0 + i, 4));
            activities.add(workout(start.plusDays(i + 1), 200 + (i * 100), 30));
        }
        // ...plus a rest night that would otherwise pull the line down.
        nights.add(night(start.plusDays(5), 9.0, 5));
        given(nights, activities);

        Map<String, Object> result = sleepService.getSleepPerformance("user-1", 30);

        assertThat(pointsOf(result)).hasSize(5);
        assertThat(pointsOf(result).get(4).get("trained")).isEqualTo(false);
        assertThat(pointsOf(result).get(4).get("calories")).isEqualTo(0);

        assertThat(result.get("pairedDays")).isEqualTo(4);
        assertThat((Double) result.get("correlation")).isEqualTo(1.0);
        assertThat((String) result.get("summary")).contains("more sleep goes with harder sessions");
    }

    @Test
    @DisplayName("reports no correlation below four paired days rather than inventing one")
    void needsFourPairedDays() {
        LocalDate start = LocalDate.now().minusDays(6);
        given(List.of(night(start, 7.0, 4), night(start.plusDays(1), 8.0, 4)),
              List.of(workout(start.plusDays(1), 300, 30), workout(start.plusDays(2), 400, 35)));

        Map<String, Object> result = sleepService.getSleepPerformance("user-1", 30);

        assertThat(result.get("pairedDays")).isEqualTo(2);
        assertThat(result.get("correlation")).isNull();
        assertThat((String) result.get("summary")).contains("at least 4 days");
    }

    @Test
    @DisplayName("reports no correlation when sleep never varies, instead of dividing by zero")
    void flatSleepHasNoCorrelation() {
        LocalDate start = LocalDate.now().minusDays(8);
        List<SleepLog> nights = new ArrayList<>();
        List<Activity> activities = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            nights.add(night(start.plusDays(i), 8.0, 4));           // identical every night
            activities.add(workout(start.plusDays(i + 1), 200 + (i * 50), 30));
        }
        given(nights, activities);

        Map<String, Object> result = sleepService.getSleepPerformance("user-1", 30);

        assertThat(result.get("correlation")).isNull();
        assertThat((String) result.get("summary")).contains("Not enough variation");
    }
}

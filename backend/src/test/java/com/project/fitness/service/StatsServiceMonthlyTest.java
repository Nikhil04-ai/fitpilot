package com.project.fitness.service;

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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * {@code weekOverWeekCalorieChange} on the monthly stats (PLAN P4-8).
 *
 * <p>It divided the 30-day total by one prior week, so a perfectly steady month read as
 * about +300%. The repository is stubbed over an in-memory list, filtered by the date
 * range each query asks for, so the windows the service picks are what's under test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StatsService monthly week-over-week")
class StatsServiceMonthlyTest {

    @Mock private ActivityRepository activityRepository;
    @Mock private UserRepository userRepository;
    @Mock private PersonalBestRepository personalBestRepository;
    @InjectMocks private StatsService statsService;

    private final User user = User.builder().id("user-1").currentStreak(0).longestStreak(0).build();
    private final List<Activity> logged = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        lenient().when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                .thenAnswer(i -> between(i.getArgument(1), i.getArgument(2)));
        lenient().when(activityRepository.sumCaloriesByUserIdAndDateRange(eq("user-1"), any(), any()))
                .thenAnswer(i -> between(i.getArgument(1), i.getArgument(2)).stream()
                        .mapToInt(Activity::getCaloriesBurned).sum());
        lenient().when(activityRepository.sumDurationByUserIdAndDateRange(eq("user-1"), any(), any()))
                .thenAnswer(i -> between(i.getArgument(1), i.getArgument(2)).stream()
                        .mapToInt(Activity::getDuration).sum());
    }

    /** BETWEEN in JPQL is inclusive at both ends. */
    private List<Activity> between(LocalDateTime from, LocalDateTime to) {
        return logged.stream()
                .filter(a -> !a.getStartTime().isBefore(from) && !a.getStartTime().isAfter(to))
                .toList();
    }

    private void workout(int daysAgo, int calories) {
        logged.add(Activity.builder().user(user).type(ActivityType.RUNNING)
                .startTime(LocalDateTime.now().minusDays(daysAgo).minusHours(1))
                .caloriesBurned(calories).duration(30).build());
    }

    private Object change() {
        return statsService.getMonthlyStats("user-1").get("weekOverWeekCalorieChange");
    }

    @Test
    @DisplayName("a steady month is no change, not +300%")
    void steadyMonthIsFlat() {
        // 500 kcal every week for four weeks: the old code compared 2000 with 500.
        for (int week = 0; week < 4; week++) workout(week * 7 + 2, 500);

        assertThat(change()).isEqualTo("+0.0%");
        assertThat(statsService.getMonthlyStats("user-1").get("totalCalories")).isEqualTo(2000);
    }

    @Test
    @DisplayName("compares the last 7 days with the 7 before them")
    void comparesTheLastTwoWeeks() {
        workout(2, 600);     // this week
        workout(9, 400);     // last week
        workout(20, 5000);   // earlier in the month: must not count

        assertThat(change()).isEqualTo("+50.0%");
    }

    @Test
    @DisplayName("a drop is negative")
    void dropIsNegative() {
        workout(3, 300);
        workout(10, 400);

        assertThat(change()).isEqualTo("-25.0%");
    }

    @Test
    @DisplayName("with nothing the week before there is no percentage, rather than a false +0.0%")
    void noPriorWeekIsNull() {
        workout(1, 500);

        assertThat(change()).isNull();
    }
}

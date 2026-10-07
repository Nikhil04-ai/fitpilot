package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The hour-of-day distribution behind the peak-hour histogram (PLAN P1-3).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimingAnalysisService Tests")
class TimingAnalysisServiceTest {

    @Mock private ActivityRepository activityRepository;
    @InjectMocks private TimingAnalysisService timingAnalysisService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").build();
    }

    private Activity atHour(int hour, int daysAgo) {
        return Activity.builder().user(user).type(ActivityType.RUNNING)
                .startTime(LocalDateTime.now().minusDays(daysAgo).withHour(hour).withMinute(0))
                .build();
    }

    private void given(List<Activity> activities) {
        when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                .thenReturn(activities);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> distributionOf(Map<String, Object> result) {
        return (List<Integer>) result.get("hourlyDistribution");
    }

    @Test
    @DisplayName("returns all 24 hour buckets, not just the winning one")
    void returnsFullDistribution() {
        given(List.of(atHour(7, 1), atHour(7, 2), atHour(18, 3)));

        Map<String, Object> result = timingAnalysisService.analyseTimingForUser("user-1");
        List<Integer> hours = distributionOf(result);

        assertThat(hours).hasSize(24);
        assertThat(hours.get(7)).isEqualTo(2);
        assertThat(hours.get(18)).isEqualTo(1);
        assertThat(hours.get(3)).isEqualTo(0);
        assertThat(hours.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
    }

    @Test
    @DisplayName("the distribution's tallest bucket is the hour it reports as peak")
    void peakMatchesDistribution() {
        List<Activity> activities = new ArrayList<>();
        for (int i = 0; i < 5; i++) activities.add(atHour(6, i + 1));
        activities.add(atHour(21, 6));
        given(activities);

        Map<String, Object> result = timingAnalysisService.analyseTimingForUser("user-1");

        assertThat(result.get("peakWorkoutHour")).isEqualTo(6);
        assertThat(result.get("peakTimeWindow")).isEqualTo("06:00–07:00");
        assertThat(distributionOf(result).get(6)).isEqualTo(5);
    }

    @Test
    @DisplayName("says so plainly when there is nothing to analyse")
    void emptyHistoryReturnsMessage() {
        given(List.of());

        Map<String, Object> result = timingAnalysisService.analyseTimingForUser("user-1");

        assertThat(result).containsKey("message");
        assertThat(result).doesNotContainKey("hourlyDistribution");
    }
}

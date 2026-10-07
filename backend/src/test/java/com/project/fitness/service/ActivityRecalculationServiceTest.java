package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.MuscleGroup;
import com.project.fitness.model.MuscleRecovery;
import com.project.fitness.model.PersonalBest;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.MuscleRecoveryRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Derived state is kept incrementally on the create path, which only ever moves it
 * forward. These tests cover the other direction — what has to happen once an activity
 * can be edited or deleted (PLAN P0-4).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityRecalculation Tests")
class ActivityRecalculationServiceTest {

    @Mock private ActivityRepository activityRepository;
    @Mock private PersonalBestRepository personalBestRepository;
    @Mock private MuscleRecoveryRepository muscleRecoveryRepository;
    @Mock private UserRepository userRepository;
    @Mock private GoalService goalService;
    @Mock private ExercisePersonalBestService exercisePersonalBestService;
    // Not a mock: the real mapping is the thing under test when recovery is rebuilt.
    private final MuscleRecoveryService muscleRecoveryService = new MuscleRecoveryService(null);

    private ActivityRecalculationService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").currentStreak(9).longestStreak(9).build();
        service = new ActivityRecalculationService(
                activityRepository, personalBestRepository, muscleRecoveryRepository,
                userRepository, muscleRecoveryService, goalService,
                exercisePersonalBestService);
    }

    private Activity on(LocalDate day, ActivityType type, Integer duration, Integer calories) {
        return Activity.builder()
                .user(user).type(type).duration(duration).caloriesBurned(calories)
                .startTime(day.atTime(9, 0))
                .build();
    }

    // ── Streak ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Deleting the only activity resets the streak to zero")
    void streak_resetsWhenNoActivitiesRemain() {
        service.recalculateStreak(user, List.of());

        assertThat(user.getCurrentStreak()).isZero();
        assertThat(user.getLongestStreak()).isZero();
        assertThat(user.getLastActivityDate()).isNull();
    }

    @Test
    @DisplayName("Three consecutive days ending today is a current streak of 3")
    void streak_countsConsecutiveDays() {
        LocalDate today = LocalDate.now();
        service.recalculateStreak(user, List.of(
                on(today.minusDays(2), ActivityType.RUNNING, 30, 200),
                on(today.minusDays(1), ActivityType.RUNNING, 30, 200),
                on(today,               ActivityType.RUNNING, 30, 200)));

        assertThat(user.getCurrentStreak()).isEqualTo(3);
        assertThat(user.getLongestStreak()).isEqualTo(3);
        assertThat(user.getLastActivityDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("A gap breaks the current streak but the longest one is remembered")
    void streak_gapBreaksCurrentButKeepsLongest() {
        LocalDate today = LocalDate.now();
        service.recalculateStreak(user, List.of(
                on(today.minusDays(20), ActivityType.RUNNING, 30, 200),
                on(today.minusDays(19), ActivityType.RUNNING, 30, 200),
                on(today.minusDays(18), ActivityType.RUNNING, 30, 200),
                on(today.minusDays(17), ActivityType.RUNNING, 30, 200),
                on(today,               ActivityType.RUNNING, 30, 200)));

        assertThat(user.getCurrentStreak()).isEqualTo(1);
        assertThat(user.getLongestStreak()).isEqualTo(4);
    }

    @Test
    @DisplayName("A streak ending yesterday is still current — the day isn't over")
    void streak_yesterdayStillCounts() {
        LocalDate today = LocalDate.now();
        service.recalculateStreak(user, List.of(
                on(today.minusDays(2), ActivityType.RUNNING, 30, 200),
                on(today.minusDays(1), ActivityType.RUNNING, 30, 200)));

        assertThat(user.getCurrentStreak()).isEqualTo(2);
    }

    @Test
    @DisplayName("Two activities on the same day count as one day, not two")
    void streak_doesNotDoubleCountSameDay() {
        LocalDate today = LocalDate.now();
        service.recalculateStreak(user, List.of(
                on(today, ActivityType.RUNNING, 30, 200),
                on(today, ActivityType.YOGA,    45, 120)));

        assertThat(user.getCurrentStreak()).isEqualTo(1);
    }

    // ── Personal bests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Deleting the record-setting activity lowers the personal best")
    void personalBest_loweredWhenRecordActivityRemoved() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.RUNNING)
                .bestDuration(90).bestCalories(800).build();

        when(personalBestRepository.findByUserId("user-1")).thenReturn(List.of(existing));
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.RUNNING))
                .thenReturn(Optional.of(existing));
        when(personalBestRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // The 90-minute session is gone; only shorter ones survive.
        service.recalculatePersonalBests(user, List.of(
                on(LocalDate.now().minusDays(3), ActivityType.RUNNING, 30, 250),
                on(LocalDate.now().minusDays(1), ActivityType.RUNNING, 45, 400)));

        assertThat(existing.getBestDuration()).isEqualTo(45);
        assertThat(existing.getBestCalories()).isEqualTo(400);
    }

    @Test
    @DisplayName("A type with no activities left loses its record row entirely")
    void personalBest_deletedWhenTypeHasNoActivities() {
        PersonalBest orphan = PersonalBest.builder()
                .user(user).activityType(ActivityType.SWIMMING)
                .bestDuration(60).bestCalories(500).build();

        when(personalBestRepository.findByUserId("user-1")).thenReturn(List.of(orphan));

        service.recalculatePersonalBests(user, List.of());

        verify(personalBestRepository).delete(orphan);
    }

    // ── Muscle recovery ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Recovery points at the most recent surviving activity that works the group")
    void recovery_rewoundToLatestSurvivingActivity() {
        MuscleRecovery quads = MuscleRecovery.builder()
                .user(user).muscleGroup(MuscleGroup.QUADRICEPS)
                .lastWorkedAt(LocalDateTime.now()).build();

        when(muscleRecoveryRepository.findByUserId("user-1")).thenReturn(List.of(quads));
        when(muscleRecoveryRepository.findByUserIdAndMuscleGroup(eq("user-1"), any()))
                .thenReturn(Optional.of(quads));

        LocalDate threeDaysAgo = LocalDate.now().minusDays(3);
        service.recalculateMuscleRecovery(user, List.of(
                on(threeDaysAgo, ActivityType.RUNNING, 30, 200)));

        assertThat(quads.getLastWorkedAt()).isEqualTo(threeDaysAgo.atTime(9, 0));
    }

    @Test
    @DisplayName("A group nothing works any more is cleared, not left pointing at a deleted workout")
    void recovery_clearedWhenNothingWorksTheGroup() {
        MuscleRecovery quads = MuscleRecovery.builder()
                .user(user).muscleGroup(MuscleGroup.QUADRICEPS)
                .lastWorkedAt(LocalDateTime.now()).build();

        when(muscleRecoveryRepository.findByUserId("user-1")).thenReturn(List.of(quads));

        service.recalculateMuscleRecovery(user, List.of());

        assertThat(quads.getLastWorkedAt()).isNull();
    }
}

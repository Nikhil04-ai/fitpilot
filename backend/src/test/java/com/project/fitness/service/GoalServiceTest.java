package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.*;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.BodyMetricsLogRepository;
import com.project.fitness.repository.GoalRepository;
import com.project.fitness.repository.MealLogRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GoalService — Feature 12
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoalService Tests")
class GoalServiceTest {

    @Mock private GoalRepository goalRepository;
    @Mock private UserRepository userRepository;
    // Nutrition and body-weight goals read their value from these rather than
    // accumulating it (PLAN P2-1). The activity-goal tests below never reach them.
    @Mock private MealLogRepository mealLogRepository;
    @Mock private BodyMetricsLogRepository bodyMetricsLogRepository;
    @Mock private ActivityRepository activityRepository;
    @InjectMocks private GoalService goalService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").firstName("Nikhil").build();
    }

    /** A goal whose progress belongs to the current week (i.e. no roll-over due). */
    private Goal buildGoal(GoalType type, double target) {
        return Goal.builder()
                .id("goal-1")
                .user(user)
                .type(type)
                .targetValue(target)
                .currentValue(0.0)
                .isCompleted(false)
                .periodStart(mondayOfThisWeek())
                .build();
    }

    private static LocalDate mondayOfThisWeek() {
        return LocalDate.now().with(java.time.temporal.TemporalAdjusters
                .previousOrSame(java.time.DayOfWeek.MONDAY));
    }

    // ── CALORIES_PER_WEEK goal progress ───────────────────────────────────────

    @Test
    @DisplayName("Logging activity adds calories to CALORIES_PER_WEEK goal")
    void calorieGoal_progressUpdated() {
        Goal goal = buildGoal(GoalType.CALORIES_PER_WEEK, 2000.0);
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = new Activity();
        activity.setCaloriesBurned(500);
        activity.setDuration(30);

        List<String> achievements = goalService.updateGoalProgress(user, activity);

        assertThat(goal.getCurrentValue()).isEqualTo(500.0);
        assertThat(goal.getIsCompleted()).isFalse();
        assertThat(achievements).isEmpty();
    }

    // ── Goal completed when target is reached ─────────────────────────────────

    @Test
    @DisplayName("Activity that crosses goal target: goal marked completed, achievement returned")
    void goalCompleted_whenTargetReached() {
        Goal goal = buildGoal(GoalType.CALORIES_PER_WEEK, 1000.0);
        goal.setCurrentValue(900.0);
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = new Activity();
        activity.setCaloriesBurned(200);
        activity.setDuration(30);

        List<String> achievements = goalService.updateGoalProgress(user, activity);

        assertThat(goal.getIsCompleted()).isTrue();
        assertThat(goal.getCurrentValue()).isEqualTo(1100.0);
        assertThat(achievements).hasSize(1);
        assertThat(achievements.get(0)).contains("Goal reached");
    }

    // ── WORKOUTS_PER_WEEK counts each activity as 1 ───────────────────────────

    @Test
    @DisplayName("WORKOUTS_PER_WEEK: each activity counts as 1 regardless of calories")
    void workoutsPerWeek_eachActivityCountsAsOne() {
        Goal goal = buildGoal(GoalType.WORKOUTS_PER_WEEK, 5.0);
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = new Activity();
        activity.setCaloriesBurned(null); // even with no calories
        activity.setDuration(20);

        goalService.updateGoalProgress(user, activity);

        assertThat(goal.getCurrentValue()).isEqualTo(1.0);
    }

    // ── DURATION_PER_WEEK adds minutes ────────────────────────────────────────

    @Test
    @DisplayName("DURATION_PER_WEEK: duration in minutes is added to progress")
    void durationGoal_minutesAdded() {
        Goal goal = buildGoal(GoalType.DURATION_PER_WEEK, 300.0);
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = new Activity();
        activity.setCaloriesBurned(400);
        activity.setDuration(45);

        goalService.updateGoalProgress(user, activity);

        assertThat(goal.getCurrentValue()).isEqualTo(45.0);
    }

    // ── No active goals: no error ─────────────────────────────────────────────

    @Test
    @DisplayName("No active goals: returns empty list, no error")
    void noActiveGoals_returnsEmpty() {
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of());

        Activity activity = new Activity();
        activity.setCaloriesBurned(300);

        List<String> result = goalService.updateGoalProgress(user, activity);
        assertThat(result).isEmpty();
    }

    // ── Create goal throws when the caller's own account is gone ──────────────

    /**
     * The id reaching createGoal() always comes from the JWT, so "no such user"
     * means the session outlived the account — not a bad request. It has to be
     * AccountNotFoundException specifically: that's what GlobalExceptionHandler
     * turns into the 409 the frontend matches to clear the dead session. A plain
     * RuntimeException here becomes a 400 and the frontend can't recover.
     */
    @Test
    @DisplayName("Create goal when the caller's account no longer exists: throws AccountNotFoundException")
    void createGoal_unknownUser_throws() {
        when(userRepository.findById("bad-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                goalService.createGoal("bad-id", GoalType.CALORIES_PER_WEEK, 1000.0, LocalDate.now()))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("account that no longer exists");
    }

    // ── Multiple concurrent goals all updated ─────────────────────────────────

    @Test
    @DisplayName("Multiple active goals: all updated in one activity")
    void multipleGoals_allUpdated() {
        Goal calGoal  = buildGoal(GoalType.CALORIES_PER_WEEK, 3000.0);
        Goal wrkGoal  = buildGoal(GoalType.WORKOUTS_PER_WEEK, 5.0);
        calGoal.setId("goal-cal");
        wrkGoal.setId("goal-wrk");

        when(goalRepository.findByUserId("user-1"))
                .thenReturn(List.of(calGoal, wrkGoal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = new Activity();
        activity.setCaloriesBurned(400);
        activity.setDuration(30);

        goalService.updateGoalProgress(user, activity);

        assertThat(calGoal.getCurrentValue()).isEqualTo(400.0);
        assertThat(wrkGoal.getCurrentValue()).isEqualTo(1.0);
        verify(goalRepository, times(2)).save(any());
    }

    // -- Weekly roll-over ------------------------------------------------------

    @Test
    @DisplayName("A goal from a previous week resets to zero before new progress is added")
    void goalFromLastWeek_resetsBeforeCounting() {
        Goal goal = buildGoal(GoalType.WORKOUTS_PER_WEEK, 5.0);
        goal.setPeriodStart(mondayOfThisWeek().minusWeeks(1));
        goal.setCurrentValue(5.0);
        goal.setIsCompleted(true);

        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = new Activity();
        activity.setDuration(30);

        goalService.updateGoalProgress(user, activity);

        assertThat(goal.getCurrentValue()).isEqualTo(1.0);       // not 6.0
        assertThat(goal.getIsCompleted()).isFalse();
        assertThat(goal.getPeriodStart()).isEqualTo(mondayOfThisWeek());
    }

    @Test
    @DisplayName("A completed goal is still visited, so it can start the new week fresh")
    void completedGoalFromLastWeek_isNotSkipped() {
        // Progress used to be read with findByUserIdAndIsCompletedFalse, so a goal
        // completed last week was filtered out and never reset - it stayed at 5/5
        // permanently.
        Goal completed = buildGoal(GoalType.WORKOUTS_PER_WEEK, 5.0);
        completed.setPeriodStart(mondayOfThisWeek().minusWeeks(2));
        completed.setCurrentValue(5.0);
        completed.setIsCompleted(true);

        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(completed));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = new Activity();
        activity.setDuration(30);
        goalService.updateGoalProgress(user, activity);

        assertThat(completed.getIsCompleted()).isFalse();
        assertThat(completed.getCurrentValue()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Reading goals rolls a stale week over without waiting for an activity")
    void readingGoals_rollsOverStaleWeek() {
        Goal goal = buildGoal(GoalType.CALORIES_PER_WEEK, 2000.0);
        goal.setPeriodStart(mondayOfThisWeek().minusWeeks(1));
        goal.setCurrentValue(1800.0);

        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = goalService.getGoalsWithProgress("user-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("currentValue")).isEqualTo(0.0);
        assertThat(result.get(0).get("progressPercent")).isEqualTo(0.0);
        verify(goalRepository).save(goal);
    }

    @Test
    @DisplayName("A goal already counting this week is left alone on read")
    void readingGoals_leavesCurrentWeekAlone() {
        Goal goal = buildGoal(GoalType.CALORIES_PER_WEEK, 2000.0);
        goal.setCurrentValue(1800.0);

        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(goal));

        var result = goalService.getGoalsWithProgress("user-1");

        assertThat(result.get(0).get("currentValue")).isEqualTo(1800.0);
        verify(goalRepository, never()).save(any());
    }

    // ── Deadlines (PLAN P0-7) ────────────────────────────────────────────────
    // The deadline was stored, updatable and returned, but never compared against
    // today, so a goal missed months ago still reported as active and in progress.

    @Test
    @DisplayName("A goal past its deadline stops accumulating progress")
    void expiredGoal_doesNotAccumulate() {
        Goal goal = buildGoal(GoalType.CALORIES_PER_WEEK, 2000.0);
        goal.setDeadline(LocalDate.now().minusDays(1));
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(goal));

        Activity activity = new Activity();
        activity.setCaloriesBurned(500);

        goalService.updateGoalProgress(user, activity);

        assertThat(goal.getCurrentValue()).isZero();
    }

    @Test
    @DisplayName("A goal inside its deadline still accumulates")
    void goalWithFutureDeadline_stillAccumulates() {
        Goal goal = buildGoal(GoalType.CALORIES_PER_WEEK, 2000.0);
        goal.setDeadline(LocalDate.now().plusDays(7));
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = new Activity();
        activity.setCaloriesBurned(500);

        goalService.updateGoalProgress(user, activity);

        assertThat(goal.getCurrentValue()).isEqualTo(500.0);
    }

    @Test
    @DisplayName("A goal reached before its deadline is not retroactively expired")
    void completedGoal_notExpiredByLaterDeadline() {
        Goal goal = buildGoal(GoalType.WORKOUTS_PER_WEEK, 3.0);
        goal.setDeadline(LocalDate.now().minusDays(1));
        goal.setIsCompleted(true);

        assertThat(goalService.isExpired(goal)).isFalse();
    }

    @Test
    @DisplayName("A goal with no deadline never expires")
    void goalWithoutDeadline_neverExpires() {
        Goal goal = buildGoal(GoalType.WORKOUTS_PER_WEEK, 3.0);

        assertThat(goalService.isExpired(goal)).isFalse();
    }

    @Test
    @DisplayName("Progress is rebuilt from this week's activities after an edit or delete")
    void recalculateProgress_rebuildsFromActivities() {
        Goal goal = buildGoal(GoalType.CALORIES_PER_WEEK, 2000.0);
        goal.setCurrentValue(1500.0);   // stale running total, including a since-deleted workout
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(goal));
        when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity survivor = new Activity();
        survivor.setCaloriesBurned(400);
        survivor.setStartTime(LocalDate.now().atTime(9, 0));

        goalService.recalculateProgress(user, List.of(survivor));

        assertThat(goal.getCurrentValue()).isEqualTo(400.0);
        assertThat(goal.getIsCompleted()).isFalse();
    }
}

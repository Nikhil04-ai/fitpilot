package com.project.fitness.service;

import com.project.fitness.model.BodyMetricsLog;
import com.project.fitness.model.Goal;
import com.project.fitness.model.GoalType;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.BodyMetricsLogRepository;
import com.project.fitness.repository.GoalRepository;
import com.project.fitness.repository.MealLogRepository;
import com.project.fitness.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The goal types nutrition made possible — PLAN P2-1.
 *
 * <p>Separate from {@link GoalServiceTest}, which covers the three activity counters
 * and their weekly roll-over. These three are scored differently enough to be worth
 * keeping apart: two are recomputed from the meal log rather than accumulated, and one
 * is read off the scale and never resets at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoalService — nutrition and body-weight goals")
class GoalServiceNutritionTest {

    @Mock private GoalRepository goalRepository;
    @Mock private UserRepository userRepository;
    @Mock private MealLogRepository mealLogRepository;
    @Mock private BodyMetricsLogRepository bodyMetricsLogRepository;
    @Mock private ActivityRepository activityRepository;
    @InjectMocks private GoalService goalService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").weight(80.0).build();
        lenient().when(goalRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Goal goal(GoalType type, double target) {
        return Goal.builder()
                .id("goal-1").user(user).type(type)
                .targetValue(target).currentValue(0.0).isCompleted(false)
                .periodStart(type.period() == com.project.fitness.model.GoalPeriod.NONE
                        ? null : LocalDate.now())
                .build();
    }

    // ── Daily calorie budget (AT_MOST) ───────────────────────────────────────

    @Nested
    @DisplayName("A daily calorie budget")
    class CalorieBudget {

        @Test
        @DisplayName("is recomputed from the meal log, not incremented")
        void recomputedFromLog() {
            Goal budget = goal(GoalType.CALORIE_INTAKE_PER_DAY, 2000.0);
            budget.setCurrentValue(1800.0);   // stale, includes a since-deleted meal
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(budget));
            when(mealLogRepository.sumCaloriesByUserIdAndRange(eq("user-1"), any(), any()))
                    .thenReturn(1200);

            goalService.refreshNutritionGoals(user);

            // Meals get edited and deleted far more often than workouts do, and a
            // running total cannot be corrected downwards. Recomputing is the only way
            // deleting a meal actually gives the budget back.
            assertThat(budget.getCurrentValue()).isEqualTo(1200.0);
        }

        @Test
        @DisplayName("is never reported as completed, however little was eaten")
        void neverCompleted() {
            Goal budget = goal(GoalType.CALORIE_INTAKE_PER_DAY, 2000.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(budget));
            when(mealLogRepository.sumCaloriesByUserIdAndRange(eq("user-1"), any(), any()))
                    .thenReturn(300);

            goalService.refreshNutritionGoals(user);

            // A budget is not an achievement. Reporting "completed" at 300/2000 would
            // put a tick next to having barely eaten, first thing in the morning.
            assertThat(budget.getIsCompleted()).isFalse();
        }

        @Test
        @DisplayName("warns once when it is first exceeded")
        void warnsOnceWhenExceeded() {
            Goal budget = goal(GoalType.CALORIE_INTAKE_PER_DAY, 2000.0);
            budget.setCurrentValue(1900.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(budget));
            when(mealLogRepository.sumCaloriesByUserIdAndRange(eq("user-1"), any(), any()))
                    .thenReturn(2300);

            List<String> messages = goalService.refreshNutritionGoals(user);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)).contains("budget").contains("300");
            // Whole numbers read as whole numbers — "300 kcal", not "300.0 kcal".
            assertThat(messages.get(0)).doesNotContain("300.0");
        }

        @Test
        @DisplayName("does not warn again on the next meal once already over")
        void doesNotRepeatTheWarning() {
            Goal budget = goal(GoalType.CALORIE_INTAKE_PER_DAY, 2000.0);
            budget.setCurrentValue(2300.0);   // already over before this meal
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(budget));
            when(mealLogRepository.sumCaloriesByUserIdAndRange(eq("user-1"), any(), any()))
                    .thenReturn(2500);

            assertThat(goalService.refreshNutritionGoals(user)).isEmpty();
        }

        @Test
        @DisplayName("reports what is left, and the overshoot separately")
        void reportsRemainingAndOvershoot() {
            Goal budget = goal(GoalType.CALORIE_INTAKE_PER_DAY, 2000.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(budget));
            when(mealLogRepository.sumCaloriesByUserIdAndRange(eq("user-1"), any(), any()))
                    .thenReturn(2250);

            Map<String, Object> view = goalService.getGoalsWithProgress("user-1").get(0);

            // A negative "remaining" would read as a smaller budget rather than an
            // overshoot, so the two are separate numbers.
            assertThat(view.get("remaining")).isEqualTo(0.0);
            assertThat(view.get("exceededBy")).isEqualTo(250.0);
            assertThat(view.get("isExceeded")).isEqualTo(true);
            assertThat(view.get("direction")).isEqualTo("AT_MOST");
        }

        @Test
        @DisplayName("resets daily, not weekly")
        void resetsDaily() {
            Goal budget = goal(GoalType.CALORIE_INTAKE_PER_DAY, 2000.0);
            budget.setPeriodStart(LocalDate.now().minusDays(1));
            budget.setCurrentValue(2400.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(budget));
            when(mealLogRepository.sumCaloriesByUserIdAndRange(eq("user-1"), any(), any()))
                    .thenReturn(150);

            goalService.refreshNutritionGoals(user);

            assertThat(budget.getPeriodStart()).isEqualTo(LocalDate.now());
            assertThat(budget.getCurrentValue()).isEqualTo(150.0);
        }
    }

    // ── Daily protein floor (AT_LEAST) ───────────────────────────────────────

    @Nested
    @DisplayName("A daily protein target")
    class ProteinTarget {

        @Test
        @DisplayName("completes and announces once the floor is reached")
        void completesWhenReached() {
            Goal protein = goal(GoalType.PROTEIN_PER_DAY, 140.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(protein));
            when(mealLogRepository.sumProteinByUserIdAndRange(eq("user-1"), any(), any()))
                    .thenReturn(146.0);

            List<String> messages = goalService.refreshNutritionGoals(user);

            assertThat(protein.getIsCompleted()).isTrue();
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)).contains("Goal reached").contains("g");
        }

        @Test
        @DisplayName("stays open while short of the target")
        void notCompletedWhenShort() {
            Goal protein = goal(GoalType.PROTEIN_PER_DAY, 140.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(protein));
            when(mealLogRepository.sumProteinByUserIdAndRange(eq("user-1"), any(), any()))
                    .thenReturn(90.0);

            assertThat(goalService.refreshNutritionGoals(user)).isEmpty();
            assertThat(protein.getIsCompleted()).isFalse();
        }
    }

    // ── Body weight (TOWARDS, never resets) ──────────────────────────────────

    @Nested
    @DisplayName("A body-weight target")
    class BodyWeight {

        private Goal weightGoal(double start, double target) {
            Goal g = goal(GoalType.TARGET_WEIGHT, target);
            g.setStartValue(start);
            return g;
        }

        private void weighIn(double kg) {
            when(bodyMetricsLogRepository.findTopByUserIdOrderByLoggedAtDesc("user-1"))
                    .thenReturn(Optional.of(BodyMetricsLog.builder().weight(kg).build()));
        }

        @Test
        @DisplayName("is read off the scale, not from what was eaten")
        void readFromTheScale() {
            Goal g = weightGoal(80.0, 72.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(g));
            weighIn(76.5);

            goalService.refreshStateGoals(user);

            assertThat(g.getCurrentValue()).isEqualTo(76.5);
            // What you ate explains the trend; it does not stand in for it.
            verifyNoInteractions(mealLogRepository);
        }

        @Test
        @DisplayName("measures progress from the starting weight, not from zero")
        void progressMeasuredFromStart() {
            Goal g = weightGoal(80.0, 72.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(g));
            weighIn(76.0);

            Map<String, Object> view = goalService.getGoalsWithProgress("user-1").get(0);

            // 76 of the way from 80 to 72 is exactly half. As a plain current/target
            // ratio it would be 105%, which is meaningless.
            assertThat((Double) view.get("progressPercent")).isEqualTo(50.0);
        }

        @Test
        @DisplayName("counts a gain as progress when the target is above the start")
        void bulkingCountsUpwards() {
            Goal g = weightGoal(60.0, 70.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(g));
            weighIn(65.0);

            Map<String, Object> view = goalService.getGoalsWithProgress("user-1").get(0);

            // The direction is not knowable from the target alone, which is the whole
            // reason startValue exists.
            assertThat((Double) view.get("progressPercent")).isEqualTo(50.0);
        }

        @Test
        @DisplayName("moving the wrong way reads as 0%, not as a negative bar")
        void wrongDirectionClampsToZero() {
            Goal g = weightGoal(80.0, 72.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(g));
            weighIn(82.0);

            Map<String, Object> view = goalService.getGoalsWithProgress("user-1").get(0);

            assertThat((Double) view.get("progressPercent")).isEqualTo(0.0);
            assertThat(view.get("isCompleted")).isEqualTo(false);
        }

        @Test
        @DisplayName("completes when the target is reached from above")
        void completesWhenCutting() {
            Goal g = weightGoal(80.0, 72.0);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(g));
            weighIn(71.8);

            List<String> achievements = goalService.refreshStateGoals(user);

            assertThat(g.getIsCompleted()).isTrue();
            assertThat(achievements).hasSize(1);
            assertThat(achievements.get(0)).contains("Body weight target of 72");
        }

        @Test
        @DisplayName("never rolls over — it is not a weekly count")
        void neverRollsOver() {
            Goal g = weightGoal(80.0, 72.0);
            g.setCurrentValue(76.0);
            g.setPeriodStart(null);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(g));
            weighIn(76.0);

            goalService.getGoalsWithProgress("user-1");

            // Resetting a body-weight goal every Monday would be nonsense: there is
            // nothing accumulating to reset.
            assertThat(g.getCurrentValue()).isEqualTo(76.0);
            assertThat(g.getPeriodStart()).isNull();
        }

        @Test
        @DisplayName("falls back to the profile weight when nothing has been logged yet")
        void fallsBackToProfileWeight() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(bodyMetricsLogRepository.findTopByUserIdOrderByLoggedAtDesc("user-1"))
                    .thenReturn(Optional.empty());

            Goal created = goalService.createGoal("user-1", GoalType.TARGET_WEIGHT, 72.0, null);

            // A goal created today should read "80.0 / 72.0", not "0 / 72".
            assertThat(created.getStartValue()).isEqualTo(80.0);
            assertThat(created.getCurrentValue()).isEqualTo(80.0);
        }

        @Test
        @DisplayName("adopts the first weigh-in as its start when it was created without one")
        void adoptsFirstWeighIn() {
            Goal g = goal(GoalType.TARGET_WEIGHT, 72.0);
            g.setStartValue(null);
            when(goalRepository.findByUserId("user-1")).thenReturn(List.of(g));
            weighIn(84.0);

            goalService.refreshStateGoals(user);

            // The alternative is a progress bar that can never move.
            assertThat(g.getStartValue()).isEqualTo(84.0);
        }
    }

    // ── Cross-cutting ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a workout does not move a nutrition or body-weight goal")
    void activityDoesNotTouchNutritionGoals() {
        Goal budget = goal(GoalType.CALORIE_INTAKE_PER_DAY, 2000.0);
        Goal weight = goal(GoalType.TARGET_WEIGHT, 72.0);
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(budget, weight));

        com.project.fitness.model.Activity activity = new com.project.fitness.model.Activity();
        activity.setCaloriesBurned(500);
        activity.setDuration(45);

        List<String> achievements = goalService.updateGoalProgress(user, activity);

        // Feeding a workout's calorie burn into a calorie *budget* would have been the
        // obvious bug here: the numbers are the same unit and the opposite meaning.
        assertThat(budget.getCurrentValue()).isZero();
        assertThat(weight.getCurrentValue()).isZero();
        assertThat(achievements).isEmpty();
        verify(goalRepository, never()).save(any());
    }

    @Test
    @DisplayName("an activity edit does not rebuild a nutrition goal from activities")
    void recalculateSkipsNutritionGoals() {
        Goal budget = goal(GoalType.CALORIE_INTAKE_PER_DAY, 2000.0);
        budget.setCurrentValue(1500.0);
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(budget));

        goalService.recalculateProgress(user, List.of());

        // ActivityRecalculationService rebuilds from the activity rows. A budget
        // derived from meals would be zeroed by it if it were not skipped.
        assertThat(budget.getCurrentValue()).isEqualTo(1500.0);
        verify(goalRepository, never()).save(any());
    }

    @Test
    @DisplayName("every goal type reports its unit and period so the UI need not keep its own table")
    void exposesUnitAndPeriod() {
        Goal budget = goal(GoalType.CALORIE_INTAKE_PER_DAY, 2000.0);
        when(goalRepository.findByUserId("user-1")).thenReturn(List.of(budget));
        when(mealLogRepository.sumCaloriesByUserIdAndRange(eq("user-1"), any(), any()))
                .thenReturn(0);

        Map<String, Object> view = goalService.getGoalsWithProgress("user-1").get(0);

        assertThat(view.get("unit")).isEqualTo("kcal");
        assertThat(view.get("period")).isEqualTo("DAILY");
    }

    @Test
    @DisplayName("a target of zero or less is refused")
    void rejectsNonPositiveTarget() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        goalService.createGoal("user-1", GoalType.CALORIE_INTAKE_PER_DAY, 0, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("greater than zero");
    }
}

package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Activity;
import com.project.fitness.model.BodyMetricsLog;
import com.project.fitness.model.Goal;
import com.project.fitness.model.GoalDirection;
import com.project.fitness.model.GoalPeriod;
import com.project.fitness.model.GoalType;
import com.project.fitness.model.User;
import com.project.fitness.repository.BodyMetricsLogRepository;
import com.project.fitness.repository.GoalRepository;
import com.project.fitness.repository.MealLogRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Feature 12 — Goal Setting and Progress Tracking
 *
 * <p>Three kinds of goal live here now, and they are scored in three different ways
 * (PLAN P2-1):
 *
 * <ul>
 *   <li><b>Activity goals</b> accumulate. Each saved workout adds its contribution,
 *       and the total resets on Monday. The original behaviour.</li>
 *   <li><b>Nutrition goals</b> are recomputed from the meal log rather than incremented,
 *       because meals are edited and deleted far more often than workouts are and an
 *       incremented running total cannot be corrected downwards. Recomputing is also
 *       cheap here — it is one indexed SUM over one day.</li>
 *   <li><b>State goals</b> (body weight) hold a value read from somewhere else entirely.
 *       Nothing accumulates, nothing resets, and progress is measured from where the
 *       user started rather than from zero.</li>
 * </ul>
 */
@Transactional
@Service
@RequiredArgsConstructor
public class GoalService {

    private final GoalRepository goalRepository;
    private final UserRepository userRepository;
    private final MealLogRepository mealLogRepository;
    private final BodyMetricsLogRepository bodyMetricsLogRepository;

    public Goal createGoal(String userId, GoalType type, double targetValue, LocalDate deadline) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        if (type == null) {
            throw new RuntimeException("A goal needs a type.");
        }
        if (targetValue <= 0) {
            throw new RuntimeException("A goal target must be greater than zero.");
        }

        Goal goal = Goal.builder()
                .user(user)
                .type(type)
                .targetValue(targetValue)
                .deadline(deadline)
                // Null for a state goal: there is no period to start.
                .periodStart(type.period() == GoalPeriod.NONE ? null : periodStartFor(type))
                // Captured now, because "80kg heading for 72kg" is only half told by the
                // target. Without the starting point a weight goal has no direction and
                // no zero for its progress bar to measure from.
                .startValue(type == GoalType.TARGET_WEIGHT ? currentBodyWeight(user).orElse(null) : null)
                .build();

        Goal saved = goalRepository.save(goal);

        // A weight goal created today should already read "80.0 / 72.0", not "0 / 72".
        if (saved.getType().isStateGoal()) refreshStateGoal(saved, user);
        if (saved.getType().isNutritionGoal()) refreshNutritionGoal(saved, user);
        return goalRepository.save(saved);
    }

    /**
     * Called after every activity save to update progress on all active goals.
     * Returns a list of newly completed goal messages.
     */
    public List<String> updateGoalProgress(User user, Activity activity) {
        // All goals, not just incomplete ones: a goal completed last week has to be
        // seen here so it can be rolled over into the new week. Filtering on
        // isCompleted=false meant a completed weekly goal was never looked at again.
        List<Goal> goals = goalRepository.findByUserId(user.getId());
        List<String> achievements = new java.util.ArrayList<>();

        for (Goal goal : goals) {
            rollOverIfNewPeriod(goal);

            // A goal past its deadline is over. It used to keep accumulating and keep
            // reporting as active forever, because nothing ever compared the deadline
            // against today (PLAN P0-7).
            if (isExpired(goal)) continue;

            // Only the accumulating activity types respond to a workout. A calorie
            // budget is driven by meals and a weight target by the scale; feeding a
            // workout into either would be nonsense, and both are refreshed from their
            // own source instead.
            if (!isActivityGoal(goal.getType())) continue;

            goal.setCurrentValue(goal.getCurrentValue() + contributionOf(goal, activity));
            collectAchievement(goal, achievements);
            goalRepository.save(goal);
        }

        return achievements;
    }

    /**
     * Rebuilds the nutrition goals for the current day from the meal log. Called after
     * any meal is created, edited or deleted, and on read.
     *
     * <p>Returns messages for goals that just changed state — a protein target reached,
     * or a calorie budget gone over. The budget message is a warning, not a
     * congratulation: {@link GoalDirection#AT_MOST} goals are never "achieved".
     */
    public List<String> refreshNutritionGoals(User user) {
        List<String> messages = new java.util.ArrayList<>();

        for (Goal goal : goalRepository.findByUserId(user.getId())) {
            if (!goal.getType().isNutritionGoal()) continue;

            boolean wasCompleted = Boolean.TRUE.equals(goal.getIsCompleted());
            boolean wasExceeded = isExceeded(goal);

            rollOverIfNewPeriod(goal);
            if (isExpired(goal)) continue;

            refreshNutritionGoal(goal, user);

            if (goal.getType().direction() == GoalDirection.AT_MOST) {
                if (!wasExceeded && isExceeded(goal)) {
                    messages.add("Over your " + label(goal.getType()) + " budget — "
                            + format(goal.getCurrentValue() - goal.getTargetValue())
                            + " " + goal.getType().unit() + " above " + format(goal.getTargetValue()) + ".");
                }
            } else if (!wasCompleted && Boolean.TRUE.equals(goal.getIsCompleted())) {
                messages.add("Goal reached! " + label(goal.getType())
                        + " target of " + format(goal.getTargetValue()) + " " + goal.getType().unit() + " achieved.");
            }

            goalRepository.save(goal);
        }

        return messages;
    }

    /**
     * Re-reads body-weight goals off the scale. Called after a body-metrics update and
     * on read, so the Goals panel reflects the latest weigh-in without needing a workout
     * or a meal to have happened first.
     */
    public List<String> refreshStateGoals(User user) {
        List<String> achievements = new java.util.ArrayList<>();

        for (Goal goal : goalRepository.findByUserId(user.getId())) {
            if (!goal.getType().isStateGoal()) continue;
            if (isExpired(goal)) continue;

            boolean wasCompleted = Boolean.TRUE.equals(goal.getIsCompleted());
            refreshStateGoal(goal, user);
            if (!wasCompleted && Boolean.TRUE.equals(goal.getIsCompleted())) {
                achievements.add("Goal reached! Body weight target of "
                        + format(goal.getTargetValue()) + " kg achieved.");
            }
            goalRepository.save(goal);
        }

        return achievements;
    }

    public List<Map<String, Object>> getGoalsWithProgress(String userId) {
        List<Goal> goals = goalRepository.findByUserId(userId);
        // Rolled over on read too, so the Goals panel shows 0/5 on Monday morning
        // rather than last week's finished numbers until the next workout is logged.
        // Nutrition and weight goals are refreshed from their own sources for the same
        // reason: neither is driven by an activity save, so waiting for one would leave
        // the panel showing yesterday.
        //
        // The owner comes off the goal rather than from a second lookup — safe here
        // because the whole service is @Transactional, and it keeps this read to one
        // query for callers that already know the user exists.
        for (Goal goal : goals) {
            boolean changed = rollOverIfNewPeriod(goal);
            if (!isExpired(goal)) {
                User owner = goal.getUser();
                if (goal.getType().isNutritionGoal()) { refreshNutritionGoal(goal, owner); changed = true; }
                if (goal.getType().isStateGoal())     { refreshStateGoal(goal, owner);     changed = true; }
            }
            if (changed) goalRepository.save(goal);
        }

        return goals.stream()
                .map(this::toProgressMap)
                .collect(Collectors.toList());
    }

    // ── Scoring ──────────────────────────────────────────────────────────────

    /** What a single activity contributes towards a goal of this type. */
    private double contributionOf(Goal goal, Activity activity) {
        return switch (goal.getType()) {
            case CALORIES_PER_WEEK -> activity.getCaloriesBurned() != null ? activity.getCaloriesBurned() : 0;
            case WORKOUTS_PER_WEEK -> 1;
            case DURATION_PER_WEEK -> activity.getDuration() != null ? activity.getDuration() : 0;
            // Not reachable — updateGoalProgress filters to activity goals first. Listed
            // rather than defaulted so adding a type is a compile error here, which is
            // how the three nutrition types got noticed in the first place.
            case CALORIE_INTAKE_PER_DAY, PROTEIN_PER_DAY, TARGET_WEIGHT -> 0;
        };
    }

    private boolean isActivityGoal(GoalType type) {
        return !type.isNutritionGoal() && !type.isStateGoal();
    }

    /** Sets a nutrition goal's value to the period's total, straight from the meal log. */
    private void refreshNutritionGoal(Goal goal, User user) {
        LocalDate day = goal.getPeriodStart() != null ? goal.getPeriodStart() : LocalDate.now();
        java.time.LocalDateTime from = day.atStartOfDay();
        java.time.LocalDateTime to = day.atTime(LocalTime.MAX);

        double total = switch (goal.getType()) {
            case CALORIE_INTAKE_PER_DAY -> nullToZero(
                    mealLogRepository.sumCaloriesByUserIdAndRange(user.getId(), from, to));
            case PROTEIN_PER_DAY -> nullToZero(
                    mealLogRepository.sumProteinByUserIdAndRange(user.getId(), from, to));
            default -> goal.getCurrentValue() != null ? goal.getCurrentValue() : 0;
        };

        goal.setCurrentValue(total);
        goal.setIsCompleted(isSatisfied(goal));
    }

    /** Sets a body-weight goal's value to the most recent weigh-in. */
    private void refreshStateGoal(Goal goal, User user) {
        Optional<Double> weight = currentBodyWeight(user);
        if (weight.isEmpty()) return;

        // A goal created before the user had ever logged a weight has no starting point.
        // Adopt the first reading we see rather than leaving progress permanently
        // unmeasurable — the alternative is a bar that can never move.
        if (goal.getStartValue() == null) goal.setStartValue(weight.get());

        goal.setCurrentValue(weight.get());
        goal.setIsCompleted(isSatisfied(goal));
    }

    /**
     * Whether a goal currently counts as met.
     *
     * <p>AT_MOST goals are deliberately never satisfied: a budget is not an achievement,
     * and reporting "completed" the moment the day starts at 0 kcal would put a tick
     * next to having eaten nothing yet. They report {@code isExceeded} instead.
     */
    private boolean isSatisfied(Goal goal) {
        double current = goal.getCurrentValue() != null ? goal.getCurrentValue() : 0;
        double target = goal.getTargetValue() != null ? goal.getTargetValue() : 0;

        return switch (goal.getType().direction()) {
            case AT_LEAST -> current >= target;
            case AT_MOST -> false;
            case TOWARDS -> {
                Double start = goal.getStartValue();
                if (start == null) yield false;
                if (start.equals(target)) yield true;
                // Cutting: done at or below target. Bulking: done at or above it.
                yield start > target ? current <= target : current >= target;
            }
        };
    }

    /** True when an AT_MOST goal has gone past its ceiling. False for every other kind. */
    public boolean isExceeded(Goal goal) {
        if (goal.getType().direction() != GoalDirection.AT_MOST) return false;
        double current = goal.getCurrentValue() != null ? goal.getCurrentValue() : 0;
        double target = goal.getTargetValue() != null ? goal.getTargetValue() : 0;
        return current > target;
    }

    private void collectAchievement(Goal goal, List<String> achievements) {
        if (Boolean.TRUE.equals(goal.getIsCompleted())) return;
        if (!isSatisfied(goal)) return;
        goal.setIsCompleted(true);
        achievements.add("Goal reached! " + label(goal.getType())
                + " target of " + format(goal.getTargetValue()) + " achieved.");
    }

    /**
     * True once the deadline is behind us and the goal was never reached. A goal that
     * was completed stays completed — missing a later deadline doesn't retract it.
     */
    public boolean isExpired(Goal goal) {
        return goal.getDeadline() != null
                && goal.getDeadline().isBefore(LocalDate.now())
                && !Boolean.TRUE.equals(goal.getIsCompleted());
    }

    /**
     * Rebuilds a user's current-period progress from the activities themselves. Used
     * after an activity is edited or deleted, where the running total kept by
     * {@link #updateGoalProgress} can no longer be corrected by adding to it.
     *
     * <p>Only touches activity goals. Nutrition and weight goals are derived from
     * sources an activity edit cannot have changed.
     */
    public void recalculateProgress(User user, List<Activity> activities) {
        for (Goal goal : goalRepository.findByUserId(user.getId())) {
            if (!isActivityGoal(goal.getType())) continue;

            rollOverIfNewPeriod(goal);
            if (isExpired(goal)) continue;

            LocalDate periodStart = goal.getPeriodStart() != null
                    ? goal.getPeriodStart() : periodStartFor(goal.getType());
            LocalDate periodEnd = periodEndExclusive(goal.getType(), periodStart);

            double total = 0;
            for (Activity a : activities) {
                if (a.getStartTime() == null) continue;
                LocalDate day = a.getStartTime().toLocalDate();
                if (day.isBefore(periodStart) || !day.isBefore(periodEnd)) continue;
                total += contributionOf(goal, a);
            }

            goal.setCurrentValue(total);
            goal.setIsCompleted(isSatisfied(goal));
            goalRepository.save(goal);
        }
    }

    // ── Periods ──────────────────────────────────────────────────────────────

    /**
     * Resets a goal's progress when its period has ended. Returns true if anything
     * changed. An expired goal is left frozen at its final numbers rather than being
     * reset to a fresh-looking 0 every period forever.
     *
     * <p>State goals never roll over — there is no period and nothing to reset.
     */
    private boolean rollOverIfNewPeriod(Goal goal) {
        if (goal.getType().period() == GoalPeriod.NONE) return false;

        LocalDate thisPeriod = periodStartFor(goal.getType());
        if (goal.getPeriodStart() != null && !goal.getPeriodStart().isBefore(thisPeriod)) {
            return false;
        }
        if (isExpired(goal)) {
            return false;
        }
        goal.setPeriodStart(thisPeriod);
        goal.setCurrentValue(0.0);
        goal.setIsCompleted(false);
        return true;
    }

    /** Monday for weekly goals, today for daily ones. */
    private LocalDate periodStartFor(GoalType type) {
        return switch (type.period()) {
            case WEEKLY -> LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case DAILY -> LocalDate.now();
            case NONE -> LocalDate.now();
        };
    }

    private LocalDate periodEndExclusive(GoalType type, LocalDate periodStart) {
        return switch (type.period()) {
            case WEEKLY -> periodStart.plusDays(7);
            case DAILY -> periodStart.plusDays(1);
            case NONE -> LocalDate.MAX;
        };
    }

    private Optional<Double> currentBodyWeight(User user) {
        // The logged history first: it is the record of weigh-ins, and it is what the
        // weight chart draws. user.weight is a mirror of the latest one and is the
        // fallback for a profile that was filled in without ever logging metrics.
        return bodyMetricsLogRepository.findTopByUserIdOrderByLoggedAtDesc(user.getId())
                .map(BodyMetricsLog::getWeight)
                .filter(w -> w != null && w > 0)
                .or(() -> Optional.ofNullable(user.getWeight()).filter(w -> w > 0));
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public Goal updateGoal(String requestingUserId, String goalId,
                           Double targetValue, LocalDate deadline) {
        Goal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new RuntimeException("Goal not found: " + goalId));
        requireOwner(goal, requestingUserId);
        if (targetValue != null) {
            if (targetValue <= 0) throw new RuntimeException("A goal target must be greater than zero.");
            goal.setTargetValue(targetValue);
        }
        if (deadline != null) goal.setDeadline(deadline);

        // Moving the target can flip a goal from met to unmet (or the reverse), so the
        // stored verdict is re-derived rather than left at whatever it was.
        goal.setIsCompleted(isSatisfied(goal));
        return goalRepository.save(goal);
    }

    public void deleteGoal(String requestingUserId, String goalId) {
        Goal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new RuntimeException("Goal not found: " + goalId));
        // A goal id alone used to be enough to delete it — including someone else's.
        requireOwner(goal, requestingUserId);
        goalRepository.delete(goal);
    }

    /** Safe to touch the lazy user here — the whole service is @Transactional. */
    private void requireOwner(Goal goal, String requestingUserId) {
        if (!goal.getUser().getId().equals(requestingUserId)) {
            throw new AccessDeniedException("You don't have access to this goal.");
        }
    }

    // ── Presentation ─────────────────────────────────────────────────────────

    private Map<String, Object> toProgressMap(Goal goal) {
        GoalType type = goal.getType();

        Map<String, Object> map = new HashMap<>();
        map.put("id", goal.getId());
        map.put("type", type.name());
        map.put("period", type.period().name());
        map.put("direction", type.direction().name());
        map.put("unit", type.unit());
        map.put("targetValue", goal.getTargetValue());
        map.put("currentValue", goal.getCurrentValue());
        map.put("startValue", goal.getStartValue());
        map.put("deadline", goal.getDeadline());
        map.put("isCompleted", goal.getIsCompleted());
        map.put("isExpired", isExpired(goal));
        map.put("isExceeded", isExceeded(goal));
        map.put("daysRemaining", goal.getDeadline() == null ? null
                : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), goal.getDeadline()));
        map.put("weekStarting", goal.getPeriodStart());
        map.put("progressPercent", progressPercentOf(goal));

        // How much budget is left, for the goals where that is the number people want.
        // Negative would read as a smaller budget rather than an overshoot, so an
        // exceeded budget reports 0 left and the overshoot separately.
        if (type.direction() == GoalDirection.AT_MOST) {
            double remaining = nullToZero(goal.getTargetValue()) - nullToZero(goal.getCurrentValue());
            map.put("remaining", Math.max(0, remaining));
            map.put("exceededBy", remaining < 0 ? -remaining : 0.0);
        }

        return map;
    }

    /**
     * Progress as a percentage, measured the way the goal's direction implies.
     *
     * <p>A weight goal measures from where the user started, not from zero: 76kg on the
     * way from 80 to 72 is half done, whereas 76/72 as a plain ratio is 105% and
     * meaningless. Moving the wrong way reads as 0 rather than as a negative bar.
     */
    private double progressPercentOf(Goal goal) {
        double current = nullToZero(goal.getCurrentValue());
        double target = nullToZero(goal.getTargetValue());

        if (goal.getType().direction() == GoalDirection.TOWARDS) {
            Double start = goal.getStartValue();
            if (start == null) return 0;
            double span = Math.abs(target - start);
            if (span < 1e-9) return 100;
            double covered = (current - start) * Math.signum(target - start);
            return Math.max(0, Math.min(100.0, (covered / span) * 100));
        }

        if (target <= 0) return 0;
        return Math.min(100.0, (current / target) * 100);
    }

    private String label(GoalType type) {
        return type.name().replace("_", " ");
    }

    /** Whole numbers read as whole numbers — "2000 kcal", not "2000.0 kcal". */
    private String format(Double value) {
        if (value == null) return "0";
        return value == Math.floor(value) ? String.valueOf((long) (double) value) : String.valueOf(value);
    }

    private double nullToZero(Number value) {
        return value == null ? 0 : value.doubleValue();
    }
}

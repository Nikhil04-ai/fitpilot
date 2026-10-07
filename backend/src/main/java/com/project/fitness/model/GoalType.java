package com.project.fitness.model;

/**
 * What a goal measures, how often it resets, and which way it has to move.
 *
 * <p>The first three are the original activity-side weekly counters. The last three
 * arrived with nutrition (PLAN P2-1) and are the reason {@link GoalPeriod} and
 * {@link GoalDirection} exist: before them every goal was weekly and every goal was
 * "reach at least X", so neither a daily calorie budget nor a body-weight target could
 * be expressed at all — and body weight is what most people are actually here for.
 */
public enum GoalType {

    // ── Activity side ────────────────────────────────────────────────────────
    CALORIES_PER_WEEK(GoalPeriod.WEEKLY, GoalDirection.AT_LEAST, "kcal"),
    WORKOUTS_PER_WEEK(GoalPeriod.WEEKLY, GoalDirection.AT_LEAST, "workouts"),
    DURATION_PER_WEEK(GoalPeriod.WEEKLY, GoalDirection.AT_LEAST, "min"),

    // ── Intake side (PLAN P2-1) ──────────────────────────────────────────────

    /** A daily calorie ceiling. Satisfied by staying under it, so AT_MOST. */
    CALORIE_INTAKE_PER_DAY(GoalPeriod.DAILY, GoalDirection.AT_MOST, "kcal"),

    /** A daily protein floor — the one macro target most people actually set. */
    PROTEIN_PER_DAY(GoalPeriod.DAILY, GoalDirection.AT_LEAST, "g"),

    /**
     * A body-weight target.
     *
     * <p>The goal this whole feature exists to make possible. Scored from the scale
     * ({@code BodyMetricsLog}), not from the meal log: what you ate explains the trend,
     * it does not stand in for it.
     */
    TARGET_WEIGHT(GoalPeriod.NONE, GoalDirection.TOWARDS, "kg");

    private final GoalPeriod period;
    private final GoalDirection direction;
    private final String unit;

    GoalType(GoalPeriod period, GoalDirection direction, String unit) {
        this.period = period;
        this.direction = direction;
        this.unit = unit;
    }

    public GoalPeriod period() { return period; }

    public GoalDirection direction() { return direction; }

    /** For display only — the API sends it so the UI doesn't keep its own copy of this table. */
    public String unit() { return unit; }

    /** True for the goals fed by logging a meal rather than by logging a workout. */
    public boolean isNutritionGoal() {
        return this == CALORIE_INTAKE_PER_DAY || this == PROTEIN_PER_DAY;
    }

    /** True for goals whose value is read from elsewhere rather than accumulated. */
    public boolean isStateGoal() {
        return period == GoalPeriod.NONE;
    }
}

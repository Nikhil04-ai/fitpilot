package com.project.fitness.model;

/**
 * The window a goal's progress is counted over, and therefore when it resets.
 *
 * <p>Every goal used to be weekly — the type names still say so — and {@code GoalService}
 * had one hard-coded Monday roll-over. Nutrition broke that in two directions at once:
 * a calorie budget is something people think about per day, and a body-weight target is
 * not a period at all. It is one number you are moving towards, and resetting it every
 * Monday would be nonsense.
 */
public enum GoalPeriod {

    /** Resets at midnight. Progress is counted over the calendar day. */
    DAILY,

    /** Resets on Monday. The original behaviour, and still the default. */
    WEEKLY,

    /**
     * Never resets. The goal holds a standing value (today's body weight) rather than
     * an accumulating count, so there is nothing to roll over.
     */
    NONE
}

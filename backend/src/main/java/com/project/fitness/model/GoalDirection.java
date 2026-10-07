package com.project.fitness.model;

/**
 * Which way a goal has to move to be doing well.
 *
 * <p>Until nutrition, every goal was "reach at least X" and the comparison was hard-coded
 * as {@code currentValue >= targetValue}. A calorie budget is the opposite — the whole
 * point is to stay under it — and a body-weight target can be either, depending on
 * whether you are cutting or bulking, which is not knowable from the target alone.
 */
public enum GoalDirection {

    /** Reach the target or beat it. Workouts, minutes, calories burned, protein. */
    AT_LEAST,

    /**
     * Stay under the target. A budget, not an achievement — which is why an AT_MOST
     * goal never produces a congratulations message. Nobody wants to be told well done
     * for eating.
     */
    AT_MOST,

    /**
     * Move from where you started to the target, in whichever direction that is.
     *
     * <p>Used by body-weight goals. 80kg heading for 72kg and 60kg heading for 72kg are
     * both TOWARDS; the direction comes from the starting value recorded when the goal
     * was created, which is why {@code Goal.startValue} exists.
     */
    TOWARDS
}

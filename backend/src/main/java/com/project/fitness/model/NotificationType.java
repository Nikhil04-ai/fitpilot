package com.project.fitness.model;

/**
 * What a notification is about.
 *
 * <p>Drives the icon and, through {@code Notification.targetPanel}, where clicking it
 * lands. Kept as an enum rather than a free string so a typo in a producer is a compile
 * error instead of a notification that silently renders without an icon.
 */
public enum NotificationType {

    /** The Monday digest is ready. */
    WEEKLY_DIGEST,

    /** A plateau scan found an activity type that has stopped improving. */
    PLATEAU,

    /** A head-to-head challenge ended. */
    CHALLENGE_RESULT,

    /** A goal's deadline is close and the goal is not met. */
    GOAL_DEADLINE,

    /** A personal best was broken. */
    PERSONAL_BEST,

    /** Anything else. */
    GENERAL
}

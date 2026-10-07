package com.project.fitness.model;

/**
 * Where one person stands in relation to a challenge they were invited to.
 *
 * <p>Per participant rather than per challenge, which is the whole reason a group
 * challenge needs its own table: with three people invited, "the challenge is pending"
 * is no longer a single fact.
 */
public enum ParticipantStatus {

    /** Invited, not yet answered. Scored as zero and shown greyed out. */
    INVITED,

    /** In. The creator is this from the moment they create it. */
    ACCEPTED,

    /** Said no. Kept rather than deleted so they are not re-invited by accident. */
    DECLINED
}

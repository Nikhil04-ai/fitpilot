package com.project.fitness.model;

/**
 * Where a friend request has got to.
 *
 * <p>There is no DECLINED state on purpose: a declined request is deleted rather than
 * remembered. Keeping it would mean the requester's UI has to either show "they said
 * no" — which is unkind and useless — or hide a row that then silently blocks them from
 * ever asking again, because the pair is unique.
 */
public enum FriendshipStatus {

    /** Sent, not yet answered. Visible to the addressee as an invite. */
    PENDING,

    /** Both sides can see each other in their friends list. */
    ACCEPTED
}

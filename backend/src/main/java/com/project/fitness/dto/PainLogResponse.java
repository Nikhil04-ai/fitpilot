package com.project.fitness.dto;

import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.BodyPart;
import com.project.fitness.model.PainLog;

import java.time.LocalDateTime;

/**
 * A pain entry as the API returns it, with the workout it happened in.
 *
 * <p>The entity used to be returned as is, with its activity {@code @JsonIgnore}d, so a
 * link made through the API could never be seen, not even as an id (PLAN P6-F20). Built
 * inside the service's transaction, because the activity is lazy.
 */
public record PainLogResponse(String id, BodyPart bodyPart, Integer severity, String description,
                              LocalDateTime loggedAt, LinkedActivity activity) {

    /** Just enough of the workout to name it: "Running, 16 Nov". Null when not linked. */
    public record LinkedActivity(String id, ActivityType type, LocalDateTime startTime) {}

    public static PainLogResponse from(PainLog p) {
        Activity a = p.getActivity();
        return new PainLogResponse(p.getId(), p.getBodyPart(), p.getSeverity(), p.getDescription(), p.getLoggedAt(),
                a == null ? null : new LinkedActivity(a.getId(), a.getType(), a.getStartTime()));
    }
}

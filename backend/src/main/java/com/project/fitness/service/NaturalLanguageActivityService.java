package com.project.fitness.service;

import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.dto.ActivityResponse;
import com.project.fitness.dto.ExerciseRequest;
import com.project.fitness.model.ActivityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Feature 9 — Natural Language Activity Logging
 * User types "I ran 5km in 30 minutes this morning" and we parse + save it automatically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NaturalLanguageActivityService {

    private final GeminiService geminiService;
    private final ActivityService activityService;

    public ActivityResponse parseAndSave(String userId, String description) {
        // A blank description used to go to Gemini anyway, and whatever came back was
        // saved as an "OTHER" workout with nothing in it. That cost a model call and
        // added a fake session to streaks, goals and WORKOUT_COUNT challenges.
        if (description == null || description.isBlank()) {
            throw new RuntimeException("Describe the workout you did.");
        }

        Map<String, Object> parsed = geminiService.parseNaturalLanguageActivity(description);

        if (parsed == null) {
            throw new RuntimeException(geminiService.isConfigured()
                    ? "Couldn't read that activity — the AI service didn't respond. "
                      + "Try again in a moment, or log it with the form."
                    : "Natural language logging isn't set up on this server (no GEMINI_API_KEY). "
                      + "Log it with the form instead.");
        }

        ActivityRequest request = new ActivityRequest();
        request.setUserId(userId);

        // Map type string from Gemini to enum
        String typeStr = String.valueOf(parsed.get("type"));
        try {
            request.setType(ActivityType.valueOf(typeStr));
        } catch (IllegalArgumentException e) {
            log.warn("Gemini returned unknown activity type '{}', defaulting to OTHER", typeStr);
            request.setType(ActivityType.OTHER);
        }

        // Duration in minutes
        Object duration = parsed.get("durationMinutes");
        if (duration instanceof Number) {
            request.setDuration(((Number) duration).intValue());
        }

        // additionalMetrics pass-through
        Object metrics = parsed.get("additionalMetrics");
        if (metrics instanceof Map) {
            request.setAdditionalMetrics((Map<String, Object>) metrics);
        }

        // Distance. ActivityService also digs it out of additionalMetrics, so this is
        // belt and braces for whichever shape the model happened to return.
        if (parsed.get("distanceKm") instanceof Number distance) {
            request.setDistanceKm(distance.doubleValue());
        }

        request.setExercises(toExerciseRequests(parsed.get("exercises")));

        // Text with no workout in it ("asdkjh qwe") comes back as an OTHER type with no
        // duration, distance or movements; the model echoes the text into
        // additionalMetrics.notes with a null distance_km. Saving that records a session
        // that never happened, so it is refused. A metric only counts if it is a number.
        if (request.getDuration() == null && request.getDistanceKm() == null
                && request.getExercises() == null && !hasNumbers(request.getAdditionalMetrics())) {
            throw new RuntimeException("Couldn't find a workout in that description. "
                    + "Say what you did and for how long, e.g. \"ran 5 km in 30 minutes\".");
        }

        // Calories will be auto-calculated by ActivityService (Feature 1)
        request.setCaloriesBurned(null);
        request.setStartTime(LocalDateTime.now());

        return activityService.trackActivity(request);
    }

    /**
     * Reads the movement list out of the model's JSON.
     *
     * <p>Deliberately forgiving: this is generated output, so anything that isn't a
     * usable {name, sets, reps, weight} object is skipped rather than allowed to fail
     * the save. Losing one malformed movement beats losing the whole workout.
     */
    @SuppressWarnings("unchecked")
    private List<ExerciseRequest> toExerciseRequests(Object raw) {
        if (!(raw instanceof List<?> items) || items.isEmpty()) return null;

        List<ExerciseRequest> exercises = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> map = (Map<String, Object>) item;

            Object name = map.get("name");
            if (!(name instanceof String text) || text.isBlank()) continue;

            ExerciseRequest exercise = new ExerciseRequest();
            exercise.setName(text.trim());
            exercise.setSets(intOrNull(map.get("sets")));
            exercise.setReps(intOrNull(map.get("reps")));
            exercise.setWeightKg(doubleOrNull(map.get("weightKg")));
            exercises.add(exercise);
        }
        return exercises.isEmpty() ? null : exercises;
    }

    private boolean hasNumbers(Map<String, Object> metrics) {
        return metrics != null && metrics.values().stream().anyMatch(v -> v instanceof Number);
    }

    private Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Double doubleOrNull(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}

package com.project.fitness.service;

import com.project.fitness.dto.ActivityRequest;
import com.project.fitness.dto.ActivityResponse;
import com.project.fitness.dto.ExerciseRequest;
import com.project.fitness.dto.ExerciseResponse;
import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.Exercise;
import com.project.fitness.model.PersonalBest;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.PainLogRepository;
import com.project.fitness.repository.PersonalBestRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final PersonalBestRepository personalBestRepository;
    private final PainLogRepository painLogRepository;
    private final CalorieCalculatorService calorieCalculatorService;
    private final StreakService streakService;
    private final PersonalBestService personalBestService;
    private final MuscleRecoveryService muscleRecoveryService;
    private final ComebackService comebackService;
    private final GoalService goalService;
    private final RecommendationService recommendationService;
    private final ProgressionService progressionService;
    private final ActivityRecalculationService recalculationService;
    private final ExercisePersonalBestService exercisePersonalBestService;

    @Transactional
    public ActivityResponse trackActivity(ActivityRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new AccountNotFoundException(request.getUserId()));

        // The type drives calories, recovery and personal bests, and a null one used to
        // crash further down as a 500 (PLAN P4-7). After the account check, so a dead
        // session still gets its 409.
        if (request.getType() == null) {
            throw new RuntimeException("An activity needs a type, such as RUNNING or WEIGHT_TRAINING.");
        }
        InputChecks.difficultyRating(request.getDifficultyRating());   // only update checked it (PLAN P4-17)
        checkNumbers(request);
        Double distanceKm = resolveDistance(request);
        InputChecks.distanceKm(distanceKm);

        Map<String, Object> comebackPlan = comebackService.evaluateComeback(user);

        Integer calories = resolveCalories(request.getCaloriesBurned(), request.getType(),
                request.getDuration(), user);

        // Stats, challenge scores and timing analysis all filter on startTime, so an
        // activity saved without one is invisible to every one of them. Default it
        // rather than storing a null the rest of the app silently skips.
        LocalDateTime startTime = request.getStartTime() != null
                ? request.getStartTime() : LocalDateTime.now();

        Activity activity = Activity.builder()
                .user(user)
                .type(request.getType())
                .duration(request.getDuration())
                .caloriesBurned(calories)
                .startTime(startTime)
                .completedAt(completionOf(startTime, request.getDuration()))
                .additionalMetrics(request.getAdditionalMetrics())
                .distanceKm(distanceKm)
                .difficultyRating(request.getDifficultyRating())
                .notes(request.getNotes())
                .build();

        activity.replaceExercises(toExercises(request.getExercises()));

        Activity savedActivity = activityRepository.save(activity);

        // The workout's own date, not today's — otherwise a backdated entry is recorded
        // as having happened when it was typed in, and disagrees with what
        // ActivityRecalculationService derives from the same rows after an edit.
        streakService.updateStreak(user, startTime.toLocalDate());
        userRepository.save(user);

        PersonalBestService.Result pb = personalBestService.evaluate(user, savedActivity);
        List<String> exerciseRecords = exercisePersonalBestService.evaluate(user, savedActivity);
        List<String> recoveryWarnings = muscleRecoveryService.processActivity(user, savedActivity);
        List<String> goalAchievements = goalService.updateGoalProgress(user, savedActivity);
        recommendationService.maybeGenerateAfterActivity(user, savedActivity);

        ActivityResponse response = mapToResponse(savedActivity, user);
        response.setIsPersonalBest(pb.isNewBest());
        response.setPersonalBestMessage(pb.message());
        response.setExercisePersonalBests(exerciseRecords.isEmpty() ? null : exerciseRecords);
        response.setMuscleRecoveryWarnings(recoveryWarnings.isEmpty() ? null : recoveryWarnings);
        response.setComebackPlan(comebackPlan.isEmpty() ? null : comebackPlan);
        response.setGoalAchievements(goalAchievements.isEmpty() ? null : goalAchievements);
        return response;
    }

    /**
     * FIX: @Transactional keeps the Hibernate session open so activity.getUser()
     * (which is LAZY) can be accessed without LazyInitializationException.
     * Without @Transactional, the session closes after findById() returns,
     * and mapToResponse(activity) → activity.getUser() throws LazyInitializationException
     * → caught by GlobalExceptionHandler as RuntimeException → HTTP 400.
     */
    @Transactional
    public ActivityResponse rateActivity(String requestingUserId, String activityId, int rating) {
        if (rating < 1 || rating > 5) {
            throw new RuntimeException("Rating must be between 1 and 5");
        }
        Activity activity = loadOwned(activityId, requestingUserId);
        activity.setDifficultyRating(rating);
        activityRepository.save(activity);

        // Feature 13's other half. ProgressionService existed but had no callers at
        // all, so a rating was stored and never looked at again (PLAN P0-1).
        String suggestion = progressionService.analyseProgression(requestingUserId, activity);

        // User is LAZY — @Transactional keeps session open so getUser() works
        ActivityResponse response = mapToResponse(activity, activity.getUser());
        response.setProgressionSuggestion(suggestion == null || suggestion.isBlank() ? null : suggestion);
        return response;
    }

    @Transactional
    public ActivityResponse addNotes(String requestingUserId, String activityId, String notes) {
        Activity activity = loadOwned(activityId, requestingUserId);
        InputChecks.notes(notes);
        activity.setNotes(notes);
        activityRepository.save(activity);
        // User is LAZY — @Transactional keeps session open so getUser() works
        return mapToResponse(activity, activity.getUser());
    }

    /**
     * Edits a logged activity. Every field the client sends is applied; anything left
     * null is kept, so this doubles as a partial update.
     *
     * <p>Duration, calories and start time all feed derived state, so the whole lot is
     * recomputed afterwards rather than nudged — see ActivityRecalculationService.
     */
    @Transactional
    public ActivityResponse updateActivity(String requestingUserId, String activityId,
                                           ActivityRequest request) {
        Activity activity = loadOwned(activityId, requestingUserId);
        User user = activity.getUser();

        // Captured before any field changes: deciding whether the stored calorie figure
        // was calculated means re-running the calculator on what it was calculated from.
        ActivityType oldType = activity.getType();
        Integer oldDuration = activity.getDuration();
        Integer oldCalories = activity.getCaloriesBurned();

        // Before anything changes, so a refused edit saves nothing.
        checkNumbers(request);
        if (request.getDistanceKm() != null || request.getAdditionalMetrics() != null) {
            InputChecks.distanceKm(resolveDistance(request));
        }

        if (request.getType() != null)      activity.setType(request.getType());
        if (request.getDuration() != null)  activity.setDuration(request.getDuration());
        if (request.getStartTime() != null) activity.setStartTime(request.getStartTime());
        if (request.getNotes() != null)     activity.setNotes(request.getNotes());
        if (request.getDifficultyRating() != null) {
            InputChecks.difficultyRating(request.getDifficultyRating());
            activity.setDifficultyRating(request.getDifficultyRating());
        }
        if (request.getAdditionalMetrics() != null) {
            activity.setAdditionalMetrics(request.getAdditionalMetrics());
        }
        if (request.getDistanceKm() != null || request.getAdditionalMetrics() != null) {
            Double distance = resolveDistance(request);
            if (distance != null) activity.setDistanceKm(distance);
        }
        // Null leaves the movements alone (this is a partial update); an empty list
        // is an explicit "remove them all".
        if (request.getExercises() != null) {
            activity.replaceExercises(toExercises(request.getExercises()));
        }

        activity.setCaloriesBurned(editedCalories(request, activity, user,
                oldType, oldDuration, oldCalories));

        activity.setCompletedAt(completionOf(activity.getStartTime(), activity.getDuration()));
        activityRepository.save(activity);

        recalculationService.recalculateFor(user);

        return mapToResponse(activity, user);
    }

    /**
     * Deletes a logged activity and rebuilds everything that was derived from it.
     * Without the recalculation, deleting the workout that set a personal best would
     * leave the record standing, and deleting a mid-streak day would leave the streak
     * counting through a gap.
     */
    @Transactional
    public void deleteActivity(String requestingUserId, String activityId) {
        Activity activity = loadOwned(activityId, requestingUserId);
        User user = activity.getUser();

        // Pain logged against this workout outlives it, unlinked (PLAN P4-6). Without
        // this the foreign key refused the delete outright, as a 409.
        painLogRepository.unlinkActivity(activityId);
        activityRepository.delete(activity);
        activityRepository.flush();

        recalculationService.recalculateFor(user);
    }

    /**
     * Paginated history.
     *
     * <p>This used to return {@code Page<Activity>} — the JPA entity — straight from the
     * controller, which is why the frontend's PR badge could never render: the entity
     * has no isPersonalBest field, so the property was always undefined (PLAN P0-3).
     * Mapping through the DTO also makes the list the same shape as the create response.
     */
    @Transactional(readOnly = true)
    public Page<ActivityResponse> getUserActivitiesPage(String userId, Pageable pageable) {
        Map<ActivityType, PersonalBest> records = new EnumMap<>(ActivityType.class);
        for (PersonalBest pb : personalBestRepository.findByUserId(userId)) {
            records.put(pb.getActivityType(), pb);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        return activityRepository.findByUserId(userId, pageable).map(activity -> {
            ActivityResponse response = mapToResponse(activity, user);
            response.setIsPersonalBest(holdsRecord(activity, records.get(activity.getType())));
            return response;
        });
    }

    @Transactional(readOnly = true)
    public List<ActivityResponse> getUserActivities(String userId) {
        return activityRepository.findByUserId(userId).stream()
                .map(a -> mapToResponse(a, a.getUser()))
                .collect(Collectors.toList());
    }

    /** True when this activity is the one currently holding its type's record. */
    private boolean holdsRecord(Activity activity, PersonalBest record) {
        if (record == null) return false;
        boolean duration = activity.getDuration() != null
                && activity.getDuration().equals(record.getBestDuration());
        boolean calories = activity.getCaloriesBurned() != null
                && activity.getCaloriesBurned().equals(record.getBestCalories());
        boolean distance = activity.getDistanceKm() != null
                && record.getBestDistanceKm() != null
                && Math.abs(activity.getDistanceKm() - record.getBestDistanceKm()) < 0.001;
        return duration || calories || distance;
    }

    /**
     * Distance, preferring the dedicated field but still accepting it inside
     * additionalMetrics.
     *
     * <p>The parser has always emitted {@code distance_km} in the metrics blob, and old
     * clients still do. Rather than break them, the blob is read as a fallback and the
     * value promoted into the column, which is what makes it reachable to stats and
     * records at all (PLAN P0-2).
     */
    private Double resolveDistance(ActivityRequest request) {
        if (request.getDistanceKm() != null) return request.getDistanceKm();

        Map<String, Object> metrics = request.getAdditionalMetrics();
        if (metrics == null) return null;

        Object raw = metrics.get("distance_km");
        if (raw == null) raw = metrics.get("distanceKm");
        if (raw instanceof Number number) return number.doubleValue();
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                // A parser that returned "about 5km" is not a distance. Drop it rather
                // than fail the whole save over a field nothing depends on.
            }
        }
        return null;
    }

    /**
     * Refuses numbers a workout can't have: a negative or day-long duration, negative or
     * absurd calories, impossible sets, reps or loads, and notes longer than the column.
     * Each one used to be stored as sent, straight into stats, goals and challenge
     * scores. Distance is checked by the callers, after it is resolved from either field.
     */
    private void checkNumbers(ActivityRequest request) {
        InputChecks.durationMinutes(request.getDuration());
        InputChecks.caloriesBurned(request.getCaloriesBurned());
        InputChecks.notes(request.getNotes());
        if (request.getExercises() != null) {
            for (ExerciseRequest e : request.getExercises()) {
                if (e == null || e.getName() == null || e.getName().isBlank()) continue;   // skipped anyway
                InputChecks.exercise(e.getName(), e.getSets(), e.getReps(), e.getWeightKg());
            }
        }
    }

    /** Maps the request's movements, skipping entries with no name to hang them on. */
    private List<Exercise> toExercises(List<ExerciseRequest> requested) {
        List<Exercise> exercises = new ArrayList<>();
        if (requested == null) return exercises;

        for (ExerciseRequest r : requested) {
            if (r == null || r.getName() == null || r.getName().isBlank()) continue;
            Exercise exercise = new Exercise();
            exercise.setName(r.getName().trim());
            exercise.setSets(r.getSets());
            exercise.setReps(r.getReps());
            exercise.setWeightKg(r.getWeightKg());
            exercises.add(exercise);
        }
        return exercises;
    }

    /** Reading activity.getExercises() is a lazy collection — callers are @Transactional. */
    private List<ExerciseResponse> toExerciseResponses(Activity activity) {
        List<Exercise> exercises = activity.getExercises();
        if (exercises == null || exercises.isEmpty()) return null;

        String userId = activity.getUser() != null ? activity.getUser().getId() : null;
        List<ExerciseResponse> responses = new ArrayList<>(exercises.size());
        for (Exercise e : exercises) {
            ExerciseResponse r = new ExerciseResponse();
            r.setId(e.getId());
            r.setName(e.getName());
            r.setSets(e.getSets());
            r.setReps(e.getReps());
            r.setWeightKg(e.getWeightKg());
            r.setOrderIndex(e.getOrderIndex());
            r.setVolume(e.volume());
            r.setEstimatedOneRepMax(e.estimatedOneRepMax());
            r.setIsPersonalBest(userId != null && exercisePersonalBestService.holdsRecord(userId, e));
            responses.add(r);
        }
        return responses;
    }

    private Integer resolveCalories(Integer explicit, ActivityType type, Integer duration, User user) {
        if (explicit != null) return explicit;
        if (duration == null || type == null || user.getWeight() == null) return null;
        return calorieCalculatorService.calculate(type, duration, user.getWeight());
    }

    /**
     * The calorie figure an edit leaves behind (PLAN P4-2).
     *
     * <p>This used to recalculate whenever the edit omitted calories, so a notes-only
     * edit replaced the figure the user had typed, and with no weight on the profile
     * replaced it with null. Now, in order:
     * <ol>
     *   <li>a figure sent with the edit is stored as-is;</li>
     *   <li>{@code recalculateCalories} re-derives it on request;</li>
     *   <li>a changed type or duration re-derives it, but only if the stored figure was
     *       itself calculated: it is missing, or matches what the calculator gives for
     *       the old type and duration. A typed figure is the user's and stays;</li>
     *   <li>anything else keeps the stored figure.</li>
     * </ol>
     * A re-derivation that can't be done (no weight on the profile) keeps the stored
     * figure rather than erasing it.
     */
    private Integer editedCalories(ActivityRequest request, Activity activity, User user,
                                   ActivityType oldType, Integer oldDuration, Integer oldCalories) {
        if (request.getCaloriesBurned() != null) return request.getCaloriesBurned();

        boolean recalculate = Boolean.TRUE.equals(request.getRecalculateCalories());
        if (!recalculate) {
            boolean inputsChanged = !Objects.equals(oldType, activity.getType())
                    || !Objects.equals(oldDuration, activity.getDuration());
            boolean wasCalculated = oldCalories == null
                    || oldCalories.equals(resolveCalories(null, oldType, oldDuration, user));
            recalculate = inputsChanged && wasCalculated;
        }
        if (!recalculate) return oldCalories;

        Integer derived = resolveCalories(null, activity.getType(), activity.getDuration(), user);
        return derived != null ? derived : oldCalories;
    }

    /**
     * When the workout finished. The column existed from the start and nothing ever
     * wrote to it, so it was null on every activity ever logged (PLAN P0-5).
     */
    private LocalDateTime completionOf(LocalDateTime startTime, Integer duration) {
        if (startTime == null || duration == null) return null;
        return startTime.plusMinutes(duration);
    }

    private Activity loadOwned(String activityId, String requestingUserId) {
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new RuntimeException("Activity not found: " + activityId));
        // An activity id is all the caller sends — without this check anyone could
        // rate, rewrite or delete any other user's workout.
        requireOwner(activity, requestingUserId);
        return activity;
    }

    /**
     * The owner id is compared against the id the controller read from the JWT.
     * Lazy user access is safe here — every caller is inside a @Transactional method.
     */
    private void requireOwner(Activity activity, String requestingUserId) {
        if (!activity.getUser().getId().equals(requestingUserId)) {
            throw new AccessDeniedException("You don't have access to this activity.");
        }
    }

    private ActivityResponse mapToResponse(Activity activity, User user) {
        ActivityResponse r = new ActivityResponse();
        r.setId(activity.getId());
        r.setUserId(user.getId());
        r.setType(activity.getType());
        r.setDuration(activity.getDuration());
        r.setCaloriesBurned(activity.getCaloriesBurned());
        r.setStartTime(activity.getStartTime());
        r.setCompletedAt(activity.getCompletedAt());
        r.setDifficultyRating(activity.getDifficultyRating());
        r.setNotes(activity.getNotes());
        r.setAdditionalMetrics(activity.getAdditionalMetrics());
        r.setDistanceKm(activity.getDistanceKm());
        r.setExercises(toExerciseResponses(activity));
        r.setTotalVolume(activity.totalVolume());
        r.setCreatedAt(activity.getCreatedAt());
        r.setUpdatedAt(activity.getUpdatedAt());
        r.setCurrentStreak(user.getCurrentStreak());
        r.setLongestStreak(user.getLongestStreak());
        r.setIsPersonalBest(false);
        return r;
    }
}

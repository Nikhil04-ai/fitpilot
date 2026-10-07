package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.Exercise;
import com.project.fitness.model.ExercisePersonalBest;
import com.project.fitness.model.User;
import com.project.fitness.repository.ExercisePersonalBestRepository;
import com.project.fitness.repository.ExerciseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Strength records, per movement (PLAN P1-1, and the half of P0-6 that was left open).
 *
 * <p>{@link PersonalBestService} tracks the longest and highest-calorie session for an
 * activity <em>type</em>. For lifting that is close to meaningless: a long session is
 * not an achievement, and the calorie figure is an estimate off a MET table. What
 * counts is load — the heaviest set of a given movement, the best estimated 1RM, and
 * the most total volume in one session.
 */
@Service
@RequiredArgsConstructor
public class ExercisePersonalBestService {

    private final ExercisePersonalBestRepository repository;
    private final ExerciseRepository exerciseRepository;

    /**
     * Updates the records for every movement in a saved workout.
     *
     * @return one human-readable line per record broken, in the order the movements
     *         were logged; empty when nothing was beaten.
     */
    @Transactional
    public List<String> evaluate(User user, Activity activity) {
        List<String> broken = new ArrayList<>();

        for (Exercise exercise : activity.getExercises()) {
            String key = Exercise.normalise(exercise.getName());
            if (key == null) continue;

            LocalDateTime achievedAt = activity.getStartTime() != null
                    ? activity.getStartTime() : LocalDateTime.now();

            ExercisePersonalBest record = repository
                    .findByUserIdAndNormalisedName(user.getId(), key)
                    .orElse(null);

            if (record == null) {
                // Nothing measurable at all (no load and no rep count) means nothing to
                // record — a card of three dashes is worse than no card.
                if (exercise.getWeightKg() == null && exercise.getReps() == null) continue;

                repository.save(ExercisePersonalBest.builder()
                        .user(user)
                        .normalisedName(key)
                        .displayName(exercise.getName())
                        .bestWeightKg(exercise.getWeightKg())
                        .bestWeightReps(exercise.getReps())
                        .bestEstimatedOneRepMax(exercise.estimatedOneRepMax())
                        .bestVolume(exercise.volume())
                        .bestBodyweightReps(bodyweightReps(exercise))
                        .achievedAt(achievedAt)
                        .build());
                // The first time a movement is logged there is nothing to beat, so it
                // is a benchmark, not a personal best. Announcing "new record!" for it
                // is the kind of praise that stops meaning anything.
                continue;
            }

            List<String> beaten = new ArrayList<>();

            if (isGreater(exercise.getWeightKg(), record.getBestWeightKg())) {
                record.setBestWeightKg(exercise.getWeightKg());
                record.setBestWeightReps(exercise.getReps());
                beaten.add(formatKg(exercise.getWeightKg()) + " x "
                        + (exercise.getReps() != null ? exercise.getReps() : 1));
            }
            if (isGreater(exercise.estimatedOneRepMax(), record.getBestEstimatedOneRepMax())) {
                record.setBestEstimatedOneRepMax(exercise.estimatedOneRepMax());
                beaten.add("est. 1RM " + formatKg(exercise.estimatedOneRepMax()));
            }
            if (isGreater(exercise.volume(), record.getBestVolume())) {
                record.setBestVolume(exercise.volume());
                beaten.add(formatKg(exercise.volume()) + " total volume");
            }
            Integer reps = bodyweightReps(exercise);
            if (reps != null && (record.getBestBodyweightReps() == null
                    || reps > record.getBestBodyweightReps())) {
                record.setBestBodyweightReps(reps);
                beaten.add(reps + " reps unloaded");
            }

            if (beaten.isEmpty()) continue;

            record.setDisplayName(exercise.getName());
            record.setAchievedAt(achievedAt);
            repository.save(record);
            broken.add(exercise.getName() + " — " + String.join(", ", beaten));
        }

        return broken;
    }

    /**
     * Rebuilds every exercise record for a user from the exercise rows.
     *
     * <p>Called after an edit or a delete, for the same reason
     * {@link ActivityRecalculationService} exists: deleting the session that set a
     * record has to lower the record, and there is no incremental way to undo a max.
     */
    @Transactional
    public void recalculateFor(User user) {
        Map<String, ExercisePersonalBest> rebuilt = new HashMap<>();

        for (Exercise e : exerciseRepository.findAllForUser(user.getId())) {
            String key = Exercise.normalise(e.getName());
            if (key == null) continue;

            if (e.getWeightKg() == null && e.getReps() == null) continue;

            ExercisePersonalBest agg = rebuilt.computeIfAbsent(key, k ->
                    ExercisePersonalBest.builder()
                            .user(user).normalisedName(k).displayName(e.getName())
                            .build());

            LocalDateTime when = e.getActivity() != null && e.getActivity().getStartTime() != null
                    ? e.getActivity().getStartTime() : e.getCreatedAt();

            if (isGreater(e.getWeightKg(), agg.getBestWeightKg())) {
                agg.setBestWeightKg(e.getWeightKg());
                agg.setBestWeightReps(e.getReps());
                agg.setDisplayName(e.getName());
                agg.setAchievedAt(later(agg.getAchievedAt(), when));
            }
            if (isGreater(e.estimatedOneRepMax(), agg.getBestEstimatedOneRepMax())) {
                agg.setBestEstimatedOneRepMax(e.estimatedOneRepMax());
                agg.setAchievedAt(later(agg.getAchievedAt(), when));
            }
            if (isGreater(e.volume(), agg.getBestVolume())) {
                agg.setBestVolume(e.volume());
                agg.setAchievedAt(later(agg.getAchievedAt(), when));
            }
            Integer reps = bodyweightReps(e);
            if (reps != null && (agg.getBestBodyweightReps() == null
                    || reps > agg.getBestBodyweightReps())) {
                agg.setBestBodyweightReps(reps);
                agg.setAchievedAt(later(agg.getAchievedAt(), when));
            }
        }

        // A movement with no sets left loses its record row rather than keeping a
        // number that no surviving row supports.
        for (ExercisePersonalBest existing : repository.findByUserId(user.getId())) {
            ExercisePersonalBest fresh = rebuilt.remove(existing.getNormalisedName());
            if (fresh == null) {
                repository.delete(existing);
                continue;
            }
            existing.setDisplayName(fresh.getDisplayName());
            existing.setBestWeightKg(fresh.getBestWeightKg());
            existing.setBestWeightReps(fresh.getBestWeightReps());
            existing.setBestEstimatedOneRepMax(fresh.getBestEstimatedOneRepMax());
            existing.setBestVolume(fresh.getBestVolume());
            existing.setBestBodyweightReps(fresh.getBestBodyweightReps());
            existing.setAchievedAt(fresh.getAchievedAt());
            repository.save(existing);
        }

        // Anything left is a movement that has no record row yet.
        rebuilt.values().forEach(repository::save);
    }

    /** True when this entry currently holds one of its movement's records. */
    @Transactional(readOnly = true)
    public boolean holdsRecord(String userId, Exercise exercise) {
        String key = Exercise.normalise(exercise.getName());
        if (key == null) return false;

        Optional<ExercisePersonalBest> record = repository.findByUserIdAndNormalisedName(userId, key);
        if (record.isEmpty()) return false;

        ExercisePersonalBest r = record.get();
        Integer reps = bodyweightReps(exercise);
        return equalish(exercise.getWeightKg(), r.getBestWeightKg())
                || equalish(exercise.estimatedOneRepMax(), r.getBestEstimatedOneRepMax())
                || equalish(exercise.volume(), r.getBestVolume())
                || (reps != null && reps.equals(r.getBestBodyweightReps()));
    }

    /**
     * The rep count, but only for a set with no added load.
     *
     * <p>Guarding on weight keeps the two kinds of achievement apart: 20 reps of an
     * empty bar is not the same accomplishment as 20 strict pull-ups, and one number
     * covering both would let the easier one take the record.
     */
    private static Integer bodyweightReps(Exercise exercise) {
        if (exercise.getWeightKg() != null && exercise.getWeightKg() > 0) return null;
        return exercise.getReps();
    }

    /** Null never beats anything, and never loses to anything either. */
    private static boolean isGreater(Double candidate, Double current) {
        if (candidate == null) return false;
        return current == null || candidate > current;
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    /** Doubles that came out of the same arithmetic still need a tolerance to compare. */
    private static boolean equalish(Double a, Double b) {
        return a != null && b != null && Math.abs(a - b) < 0.001;
    }

    /** "60kg", not "60.0kg" — but still "62.5kg" when the half matters. */
    private static String formatKg(Double kg) {
        if (kg == null) return "—";
        return (kg == Math.floor(kg) ? String.valueOf(kg.longValue()) : String.valueOf(kg)) + "kg";
    }
}

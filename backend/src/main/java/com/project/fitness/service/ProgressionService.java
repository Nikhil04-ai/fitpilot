package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.Exercise;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.ExerciseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Feature 13 — progression analysis.
 *
 * <p>Two sources, in order of how much they are worth trusting:
 *
 * <ol>
 *   <li><b>Logged load.</b> If the workout records actual movements, the estimated 1RM
 *       for each one over its last few sessions says whether the person is progressing,
 *       and by how much. This is measured, not reported.</li>
 *   <li><b>Self-reported difficulty.</b> The original heuristic, kept as the fallback
 *       for cardio and for anyone not logging sets. A 1-5 rating is a real signal but a
 *       noisy one — the same session gets a 2 on a good day and a 4 on a bad one.</li>
 * </ol>
 *
 * <p>Before PLAN P1-1 only the second existed, which meant the advice for a lifter was
 * derived entirely from how hard the session felt and never from what they lifted.
 */
@Service
@RequiredArgsConstructor
public class ProgressionService {

    private final ActivityRepository activityRepository;
    private final ExerciseRepository exerciseRepository;

    /** Sessions of a movement needed before its trend is worth reading. */
    private static final int MIN_SESSIONS = 3;

    /** Below this much change across the window, a movement counts as stalled. */
    private static final double STALL_THRESHOLD_PERCENT = 1.0;

    /**
     * Returns a progression suggestion for the activity, or an empty string when there
     * is not enough to say anything honest.
     */
    public String analyseProgression(String userId, Activity ratedActivity) {
        String byLoad = analyseByLoad(userId, ratedActivity);
        if (!byLoad.isEmpty()) return byLoad;
        return analyseByDifficulty(userId, ratedActivity);
    }

    // ── Load-driven ────────────────────────────────────────────────────────

    /**
     * Compares each logged movement's best estimated 1RM now against its oldest
     * session inside the window.
     *
     * <p>Judged per movement rather than on session totals: adding a fourth exercise
     * raises total volume without anything actually getting stronger, so a totals-based
     * trend rewards doing more stuff rather than progressing.
     */
    private String analyseByLoad(String userId, Activity activity) {
        if (activity.getExercises() == null || activity.getExercises().isEmpty()) return "";

        List<String> progressing = new ArrayList<>();
        List<String> stalled = new ArrayList<>();

        for (Exercise exercise : activity.getExercises()) {
            String key = Exercise.normalise(exercise.getName());
            if (key == null) continue;

            // Newest first, and one row per set — collapse to the best estimate per
            // session so a day with five sets doesn't count as five data points.
            List<Double> perSession = bestPerSession(
                    exerciseRepository.findForUserByName(userId, key));
            if (perSession.size() < MIN_SESSIONS) continue;

            List<Double> window = perSession.subList(0, MIN_SESSIONS);
            double newest = window.get(0);
            double oldest = window.get(window.size() - 1);
            if (oldest <= 0) continue;

            double changePercent = ((newest - oldest) / oldest) * 100.0;

            if (changePercent > STALL_THRESHOLD_PERCENT) {
                progressing.add(String.format("%s is up %.0f%%", exercise.getName(), changePercent));
            } else if (changePercent < -STALL_THRESHOLD_PERCENT) {
                stalled.add(String.format("%s has dropped %.0f%%", exercise.getName(), Math.abs(changePercent)));
            } else {
                stalled.add(String.format("%s has not moved", exercise.getName()));
            }
        }

        if (progressing.isEmpty() && stalled.isEmpty()) return "";

        StringBuilder message = new StringBuilder("Across your last " + MIN_SESSIONS + " sessions: ");
        List<String> all = new ArrayList<>(progressing);
        all.addAll(stalled);
        message.append(String.join(", ", all)).append(". ");

        if (stalled.isEmpty()) {
            message.append("Keep the current progression going.");
        } else if (progressing.isEmpty()) {
            message.append("Add a small amount of weight, or an extra rep per set, before the next session.");
        } else {
            message.append("Push the stalled lifts specifically — the rest are still moving.");
        }
        return message.toString();
    }

    /**
     * One number per session: the best estimated 1RM recorded that day.
     *
     * <p>Rows arrive newest-first grouped by workout, so the session boundary is the
     * activity id changing. Sets whose 1RM cannot be estimated (bodyweight, or above
     * the rep range where Epley holds) are skipped rather than counted as zero.
     */
    private List<Double> bestPerSession(List<Exercise> setsNewestFirst) {
        List<Double> perSession = new ArrayList<>();
        String currentActivityId = null;
        Double best = null;

        for (Exercise e : setsNewestFirst) {
            String activityId = e.getActivity() != null ? e.getActivity().getId() : null;
            if (currentActivityId != null && !currentActivityId.equals(activityId)) {
                if (best != null) perSession.add(best);
                best = null;
            }
            currentActivityId = activityId;

            Double estimate = e.estimatedOneRepMax();
            if (estimate != null && (best == null || estimate > best)) best = estimate;
        }
        if (best != null) perSession.add(best);

        return perSession;
    }

    // ── Difficulty-driven fallback ─────────────────────────────────────────

    /**
     * The original heuristic: if the last 3 sessions of this type all felt easy or all
     * felt hard, say so. Unchanged apart from being the second thing tried.
     */
    private String analyseByDifficulty(String userId, Activity ratedActivity) {
        List<Activity> last3 = activityRepository
                .findTop3ByUserIdAndTypeOrderByStartTimeDesc(userId, ratedActivity.getType());

        if (last3.size() < 3) return "";

        boolean allHaveRatings = last3.stream().allMatch(a -> a.getDifficultyRating() != null);
        if (!allHaveRatings) return "";

        OptionalDouble avgRating = last3.stream()
                .mapToInt(Activity::getDifficultyRating).average();
        if (avgRating.isEmpty()) return "";

        double avg = avgRating.getAsDouble();
        String type = ratedActivity.getType().name().toLowerCase().replace('_', ' ');

        if (avg <= 2.0) {
            return "Your last 3 " + type
                    + " sessions all felt easy. Consider increasing intensity, distance, or duration.";
        } else if (avg >= 4.0) {
            return "Your last 3 " + type
                    + " sessions all felt very hard. Consider scaling back or adding a rest day to prevent burnout.";
        }

        return "";
    }
}

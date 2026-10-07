package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.PersonalBest;
import com.project.fitness.model.User;
import com.project.fitness.repository.PersonalBestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Feature 3 — Personal Best Tracker
 * After every activity save, checks if this is a new record for the activity type.
 */
@Service
@RequiredArgsConstructor
public class PersonalBestService {

    private final PersonalBestRepository personalBestRepository;

    /**
     * What a save did to the user's records: whether anything broke, and which of the
     * two it was. The message used to be hardcoded to "longest session" regardless,
     * so beating your calorie record was announced as a duration record (PLAN P0-6).
     */
    public record Result(boolean isNewBest, String message) {
        static final Result NONE = new Result(false, null);
    }

    /**
     * Returns true if the activity set a new personal best (duration or calories).
     * Upserts the PersonalBest record accordingly.
     */
    public boolean checkAndUpdate(User user, Activity activity) {
        return evaluate(user, activity).isNewBest();
    }

    /** "a, b and c" — an Oxford-comma-free list, because these are read aloud in a toast. */
    private static String joinNaturally(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }

    /** "5" not "5.0", "5.2" stays "5.2". */
    private static String trim(Double value) {
        if (value == null) return "—";
        return value == Math.floor(value) ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    /** As {@link #checkAndUpdate}, but also says which record was broken. */
    public Result evaluate(User user, Activity activity) {
        Optional<PersonalBest> existing =
                personalBestRepository.findByUserIdAndActivityType(user.getId(), activity.getType());

        String what = activity.getType().name().toLowerCase().replace('_', ' ');

        if (existing.isEmpty()) {
            // First activity of this type — automatically a personal best
            PersonalBest pb = PersonalBest.builder()
                    .user(user)
                    .activityType(activity.getType())
                    .bestDuration(activity.getDuration())
                    .bestCalories(activity.getCaloriesBurned())
                    .bestDistanceKm(activity.getDistanceKm())
                    .achievedAt(LocalDateTime.now())
                    .build();
            personalBestRepository.save(pb);
            return new Result(true, "First " + what + " session logged — that's a benchmark to beat.");
        }

        PersonalBest pb = existing.get();
        boolean durationBeaten = false;
        boolean caloriesBeaten = false;
        boolean distanceBeaten = false;

        if (activity.getDuration() != null
                && (pb.getBestDuration() == null || activity.getDuration() > pb.getBestDuration())) {
            pb.setBestDuration(activity.getDuration());
            durationBeaten = true;
        }

        if (activity.getCaloriesBurned() != null
                && (pb.getBestCalories() == null || activity.getCaloriesBurned() > pb.getBestCalories())) {
            pb.setBestCalories(activity.getCaloriesBurned());
            caloriesBeaten = true;
        }

        // Distance only became comparable once it was a column of its own — it was
        // parsed into a JSON blob before, where nothing could read it (PLAN P0-2).
        if (activity.getDistanceKm() != null && activity.getDistanceKm() > 0
                && (pb.getBestDistanceKm() == null || activity.getDistanceKm() > pb.getBestDistanceKm())) {
            pb.setBestDistanceKm(activity.getDistanceKm());
            distanceBeaten = true;
        }

        if (!durationBeaten && !caloriesBeaten && !distanceBeaten) {
            return Result.NONE;
        }

        pb.setAchievedAt(LocalDateTime.now());
        personalBestRepository.save(pb);

        // Name every record that actually broke. The message used to be hardcoded to
        // "longest session" whatever happened (PLAN P0-6), and with three records in
        // play an if/else chain over every combination is worse than listing them.
        List<String> beaten = new ArrayList<>();
        if (distanceBeaten) beaten.add(String.format("furthest at %s km", trim(activity.getDistanceKm())));
        if (durationBeaten) beaten.add(String.format("longest at %d min", activity.getDuration()));
        if (caloriesBeaten) beaten.add(String.format("most calories at %d", activity.getCaloriesBurned()));

        String message = String.format("New %s record — %s.", what, joinNaturally(beaten));
        return new Result(true, message);
    }
}

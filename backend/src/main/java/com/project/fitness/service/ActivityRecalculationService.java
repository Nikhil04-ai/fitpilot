package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.MuscleGroup;
import com.project.fitness.model.MuscleRecovery;
import com.project.fitness.model.PersonalBest;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.MuscleRecoveryRepository;
import com.project.fitness.repository.PersonalBestRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rebuilds every piece of derived state from the activity table.
 *
 * <p>Logging an activity updates streaks, personal bests and muscle recovery
 * <em>incrementally</em> — each one only ever moves forward, which is correct as long
 * as activities are only ever added. The moment an activity can be edited or deleted
 * (PLAN P0-4) that stops holding: deleting the workout that set a personal best has to
 * lower the record, and deleting yesterday's workout has to break today's streak.
 *
 * <p>There is no incremental way to undo those, so this service recomputes them from
 * the activity rows, which are the source of truth. It runs after an edit or a delete,
 * never on the create path — creates stay on the cheap incremental path.
 */
@Service
@RequiredArgsConstructor
public class ActivityRecalculationService {

    private final ActivityRepository activityRepository;
    private final PersonalBestRepository personalBestRepository;
    private final MuscleRecoveryRepository muscleRecoveryRepository;
    private final UserRepository userRepository;
    private final MuscleRecoveryService muscleRecoveryService;
    private final GoalService goalService;
    private final ExercisePersonalBestService exercisePersonalBestService;

    /**
     * Recomputes streaks, personal bests, muscle recovery and goal progress for a user.
     * Safe to call when the user has no activities left at all — everything resets to zero.
     */
    @Transactional
    public void recalculateFor(User user) {
        List<Activity> activities = activityRepository.findByUserId(user.getId());

        recalculateStreak(user, activities);
        recalculatePersonalBests(user, activities);
        // Per-movement strength records have the same problem and the same fix:
        // deleting the session you set a 1RM in has to lower the 1RM (PLAN P1-1).
        exercisePersonalBestService.recalculateFor(user);
        recalculateMuscleRecovery(user, activities);
        goalService.recalculateProgress(user, activities);

        userRepository.save(user);
    }

    /**
     * Current streak = consecutive days up to today (or up to yesterday — a streak is
     * still alive until the day is out). Longest = the longest run anywhere in history.
     */
    void recalculateStreak(User user, List<Activity> activities) {
        Set<LocalDate> days = new TreeSet<>();
        for (Activity a : activities) {
            if (a.getStartTime() != null) days.add(a.getStartTime().toLocalDate());
        }

        if (days.isEmpty()) {
            user.setCurrentStreak(0);
            user.setLongestStreak(0);
            user.setLastActivityDate(null);
            return;
        }

        List<LocalDate> ordered = new ArrayList<>(days);
        int longest = 1;
        int run = 1;
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i - 1).plusDays(1).equals(ordered.get(i))) {
                run++;
            } else {
                run = 1;
            }
            longest = Math.max(longest, run);
        }

        LocalDate today = LocalDate.now();
        LocalDate last = ordered.get(ordered.size() - 1);

        // A streak only counts as "current" if it reaches today or yesterday.
        int current = 0;
        if (last.equals(today) || last.equals(today.minusDays(1))) {
            current = 1;
            for (int i = ordered.size() - 1; i > 0; i--) {
                if (ordered.get(i - 1).plusDays(1).equals(ordered.get(i))) current++;
                else break;
            }
        }

        user.setCurrentStreak(current);
        user.setLongestStreak(Math.max(longest, current));
        user.setLastActivityDate(last);
    }

    /**
     * Rebuilds the per-type records. A type with no activities left loses its record
     * row entirely rather than keeping a number nothing supports any more.
     */
    void recalculatePersonalBests(User user, List<Activity> activities) {
        Map<ActivityType, List<Activity>> byType = new EnumMap<>(ActivityType.class);
        for (Activity a : activities) {
            if (a.getType() == null) continue;
            byType.computeIfAbsent(a.getType(), t -> new ArrayList<>()).add(a);
        }

        for (PersonalBest existing : personalBestRepository.findByUserId(user.getId())) {
            if (!byType.containsKey(existing.getActivityType())) {
                personalBestRepository.delete(existing);
            }
        }

        for (Map.Entry<ActivityType, List<Activity>> entry : byType.entrySet()) {
            Integer bestDuration = entry.getValue().stream()
                    .map(Activity::getDuration).filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            Integer bestCalories = entry.getValue().stream()
                    .map(Activity::getCaloriesBurned).filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            Double bestDistance = entry.getValue().stream()
                    .map(Activity::getDistanceKm).filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);

            final Integer bd = bestDuration;
            final Integer bc = bestCalories;
            final Double bdist = bestDistance;
            LocalDateTime achievedAt = entry.getValue().stream()
                    .filter(a -> (bd != null && bd.equals(a.getDuration()))
                              || (bc != null && bc.equals(a.getCaloriesBurned()))
                              || (bdist != null && bdist.equals(a.getDistanceKm())))
                    .map(Activity::getStartTime).filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(LocalDateTime.now());

            PersonalBest pb = personalBestRepository
                    .findByUserIdAndActivityType(user.getId(), entry.getKey())
                    .orElseGet(() -> PersonalBest.builder()
                            .user(user)
                            .activityType(entry.getKey())
                            .build());

            pb.setBestDuration(bestDuration);
            pb.setBestCalories(bestCalories);
            pb.setBestDistanceKm(bestDistance);
            pb.setAchievedAt(achievedAt);
            personalBestRepository.save(pb);
        }
    }

    /**
     * lastWorkedAt per muscle group becomes the start time of the most recent surviving
     * activity that works it. A group nothing works any more is cleared, not left
     * pointing at a deleted workout.
     */
    void recalculateMuscleRecovery(User user, List<Activity> activities) {
        Map<MuscleGroup, LocalDateTime> latest = new EnumMap<>(MuscleGroup.class);

        for (Activity a : activities) {
            if (a.getStartTime() == null || a.getType() == null) continue;
            for (MuscleGroup group : muscleRecoveryService.musclesFor(a.getType())) {
                LocalDateTime current = latest.get(group);
                if (current == null || a.getStartTime().isAfter(current)) {
                    latest.put(group, a.getStartTime());
                }
            }
        }

        for (MuscleRecovery recovery : muscleRecoveryRepository.findByUserId(user.getId())) {
            recovery.setLastWorkedAt(latest.get(recovery.getMuscleGroup()));
            muscleRecoveryRepository.save(recovery);
        }

        for (Map.Entry<MuscleGroup, LocalDateTime> entry : latest.entrySet()) {
            Optional<MuscleRecovery> existing =
                    muscleRecoveryRepository.findByUserIdAndMuscleGroup(user.getId(), entry.getKey());
            if (existing.isEmpty()) {
                muscleRecoveryRepository.save(MuscleRecovery.builder()
                        .user(user)
                        .muscleGroup(entry.getKey())
                        .lastWorkedAt(entry.getValue())
                        .build());
            }
        }
    }
}

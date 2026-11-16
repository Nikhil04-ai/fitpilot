package com.project.fitness.service;

import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Feature 2 — Activity Streak System. The one place a streak is worked out.
 *
 * <p>Two things used to go wrong here, both found by the full-app test run of 2026-11-16:
 * <ul>
 *   <li>Logging a workout updated the stored streak <em>incrementally</em>, and returned
 *       early for a workout dated before the last one. So backdated workouts (yesterday,
 *       then the day before) never extended a streak, until some unrelated edit made
 *       {@link ActivityRecalculationService} derive it properly. Now logging derives it
 *       from the workout dates too, so there is one definition, not two that disagree.</li>
 *   <li>The stored number is a snapshot of the day it was written. Nothing re-read it as
 *       days passed, so someone who stopped training kept a "3 day streak" for good. Every
 *       read now goes through {@link #current}, which is 0 once the last workout is more
 *       than a day old.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StreakService {

    private final ActivityRepository activityRepository;

    /** Rebuilds the user's streak from the dates of their workouts. Caller saves the user. */
    public void recompute(User user) {
        recompute(user, activityRepository.findStartTimesByUserId(user.getId()), LocalDate.now());
    }

    /**
     * Current streak = consecutive days with a workout, ending today or yesterday (a streak
     * is still alive until the day is out). Longest = the longest run anywhere in history.
     */
    static void recompute(User user, Collection<LocalDateTime> startTimes, LocalDate today) {
        TreeSet<LocalDate> days = new TreeSet<>();
        for (LocalDateTime t : startTimes) if (t != null) days.add(t.toLocalDate());

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
            run = ordered.get(i - 1).plusDays(1).equals(ordered.get(i)) ? run + 1 : 1;
            longest = Math.max(longest, run);
        }

        // Only workouts up to today count towards the current run. A start time a few
        // hours ahead is allowed (InputChecks.startTime), and must not break it.
        LocalDate last = days.floor(today);
        int current = 0;
        if (last != null && (last.equals(today) || last.equals(today.minusDays(1)))) {
            current = 1;
            for (LocalDate d = last.minusDays(1); days.contains(d); d = d.minusDays(1)) current++;
        }

        user.setCurrentStreak(current);
        user.setLongestStreak(Math.max(longest, current));
        user.setLastActivityDate(ordered.get(ordered.size() - 1));
    }

    /** The streak as it stands today: what every response should show. */
    public static int current(User user) {
        return currentAsOf(user, LocalDate.now());
    }

    /** The stored streak, unless the last workout is older than yesterday: then it has lapsed. */
    static int currentAsOf(User user, LocalDate today) {
        LocalDate last = user.getLastActivityDate();
        int stored = Objects.requireNonNullElse(user.getCurrentStreak(), 0);
        if (last == null || last.isBefore(today.minusDays(1))) return 0;
        return stored;
    }
}

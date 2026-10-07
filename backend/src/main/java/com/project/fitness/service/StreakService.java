package com.project.fitness.service;

import com.project.fitness.model.User;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Feature 2 — Activity Streak System
 * Called after every activity save to keep currentStreak and longestStreak up to date.
 */
@Service
public class StreakService {

    /** Streak as of today — the behaviour for a workout logged as it happens. */
    public void updateStreak(User user) {
        updateStreak(user, LocalDate.now());
    }

    /**
     * Streak as of the day the workout actually happened.
     *
     * <p>This used to key on {@code LocalDate.now()} unconditionally — the day the
     * button was pressed, not the day the workout was done. Backdating three activities
     * to three consecutive days therefore left the streak at 1, because all three were
     * recorded as happening "today".
     *
     * <p>It also put this path at odds with {@link ActivityRecalculationService}, which
     * derives the streak from the activity rows: after any edit or delete the streak
     * would jump from the incremental value to the derived one. Both now read the same
     * source of truth, the activity's own date.
     */
    public void updateStreak(User user, LocalDate activityDate) {
        LocalDate today = activityDate;
        LocalDate lastDate = user.getLastActivityDate();

        // A backdated entry must never pull a more recent streak backwards.
        if (lastDate != null && lastDate.isAfter(today)) {
            return;
        }

        if (lastDate == null) {
            // First ever activity
            user.setCurrentStreak(1);
        } else if (lastDate.equals(today)) {
            // Already logged today — no change
            return;
        } else if (lastDate.equals(today.minusDays(1))) {
            // Consecutive day — extend streak
            user.setCurrentStreak(user.getCurrentStreak() + 1);
        } else {
            // Gap of 2+ days — reset streak
            user.setCurrentStreak(1);
        }

        // Update longest streak if current surpasses it
        if (user.getCurrentStreak() > user.getLongestStreak()) {
            user.setLongestStreak(user.getCurrentStreak());
        }

        user.setLastActivityDate(today);
    }
}

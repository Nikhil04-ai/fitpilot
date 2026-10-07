package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.PersonalBestRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Feature 11 — Weekly / Monthly / All-Time Stats API
 */
@Transactional
@Service
@RequiredArgsConstructor
public class StatsService {

    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final PersonalBestRepository personalBestRepository;

    public Map<String, Object> getWeeklyStats(String userId) {
        LocalDateTime from = LocalDateTime.now().minusDays(7);
        LocalDateTime to = LocalDateTime.now();
        return buildStats(userId, from, to);
    }

    public Map<String, Object> getMonthlyStats(String userId) {
        LocalDateTime from = LocalDateTime.now().minusDays(30);
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime prevFrom = from.minusDays(30);

        Map<String, Object> current = buildStats(userId, from, to);

        // Week-over-week comparison (last 7 days vs prior 7 days)
        Map<String, Object> prevWeek = buildStats(userId, to.minusDays(14), to.minusDays(7));

        int currentCalories = (int) current.getOrDefault("totalCalories", 0);
        int prevCalories = (int) prevWeek.getOrDefault("totalCalories", 0);
        double change = prevCalories > 0
                ? ((currentCalories - prevCalories) * 100.0 / prevCalories)
                : 0;

        current.put("weekOverWeekCalorieChange", String.format("%+.1f%%", change));
        return current;
    }

    /**
     * One row per calendar day for the last {@code days} days — what the volume chart
     * is drawn from.
     *
     * <p>Days with nothing logged are emitted as zeros rather than omitted. That is the
     * whole point: a series that only contains the days you trained draws a rest week
     * as a straight line between two workouts and makes a gap look like consistency.
     *
     * <p>Aggregated in one query and bucketed in memory. A per-day query would be 30
     * round trips to render one chart.
     */
    public List<Map<String, Object>> getDailySeries(String userId, int days) {
        // Same treatment as every other read here: a missing row is a dead session,
        // not an empty month, and the frontend self-heals from the 409.
        userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        int span = Math.max(1, Math.min(days, 365));
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(span - 1L);

        Map<LocalDate, int[]> byDay = new HashMap<>();   // date -> [calories, minutes, workouts]
        for (Activity a : activityRepository.findByUserIdAndStartTimeBetween(
                userId, start.atStartOfDay(), today.atTime(LocalTime.MAX))) {
            if (a.getStartTime() == null) continue;
            int[] bucket = byDay.computeIfAbsent(a.getStartTime().toLocalDate(), d -> new int[3]);
            if (a.getCaloriesBurned() != null) bucket[0] += a.getCaloriesBurned();
            if (a.getDuration() != null)       bucket[1] += a.getDuration();
            bucket[2]++;
        }

        List<Map<String, Object>> series = new ArrayList<>(span);
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            int[] b = byDay.get(d);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("calories", b != null ? b[0] : 0);
            row.put("minutes",  b != null ? b[1] : 0);
            row.put("workouts", b != null ? b[2] : 0);
            series.add(row);
        }
        return series;
    }

    public Map<String, Object> getAllTimeStats(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        // Aggregated in the database. Loading every activity a user has ever logged
        // just to sum two columns is the kind of thing that works fine with 20 rows
        // and falls over with 20,000.
        long totalWorkouts = activityRepository.countByUserId(userId);
        int totalCalories = activityRepository.sumCaloriesByUserId(userId);
        int totalMinutes = activityRepository.sumDurationByUserId(userId);

        long personalBestCount = personalBestRepository.countByUserId(userId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalWorkouts", totalWorkouts);
        stats.put("totalCaloriesBurned", totalCalories);
        stats.put("totalDurationMinutes", totalMinutes);
        stats.put("longestStreak", user.getLongestStreak());
        stats.put("currentStreak", user.getCurrentStreak());
        stats.put("personalBestsCount", personalBestCount);

        return stats;
    }

    private Map<String, Object> buildStats(String userId, LocalDateTime from, LocalDateTime to) {
        List<Activity> activities = activityRepository.findByUserIdAndStartTimeBetween(userId, from, to);

        int totalCalories = activityRepository.sumCaloriesByUserIdAndDateRange(userId, from, to);
        int totalDuration = activityRepository.sumDurationByUserIdAndDateRange(userId, from, to);
        int activityCount = activities.size();

        // Most frequent activity type
        ActivityType mostFrequent = activities.stream()
                .filter(a -> a.getType() != null)
                .collect(Collectors.groupingBy(Activity::getType, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // Both callers pass CurrentUser.requireSelf(...), so a missing row here is a
        // dead session, not an empty week. Returning zeros instead made the panel
        // render a plausible-looking "0 workouts" for an account that was gone.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCalories", totalCalories);
        stats.put("totalDurationMinutes", totalDuration);
        stats.put("activityCount", activityCount);
        stats.put("mostFrequentActivityType", mostFrequent != null ? mostFrequent.name() : "N/A");
        stats.put("currentStreak", user.getCurrentStreak());
        stats.put("longestStreak", user.getLongestStreak());
        stats.put("fromDate", from.toString());
        stats.put("toDate", to.toString());

        return stats;
    }
}

package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature 5 — Workout Timing Optimizer
 * Analyses which hours of day the user actually completes workouts and reports peak time.
 */
@Service
@RequiredArgsConstructor
public class TimingAnalysisService {

    private final ActivityRepository activityRepository;

    public Map<String, Object> analyseTimingForUser(String userId) {
        LocalDateTime from = LocalDateTime.now().minusDays(30);
        LocalDateTime to = LocalDateTime.now();

        List<Activity> activities = activityRepository.findByUserIdAndStartTimeBetween(userId, from, to);

        if (activities.isEmpty()) {
            return Map.of("message", "Not enough data yet. Log more workouts to see your peak time.");
        }

        // Count activities per hour-of-day bucket
        Map<Integer, Integer> hourCounts = new HashMap<>();
        for (Activity a : activities) {
            if (a.getStartTime() != null) {
                int hour = a.getStartTime().getHour();
                hourCounts.merge(hour, 1, Integer::sum);
            }
        }

        if (hourCounts.isEmpty()) {
            return Map.of("message", "No activities with start times found in the last 30 days.");
        }

        // Find the peak hour
        int peakHour = hourCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);

        int peakCount = hourCounts.get(peakHour);
        double peakPercent = (peakCount * 100.0) / activities.size();

        String timeLabel = String.format("%02d:00–%02d:00", peakHour, peakHour + 1);
        String message = String.format(
                "Your data shows you complete %.0f%% of workouts between %s. Consider scheduling all workouts in this window.",
                peakPercent, timeLabel);

        // The full 24-bucket distribution, not just the winner. The counts were always
        // computed here and then thrown away, so the panel could say "you train at 7am"
        // but never show the shape behind it — one dominant hour and a scattered week
        // produced the identical sentence (PLAN P1-3).
        List<Integer> distribution = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            distribution.add(hourCounts.getOrDefault(h, 0));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("peakWorkoutHour", peakHour);
        result.put("peakTimeWindow", timeLabel);
        result.put("activitiesAnalysed", activities.size());
        result.put("completionRateAtPeak", String.format("%.1f%%", peakPercent));
        result.put("suggestion", message);
        result.put("hourlyDistribution", distribution);
        return result;
    }
}

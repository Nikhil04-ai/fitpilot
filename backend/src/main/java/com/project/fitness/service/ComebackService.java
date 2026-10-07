package com.project.fitness.service;

import com.project.fitness.model.User;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Feature 8 — Comeback Protocol
 * Detects users returning after 14+ day gaps and suggests reduced intensity to prevent injury.
 */
@Service
public class ComebackService {

    /**
     * Returns a comeback plan if the user has been away 14+ days, otherwise returns empty map.
     */
    public Map<String, Object> evaluateComeback(User user) {
        LocalDate lastActivity = user.getLastActivityDate();
        if (lastActivity == null) {
            return Map.of(); // first-ever activity, not a comeback
        }

        long daysAway = ChronoUnit.DAYS.between(lastActivity, LocalDate.now());

        if (daysAway < 14) {
            return Map.of(); // not a comeback scenario
        }

        // Determine which week of comeback the user is in
        double intensityMultiplier;
        String weekLabel;

        if (daysAway <= 21) {
            intensityMultiplier = 0.40;
            weekLabel = "Week 1";
        } else if (daysAway <= 28) {
            intensityMultiplier = 0.60;
            weekLabel = "Week 2";
        } else {
            intensityMultiplier = 0.80;
            weekLabel = "Week 3+";
        }

        Map<String, Object> plan = new HashMap<>();
        plan.put("comebackDetected", true);
        plan.put("daysAway", daysAway);
        plan.put("recommendedIntensity", String.format("%.0f%%", intensityMultiplier * 100));
        plan.put("weekLabel", weekLabel);
        plan.put("message", String.format(
                "Welcome back! You've been away %d days. Starting at %.0f%% of your previous intensity to prevent injury. " +
                "Gradually increase over the next 3 weeks.",
                daysAway, intensityMultiplier * 100));

        return plan;
    }
}

package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.BodyMetricsLog;
import com.project.fitness.model.User;
import com.project.fitness.repository.BodyMetricsLogRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Transactional
@Service
@RequiredArgsConstructor
public class BodyMetricsService {

    private final BodyMetricsLogRepository bodyMetricsLogRepository;
    private final UserRepository userRepository;
    private final GoalService goalService;

    /** Update user's weight/height, log a history entry, return full stats */
    public Map<String, Object> updateMetrics(String userId, Double weight, Double height) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        if (weight != null) user.setWeight(weight);
        if (height != null) user.setHeight(height);
        userRepository.save(user);

        double w = user.getWeight() != null ? user.getWeight() : 0;
        double h = user.getHeight() != null ? user.getHeight() : 0;

        Double bmi = null;
        String category = null;
        if (w > 0 && h > 0) {
            double hm = h / 100.0;
            bmi = Math.round((w / (hm * hm)) * 10.0) / 10.0;
            category = bmiCategory(bmi);
        }

        BodyMetricsLog log = BodyMetricsLog.builder()
                .user(user)
                .weight(w)
                .height(h)
                .bmi(bmi)
                .bmiCategory(category)
                .build();
        bodyMetricsLogRepository.save(log);

        // A weigh-in is the only thing that moves a TARGET_WEIGHT goal, and nothing
        // else in the app ever triggers one: those goals are not fed by workouts or by
        // meals, so without this the Goals panel would keep showing the weight from
        // whenever the goal was created (PLAN P2-1).
        Map<String, Object> response = buildResponse(user, bmi, category);
        List<String> goalAchievements = goalService.refreshStateGoals(user);
        if (!goalAchievements.isEmpty()) response.put("goalAchievements", goalAchievements);
        return response;
    }

    /**
     * How many recent weigh-ins ride along with /metrics for the trend chart. The full
     * history used to be embedded, growing with every save forever; older entries are
     * still reachable page by page from {@link #getHistory} (PLAN P3-3).
     */
    static final int EMBEDDED_HISTORY_LIMIT = 100;

    /** Get current BMI + the most recent history, oldest first for charting */
    public Map<String, Object> getMetrics(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        Double bmi = null;
        String category = null;
        if (user.getWeight() != null && user.getHeight() != null && user.getHeight() > 0) {
            double hm = user.getHeight() / 100.0;
            bmi = Math.round((user.getWeight() / (hm * hm)) * 10.0) / 10.0;
            category = bmiCategory(bmi);
        }

        Map<String, Object> result = buildResponse(user, bmi, category);

        // Newest N, then flipped: the chart reads left to right in time.
        Page<BodyMetricsLog> recent = bodyMetricsLogRepository.findByUserId(userId,
                PageRequest.of(0, EMBEDDED_HISTORY_LIMIT, Sort.by(Sort.Direction.DESC, "loggedAt")));
        List<Map<String, Object>> history = recent.getContent().stream()
                .map(BodyMetricsService::historyEntry)
                .collect(Collectors.toList());
        java.util.Collections.reverse(history);

        result.put("history", history);
        // Lets a client tell "that's everything" from "that's the latest 100".
        result.put("historyTotal", recent.getTotalElements());
        return result;
    }

    /** One page of weigh-ins, newest first — the whole record, unlike /metrics. */
    public Page<Map<String, Object>> getHistory(String userId, Pageable pageable) {
        return bodyMetricsLogRepository.findByUserId(userId, pageable)
                .map(BodyMetricsService::historyEntry);
    }

    private static Map<String, Object> historyEntry(BodyMetricsLog e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weight", e.getWeight());
        m.put("height", e.getHeight());
        m.put("bmi", e.getBmi());
        m.put("bmiCategory", e.getBmiCategory());
        m.put("loggedAt", e.getLoggedAt());
        return m;
    }

    private Map<String, Object> buildResponse(User user, Double bmi, String category) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", user.getId());
        map.put("weight", user.getWeight());
        map.put("height", user.getHeight());
        map.put("age", user.getAge());
        map.put("bmi", bmi);
        map.put("bmiCategory", category);
        if (bmi != null) {
            map.put("healthyWeightRange", healthyWeightRange(user.getHeight()));
            map.put("bmiNote", bmiNote(category));
        }
        return map;
    }

    private String bmiCategory(double bmi) {
        if (bmi < 18.5) return "Underweight";
        if (bmi < 25.0) return "Normal weight";
        if (bmi < 30.0) return "Overweight";
        return "Obese";
    }

    private String bmiNote(String category) {
        return switch (category) {
            case "Underweight" -> "Consider increasing calorie intake and strength training.";
            case "Normal weight" -> "Great! Maintain your healthy lifestyle.";
            case "Overweight" -> "A mix of cardio and strength training can help reach your target.";
            default -> "Consult a healthcare professional for a personalised plan.";
        };
    }

    private String healthyWeightRange(Double heightCm) {
        if (heightCm == null || heightCm == 0) return "N/A";
        double hm = heightCm / 100.0;
        double low = Math.round(18.5 * hm * hm * 10.0) / 10.0;
        double high = Math.round(24.9 * hm * hm * 10.0) / 10.0;
        return low + " kg – " + high + " kg";
    }
}

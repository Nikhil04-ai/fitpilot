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

        // After the account check, so a dead session still gets its 409. An empty body
        // used to write a weigh-in anyway (PLAN P4-12): a row that measured nothing.
        if (weight == null && height == null) {
            throw new RuntimeException("Send a weight or a height to record.");
        }
        // Same limits as the profile: this writes the same two fields (PLAN P4-17).
        InputChecks.weightKg(weight);
        InputChecks.heightCm(height);

        if (weight != null) user.setWeight(weight);
        if (height != null) user.setHeight(height);
        userRepository.save(user);

        List<String> goalAchievements = recordWeighIn(user);
        Map<String, Object> response = buildResponse(user, bmiOf(user), bmiOf(user) == null ? null : bmiCategory(user));
        if (!goalAchievements.isEmpty()) response.put("goalAchievements", goalAchievements);
        return response;
    }

    /**
     * Writes a weigh-in row from the user's current weight and height, and refreshes the
     * weight goals. Called by PUT /api/health/metrics and by a profile update that changes
     * weight or height. The profile is the only place the app lets a person change their
     * weight, and it used to write no row, so the weight chart, the history and the
     * weight goal never saw it (found 2026-11-16).
     *
     * @return the goal messages a weigh-in produced, empty if none.
     */
    public List<String> recordWeighIn(User user) {
        // Unknown stays null. It was stored as 0, and zero kg is not a measurement: it
        // looked like one in the history, the chart and the export.
        Double bmi = bmiOf(user);
        BodyMetricsLog log = BodyMetricsLog.builder()
                .user(user)
                .weight(user.getWeight())
                .height(user.getHeight())
                .bmi(bmi)
                .bmiCategory(bmi == null ? null : bmiCategory(user))
                .build();
        bodyMetricsLogRepository.save(log);

        // A weigh-in is the only thing that moves a TARGET_WEIGHT goal: those goals are
        // not fed by workouts or by meals (PLAN P2-1).
        return goalService.refreshStateGoals(user);
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

        Double bmi = bmiOf(user);
        Map<String, Object> result = buildResponse(user, bmi, bmi == null ? null : bmiCategory(user));

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

    // ── BMI ─────────────────────────────────────────────────────────────────
    // The category used to be judged from the BMI rounded to 0.1, and the healthy range's
    // top used 24.9 while the category's cut-off is 25. So at the edges one response
    // disagreed with itself: 53.4 kg at 170 cm (BMI 18.48) read "18.5, Normal weight"
    // beside a range starting at 53.5 kg, and 72.1 kg was "Normal" though above the
    // range (found 2026-11-16). Now the category comes from the exact BMI, the figure
    // shown is rounded as usual except where rounding would carry it across a category
    // boundary, and the range's ends are the exact weights where the category changes.

    /** The exact BMI, or null when weight or height is missing. */
    private static Double exactBmi(User user) {
        Double w = user.getWeight(), h = user.getHeight();
        if (w == null || h == null || w <= 0 || h <= 0) return null;
        double hm = h / 100.0;
        return w / (hm * hm);
    }

    /** The category cut-offs: below 18.5, below 25, below 30. */
    private static final double[] BMI_CUTOFFS = {18.5, 25.0, 30.0};

    /**
     * BMI to one decimal, rounded as usual, except that rounding never carries it onto a
     * cut-off it is below: 18.48 shows as 18.4, because "18.5, Underweight" contradicts
     * itself. So the figure and its category always agree.
     */
    static Double bmiOf(User user) {
        Double exact = exactBmi(user);
        if (exact == null) return null;
        double shown = Math.round(exact * 10.0) / 10.0;
        for (double cut : BMI_CUTOFFS) {
            if (exact < cut && shown >= cut) shown = Math.round((cut - 0.1) * 10.0) / 10.0;
        }
        return shown;
    }

    /** The category of the exact BMI. Only called when there is one. */
    static String bmiCategory(User user) {
        double bmi = exactBmi(user);
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

    /**
     * The lightest and heaviest weights, to 0.1 kg, whose BMI is "Normal weight":
     * at least 18.5 and below 25. The small epsilon keeps a weight that lands exactly on
     * the cut-off on the right side of it.
     */
    static String healthyWeightRange(Double heightCm) {
        if (heightCm == null || heightCm <= 0) return "N/A";
        double hm2 = Math.pow(heightCm / 100.0, 2);
        double low = Math.ceil(18.5 * hm2 * 10.0 - 1e-9) / 10.0;
        double high = Math.floor(25.0 * hm2 * 10.0 - 1e-9) / 10.0;
        return low + " kg – " + high + " kg";
    }
}

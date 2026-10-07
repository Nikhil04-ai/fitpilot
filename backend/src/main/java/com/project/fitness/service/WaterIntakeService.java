package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.User;
import com.project.fitness.model.WaterLog;
import com.project.fitness.repository.UserRepository;
import com.project.fitness.repository.WaterLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Transactional
@Service
@RequiredArgsConstructor
public class WaterIntakeService {

    private final WaterLogRepository waterLogRepository;
    private final UserRepository userRepository;

    // Recommended daily intake in ml — 35 ml per kg body weight, minimum 2000 ml
    private static final double ML_PER_KG = 35.0;
    private static final double MIN_DAILY_ML = 2000.0;

    public Map<String, Object> logWater(String userId, double amountMl, LocalDate date) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        // A body with no amountMl arrives as 0 and used to be saved as a drink of 0 ml;
        // a negative one pulled the day's total down (PLAN P4-17).
        InputChecks.waterMl(amountMl);

        LocalDate logDate = date != null ? date : LocalDate.now();
        WaterLog log = WaterLog.builder()
                .user(user).amountMl(amountMl).logDate(logDate).build();
        waterLogRepository.save(log);

        return getDailySummary(userId, logDate, user);
    }

    public Map<String, Object> getDailySummary(String userId, LocalDate date) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));
        return getDailySummary(userId, date, user);
    }

    private Map<String, Object> getDailySummary(String userId, LocalDate date, User user) {
        double total = waterLogRepository.sumByUserIdAndDate(userId, date);
        double goal = user.getWeight() != null
                ? Math.max(MIN_DAILY_ML, user.getWeight() * ML_PER_KG)
                : MIN_DAILY_ML;

        double percent = Math.min(100.0, (total / goal) * 100);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("date", date);
        map.put("totalMl", total);
        map.put("goalMl", goal);
        map.put("progressPercent", Math.round(percent * 10.0) / 10.0);
        map.put("remainingMl", Math.max(0, goal - total));
        map.put("goalMet", total >= goal);
        map.put("status", hydrationStatus(percent));
        return map;
    }

    public List<Map<String, Object>> getWeeklyHistory(String userId) {
        LocalDate today = LocalDate.now();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));
        double goal = user.getWeight() != null
                ? Math.max(MIN_DAILY_ML, user.getWeight() * ML_PER_KG) : MIN_DAILY_ML;

        return waterLogRepository
                .findByUserIdAndLogDateBetweenOrderByLogDateAsc(userId, today.minusDays(6), today)
                .stream()
                .collect(Collectors.groupingBy(WaterLog::getLogDate,
                        Collectors.summingDouble(WaterLog::getAmountMl)))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", e.getKey());
                    m.put("totalMl", e.getValue());
                    m.put("goalMl", goal);
                    m.put("goalMet", e.getValue() >= goal);
                    return m;
                }).collect(Collectors.toList());
    }

    private String hydrationStatus(double percent) {
        if (percent >= 100) return "Fully hydrated! Great work.";
        if (percent >= 75)  return "Almost there — keep sipping!";
        if (percent >= 50)  return "Halfway — don't forget to drink.";
        if (percent >= 25)  return "Low — try to drink more water.";
        return "Very low — drink water now!";
    }
}

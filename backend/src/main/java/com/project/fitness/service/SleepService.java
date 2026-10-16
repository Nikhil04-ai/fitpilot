package com.project.fitness.service;

import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Activity;
import com.project.fitness.model.SleepLog;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.SleepLogRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Transactional
@Service
@RequiredArgsConstructor
public class SleepService {

    private final SleepLogRepository sleepLogRepository;
    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;

    public SleepLog logSleep(String userId, LocalDate sleepDate, double hoursSlept,
                             int qualityRating, LocalDateTime bedtime, LocalDateTime wakeTime, String notes) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        InputChecks.sleepHours(hoursSlept);
        InputChecks.sleepQuality(qualityRating);   // was clamped: a missing rating was saved as 1

        LocalDate date = sleepDate != null ? sleepDate : LocalDate.now().minusDays(1);

        // A night that hasn't happened yet was accepted (2099 included), as was waking
        // before going to bed. A day of slack, because the server's "today" can be the
        // day before the person's own.
        if (date.isAfter(LocalDate.now().plusDays(1))) {
            throw new RuntimeException("Sleep can't be logged for a night that hasn't happened yet.");
        }
        if (bedtime != null && wakeTime != null && !wakeTime.isAfter(bedtime)) {
            throw new RuntimeException("Wake time must be after bedtime.");
        }

        // Upsert — one log per day
        SleepLog log = sleepLogRepository.findByUserIdAndSleepDate(userId, date)
                .orElse(SleepLog.builder().user(user).sleepDate(date).build());

        log.setHoursSlept(hoursSlept);
        log.setQualityRating(qualityRating);
        log.setBedtime(bedtime);
        log.setWakeTime(wakeTime);
        log.setNotes(notes);

        return sleepLogRepository.save(log);
    }

    public Map<String, Object> getWeeklySleepAnalysis(String userId) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(6);

        Double avgHours = sleepLogRepository.avgHoursByUserIdAndDateRange(userId, from, today);
        Double avgQuality = sleepLogRepository.avgQualityByUserIdAndDateRange(userId, from, today);

        List<Map<String, Object>> history = sleepLogRepository
                .findByUserIdAndSleepDateBetweenOrderBySleepDateAsc(userId, from, today)
                .stream().map(this::toMap).collect(Collectors.toList());

        // Correlate sleep quality with workout performance
        List<String> insights = generateInsights(userId, from, today, avgHours, avgQuality);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("weekAvgHours", avgHours != null ? Math.round(avgHours * 10.0) / 10.0 : 0);
        result.put("weekAvgQuality", avgQuality != null ? Math.round(avgQuality * 10.0) / 10.0 : 0);
        result.put("recommendation", sleepRecommendation(avgHours));
        result.put("history", history);
        result.put("insights", insights);
        return result;
    }

    /**
     * Each logged night paired with the training that followed it — the scatter plot
     * behind the "sleep affects performance" claim the insights already make in prose.
     *
     * <p>A SleepLog's {@code sleepDate} is the evening the person went to bed, so the
     * workout it could plausibly affect is the <em>next</em> day's. Pairing a night
     * with the same calendar date would line every point up against the session that
     * happened before the sleep.
     *
     * <p>Rest days are returned as points with zero calories so the chart shows them,
     * but they are excluded from the correlation: a week of good sleep and no training
     * is not evidence that sleep hurts performance, and leaving them in drags the
     * coefficient toward whatever the rest-day rate happens to be.
     */
    public Map<String, Object> getSleepPerformance(String userId, int days) {
        userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        int span = Math.max(1, Math.min(days, 365));
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(span - 1L);

        Map<LocalDate, int[]> byDay = new HashMap<>();   // workout date -> [calories, minutes]
        for (Activity a : activityRepository.findByUserIdAndStartTimeBetween(
                userId, from.atStartOfDay(), today.plusDays(1).atTime(LocalTime.MAX))) {
            if (a.getStartTime() == null) continue;
            int[] bucket = byDay.computeIfAbsent(a.getStartTime().toLocalDate(), d -> new int[2]);
            if (a.getCaloriesBurned() != null) bucket[0] += a.getCaloriesBurned();
            if (a.getDuration() != null)       bucket[1] += a.getDuration();
        }

        List<Map<String, Object>> points = new ArrayList<>();
        List<double[]> trained = new ArrayList<>();   // [hoursSlept, calories] for trained days only

        for (SleepLog log : sleepLogRepository
                .findByUserIdAndSleepDateBetweenOrderBySleepDateAsc(userId, from, today)) {
            if (log.getHoursSlept() == null) continue;

            LocalDate workoutDate = log.getSleepDate().plusDays(1);
            int[] b = byDay.get(workoutDate);
            int calories = b != null ? b[0] : 0;
            int minutes  = b != null ? b[1] : 0;

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("sleepDate", log.getSleepDate().toString());
            point.put("workoutDate", workoutDate.toString());
            point.put("hoursSlept", log.getHoursSlept());
            point.put("qualityRating", log.getQualityRating());
            point.put("calories", calories);
            point.put("minutes", minutes);
            point.put("trained", b != null);
            points.add(point);

            if (b != null) trained.add(new double[]{log.getHoursSlept(), calories});
        }

        Double correlation = pearson(trained);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("points", points);
        result.put("pairedDays", trained.size());
        result.put("correlation", correlation);
        result.put("summary", correlationSummary(correlation, trained.size()));
        return result;
    }

    /**
     * Pearson's r between hours slept and calories burned the following day.
     *
     * <p>Null below four paired days, and null when either side has no spread at all —
     * the formula divides by the standard deviations, and "every night was 8 hours"
     * has nothing to correlate against.
     */
    private Double pearson(List<double[]> pairs) {
        int n = pairs.size();
        if (n < 4) return null;

        double sumX = 0, sumY = 0;
        for (double[] p : pairs) { sumX += p[0]; sumY += p[1]; }
        double meanX = sumX / n, meanY = sumY / n;

        double cov = 0, varX = 0, varY = 0;
        for (double[] p : pairs) {
            double dx = p[0] - meanX, dy = p[1] - meanY;
            cov  += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        if (varX == 0 || varY == 0) return null;

        double r = cov / Math.sqrt(varX * varY);
        return Math.round(r * 100.0) / 100.0;
    }

    private String correlationSummary(Double r, int pairedDays) {
        if (r == null) {
            return pairedDays < 4
                    ? "Log sleep on at least 4 days you also train to see whether it moves your numbers."
                    : "Not enough variation in your sleep or your sessions yet to read a trend.";
        }
        String strength = Math.abs(r) >= 0.5 ? "a clear" : Math.abs(r) >= 0.25 ? "a mild" : "almost no";
        if (Math.abs(r) < 0.25) {
            return String.format("Across %d training days there is %s link between how long you slept and what you burned (r = %.2f).",
                    pairedDays, strength, r);
        }
        return r > 0
                ? String.format("Across %d training days, more sleep goes with harder sessions — %s positive link (r = %.2f).",
                    pairedDays, strength, r)
                : String.format("Across %d training days, more sleep goes with easier sessions — %s negative link (r = %.2f). Worth a second look before acting on it.",
                    pairedDays, strength, r);
    }

    /** One page of sleep history (PLAN P3-3). */
    public Page<SleepLog> getHistory(String userId, Pageable pageable) {
        return sleepLogRepository.findByUserId(userId, pageable);
    }

    private List<String> generateInsights(String userId, LocalDate from, LocalDate today,
                                          Double avgHours, Double avgQuality) {
        List<String> insights = new java.util.ArrayList<>();

        if (avgHours == null) return insights;

        if (avgHours < 6.0) {
            insights.add("You're averaging under 6 hours of sleep. This directly reduces muscle recovery and workout performance.");
        } else if (avgHours >= 7.0 && avgHours <= 9.0) {
            insights.add("Your sleep duration is in the optimal 7–9 hour range for athletic recovery.");
        }

        if (avgQuality != null && avgQuality < 3.0) {
            insights.add("Your sleep quality has been low this week. Poor sleep raises cortisol, which can increase fat storage and reduce strength.");
        }

        // Check if workout days correlate with worse sleep
        int activeDays = activityRepository
                .findByUserIdAndStartTimeBetween(userId, from.atStartOfDay(), today.atTime(23, 59))
                .stream().map(a -> a.getStartTime().toLocalDate()).collect(Collectors.toSet()).size();

        if (activeDays >= 5 && avgHours < 7.0) {
            insights.add("You worked out " + activeDays + " days this week but slept under 7 hours. High training load needs more recovery sleep.");
        }

        return insights;
    }

    private String sleepRecommendation(Double avgHours) {
        if (avgHours == null) return "Start logging your sleep to get personalised recommendations.";
        if (avgHours < 6) return "Critical: aim for at least 7 hours. Sleep deprivation severely impacts recovery.";
        if (avgHours < 7) return "Try to get one more hour per night to reach the recommended 7–9 hours.";
        if (avgHours <= 9) return "Excellent sleep duration! Keep maintaining this routine.";
        return "Over 9 hours consistently can indicate fatigue or health issues — consider checking in with a doctor.";
    }

    private Map<String, Object> toMap(SleepLog s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sleepDate", s.getSleepDate());
        m.put("hoursSlept", s.getHoursSlept());
        m.put("qualityRating", s.getQualityRating());
        m.put("bedtime", s.getBedtime());
        m.put("wakeTime", s.getWakeTime());
        m.put("notes", s.getNotes());
        return m;
    }
}

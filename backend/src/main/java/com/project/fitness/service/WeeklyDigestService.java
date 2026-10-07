package com.project.fitness.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.WeeklyDigestResponse;
import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.*;
import com.project.fitness.model.NotificationType;
import com.project.fitness.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * The weekly digest (PLAN P2-3).
 *
 * <p>Gemini was called from exactly two places, and the coaching call was fed age,
 * weight, the last five activities and a streak number — nothing else. Everything the
 * app knows about sleep, pain, recovery, timing, body weight, per-movement load and
 * (since P2-1) intake was computed by rule-based services, rendered to the screen, and
 * never reached the model. This reasons over all of it at once, which is the only place
 * a statement that crosses two signals can come from.
 *
 * <p><b>The division of labour is deliberate and load-bearing:</b>
 *
 * <ul>
 *   <li><b>The facts are computed here</b>, from the rows, every time — and stored on
 *       the digest. The model is handed them and asked to interpret; it is never asked
 *       to supply a number. A hallucinated figure then has nowhere to hide, because the
 *       digest carries the real ones next to the prose.</li>
 *   <li><b>The fallback is a real digest, not an apology.</b> With no API key the facts
 *       and the rule-based observations still stand on their own, and
 *       {@code aiGenerated} is false so the reader knows which they are looking at.
 *       Degrading to "AI unavailable" would throw away work that was already done.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyDigestService {

    private final WeeklyDigestRepository digestRepository;
    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final ExerciseRepository exerciseRepository;
    private final MealLogRepository mealLogRepository;
    private final SleepLogRepository sleepLogRepository;
    private final PainLogRepository painLogRepository;
    private final BodyMetricsLogRepository bodyMetricsLogRepository;
    private final GoalRepository goalRepository;
    private final CalorieCalculatorService calorieCalculatorService;
    private final GeminiService geminiService;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    /** A movement whose best estimated 1RM moved less than this is treated as flat. */
    private static final double STALL_THRESHOLD_PERCENT = 1.0;

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Builds (or rebuilds) the digest for a week. Defaults to the week just finished,
     * which is the one worth reading on a Monday morning.
     */
    @Transactional
    public WeeklyDigestResponse generate(String userId, LocalDate weekStart) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        LocalDate week = weekStart != null ? mondayOf(weekStart) : mondayOf(LocalDate.now().minusWeeks(1));
        Map<String, Object> facts = gatherFacts(user, week);

        if (Boolean.FALSE.equals(facts.get("hasAnythingLogged"))) {
            throw new RuntimeException("Nothing was logged in the week of " + week
                    + ", so there is nothing to summarise yet.");
        }

        Narrative narrative = askGemini(user, facts);
        boolean aiGenerated = narrative != null;
        if (!aiGenerated) narrative = ruleBasedNarrative(facts);

        // One row per user per week (unique constraint), so regenerating replaces rather
        // than piling up near-identical digests for the same seven days.
        WeeklyDigest digest = digestRepository.findByUserIdAndWeekStart(userId, week)
                .orElseGet(() -> WeeklyDigest.builder().user(user).weekStart(week).build());

        digest.setHeadline(narrative.headline());
        digest.setNarrative(narrative.narrative());
        digest.setObservations(narrative.observations());
        digest.setFocusNextWeek(narrative.focusNextWeek());
        digest.setDataPoints(facts);
        digest.setAiGenerated(aiGenerated);

        return toResponse(digestRepository.save(digest));
    }

    @Transactional(readOnly = true)
    public WeeklyDigestResponse getLatest(String userId) {
        return digestRepository.findTopByUserIdOrderByWeekStartDesc(userId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<WeeklyDigestResponse> getHistory(String userId) {
        return digestRepository.findByUserIdOrderByWeekStartDesc(userId)
                .stream().map(this::toResponse).toList();
    }

    /**
     * Monday morning, for everyone who actually used the app last week.
     *
     * <p>Scoped to active users because each digest costs a Gemini call against a shared
     * quota — running it for every account that ever registered would spend that quota
     * writing summaries of empty weeks for people who are not coming back. One failure
     * must not stop the rest, so each user is attempted independently.
     */
    @Scheduled(cron = "0 0 8 * * MON")
    public void generateForActiveUsers() {
        LocalDate lastWeek = mondayOf(LocalDate.now().minusWeeks(1));
        LocalDateTime since = lastWeek.atStartOfDay();

        Set<String> userIds = new LinkedHashSet<>(activityRepository.findUserIdsWithActivitySince(since));
        userIds.addAll(mealLogRepository.findUserIdsWithMealsSince(since));

        log.info("Weekly digest: generating for {} active user(s), week of {}", userIds.size(), lastWeek);
        int written = 0;
        for (String userId : userIds) {
            try {
                WeeklyDigestResponse digest = generate(userId, lastWeek);
                deliver(userId, digest);
                written++;
            } catch (Exception e) {
                // Including the "nothing logged" case, which is an ordinary outcome here.
                log.warn("Weekly digest skipped for user {}: {}", userId, e.getMessage());
            }
        }
        log.info("Weekly digest: wrote {} of {}", written, userIds.size());
    }

    /**
     * Tells the user their digest exists (PLAN P2-2).
     *
     * <p>A digest written at 08:00 on Monday that nobody is told about is the exact
     * problem the delivery layer was built for — the feed entry is the point of
     * generating it on a schedule at all. The email is a bonus on top: it only goes out
     * where a mail transport is configured, and never fails the digest if it doesn't.
     */
    private void deliver(String userId, WeeklyDigestResponse digest) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return;

        notificationService.notify(user, NotificationType.WEEKLY_DIGEST,
                "Your week in review",
                digest.getHeadline(),
                "ai",
                "weekly-digest:" + digest.getWeekStart());

        if (!emailService.isConfigured() || user.getEmail() == null) return;
        try {
            emailService.send(user.getEmail(),
                    "FitPilot — your week in review",
                    digestEmailHtml(user, digest));
        } catch (Exception e) {
            // The in-app feed already has it. A bounced email is not worth losing a
            // digest over, and EmailService degrades to a log line anyway.
            log.warn("Weekly digest email failed for user {}: {}", userId, e.toString());
        }
    }

    private String digestEmailHtml(User user, WeeklyDigestResponse digest) {
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:system-ui,sans-serif;max-width:560px;margin:0 auto\">");
        html.append("<h2 style=\"margin-bottom:4px\">")
            .append(escape(digest.getHeadline())).append("</h2>");
        html.append("<p style=\"color:#667;font-size:13px;margin-top:0\">")
            .append(digest.getWeekStart()).append(" &ndash; ").append(digest.getWeekEnd())
            .append("</p>");
        if (digest.getNarrative() != null) {
            html.append("<p>").append(escape(digest.getNarrative())).append("</p>");
        }
        appendList(html, "What the week showed", digest.getObservations());
        appendList(html, "Next week", digest.getFocusNextWeek());
        html.append("<p style=\"color:#889;font-size:12px\">Open FitPilot to see the numbers behind this.</p>");
        html.append("</div>");
        return html.toString();
    }

    private void appendList(StringBuilder html, String heading, List<String> items) {
        if (items == null || items.isEmpty()) return;
        html.append("<h3 style=\"font-size:13px;text-transform:uppercase;letter-spacing:1px;color:#667\">")
            .append(heading).append("</h3><ul>");
        for (String item : items) html.append("<li>").append(escape(item)).append("</li>");
        html.append("</ul>");
    }

    /** The digest is model output; it goes into an HTML email, so it gets escaped. */
    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;");
    }

    // ── Facts ────────────────────────────────────────────────────────────────

    /**
     * Everything the week actually contained, as plain numbers.
     *
     * <p>This is the part that makes the digest trustworthy: each figure is derived from
     * the rows, stored on the digest, and returned to the client alongside the prose.
     */
    private Map<String, Object> gatherFacts(User user, LocalDate weekStart) {
        String userId = user.getId();
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDateTime from = weekStart.atStartOfDay();
        LocalDateTime to = weekEnd.atTime(LocalTime.MAX);
        LocalDateTime priorFrom = weekStart.minusWeeks(1).atStartOfDay();

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("weekStart", weekStart.toString());
        facts.put("weekEnd", weekEnd.toString());

        // ── Training ──
        List<Activity> activities = activityRepository.findByUserIdAndStartTimeBetween(userId, from, to);
        List<Activity> priorActivities = activityRepository
                .findByUserIdAndStartTimeBetween(userId, priorFrom, from.minusNanos(1));

        int minutes = 0, calories = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        Set<LocalDate> trainingDays = new HashSet<>();
        double volume = 0;
        for (Activity a : activities) {
            if (a.getDuration() != null) minutes += a.getDuration();
            if (a.getCaloriesBurned() != null) calories += a.getCaloriesBurned();
            if (a.getType() != null) byType.merge(a.getType().name(), 1, Integer::sum);
            if (a.getStartTime() != null) trainingDays.add(a.getStartTime().toLocalDate());
            Double v = a.totalVolume();
            if (v != null) volume += v;
        }

        Map<String, Object> training = new LinkedHashMap<>();
        training.put("sessions", activities.size());
        training.put("daysTrained", trainingDays.size());
        training.put("minutes", minutes);
        training.put("calories", calories);
        training.put("volumeKg", round1(volume));
        training.put("byType", byType);
        training.put("sessionsPreviousWeek", priorActivities.size());
        training.put("minutesPreviousWeek", priorActivities.stream()
                .filter(a -> a.getDuration() != null).mapToInt(Activity::getDuration).sum());
        training.put("minutesChangePercent",
                percentChange((Integer) training.get("minutesPreviousWeek"), minutes));
        facts.put("training", training);

        // ── Strength ──
        facts.put("strength", strengthFacts(userId, from, to));

        // ── Nutrition ──
        List<MealLog> meals = mealLogRepository
                .findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(userId, from, to);
        Map<String, Object> nutrition = new LinkedHashMap<>();
        if (meals.isEmpty()) {
            nutrition.put("daysLogged", 0);
        } else {
            Map<LocalDate, int[]> byDay = new HashMap<>();   // [calories, protein*10]
            for (MealLog m : meals) {
                if (m.getLoggedAt() == null) continue;
                int[] bucket = byDay.computeIfAbsent(m.getLoggedAt().toLocalDate(), d -> new int[2]);
                if (m.getCalories() != null) bucket[0] += m.getCalories();
                if (m.getProteinGrams() != null) bucket[1] += (int) Math.round(m.getProteinGrams() * 10);
            }
            int days = byDay.size();
            int totalCalories = byDay.values().stream().mapToInt(b -> b[0]).sum();
            int totalProteinTenths = byDay.values().stream().mapToInt(b -> b[1]).sum();

            nutrition.put("daysLogged", days);
            nutrition.put("mealsLogged", meals.size());
            nutrition.put("avgCaloriesPerLoggedDay", days > 0 ? totalCalories / days : 0);
            nutrition.put("avgProteinPerLoggedDay", days > 0 ? round1(totalProteinTenths / 10.0 / days) : 0.0);

            Integer maintenance = calorieCalculatorService.maintenanceCalories(user);
            if (maintenance != null && days > 0) {
                // Averaged over the days that were actually logged, not over seven. A
                // day nobody recorded is missing data, and treating it as a zero-calorie
                // fast would report a starvation week to anyone who skipped logging.
                //
                // The burn is averaged over the same days (PLAN P4-10). It used to be the
                // whole week's training divided by the logged-day count, so a workout on
                // a day with no food logged was charged against the days that had some.
                int burnOnLoggedDays = activities.stream()
                        .filter(a -> a.getStartTime() != null && a.getCaloriesBurned() != null
                                && byDay.containsKey(a.getStartTime().toLocalDate()))
                        .mapToInt(Activity::getCaloriesBurned)
                        .sum();
                int avgBurn = burnOnLoggedDays / days;
                nutrition.put("maintenanceCalories", maintenance);
                nutrition.put("avgNetPerLoggedDay", (totalCalories / days) - maintenance - avgBurn);
            }
        }
        facts.put("nutrition", nutrition);

        // ── Sleep ──
        List<SleepLog> sleep = sleepLogRepository
                .findByUserIdAndSleepDateBetweenOrderBySleepDateAsc(userId, weekStart, weekEnd);
        Map<String, Object> sleepFacts = new LinkedHashMap<>();
        sleepFacts.put("nightsLogged", sleep.size());
        if (!sleep.isEmpty()) {
            sleepFacts.put("avgHours", round1(sleep.stream()
                    .filter(s -> s.getHoursSlept() != null)
                    .mapToDouble(SleepLog::getHoursSlept).average().orElse(0)));
            sleepFacts.put("avgQuality", round1(sleep.stream()
                    .filter(s -> s.getQualityRating() != null)
                    .mapToInt(SleepLog::getQualityRating).average().orElse(0)));
        }
        facts.put("sleep", sleepFacts);

        // ── Body weight ──
        List<BodyMetricsLog> metrics = bodyMetricsLogRepository.findByUserIdOrderByLoggedAtAsc(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        Double weekOpen = null, weekClose = null;
        for (BodyMetricsLog m : metrics) {
            if (m.getLoggedAt() == null || m.getWeight() == null || m.getWeight() <= 0) continue;
            if (m.getLoggedAt().isBefore(from)) weekOpen = m.getWeight();     // last before the week
            else if (!m.getLoggedAt().isAfter(to)) {
                if (weekOpen == null) weekOpen = m.getWeight();
                weekClose = m.getWeight();
            }
        }
        if (weekClose != null) {
            body.put("weightAtWeekEnd", weekClose);
            if (weekOpen != null) body.put("changeKg", round1(weekClose - weekOpen));
        }
        facts.put("bodyWeight", body);

        // ── Pain ──
        List<PainLog> pain = painLogRepository.findByUserId(userId).stream()
                .filter(p -> p.getLoggedAt() != null
                        && !p.getLoggedAt().isBefore(from) && !p.getLoggedAt().isAfter(to))
                .toList();
        Map<String, Object> painFacts = new LinkedHashMap<>();
        painFacts.put("entries", pain.size());
        if (!pain.isEmpty()) {
            painFacts.put("maxSeverity", pain.stream()
                    .filter(p -> p.getSeverity() != null).mapToInt(PainLog::getSeverity).max().orElse(0));
            painFacts.put("bodyParts", pain.stream()
                    .map(p -> p.getBodyPart() != null ? p.getBodyPart().name() : "OTHER")
                    .distinct().toList());
        }
        facts.put("pain", painFacts);

        // ── Goals ──
        List<Map<String, Object>> goals = new ArrayList<>();
        for (Goal g : goalRepository.findByUserId(userId)) {
            if (g.getType() == null) continue;
            Map<String, Object> goal = new LinkedHashMap<>();
            goal.put("type", g.getType().name());
            goal.put("target", g.getTargetValue());
            goal.put("current", g.getCurrentValue());
            goal.put("unit", g.getType().unit());
            goal.put("completed", Boolean.TRUE.equals(g.getIsCompleted()));
            goals.add(goal);
        }
        facts.put("goals", goals);
        facts.put("currentStreak", user.getCurrentStreak());

        facts.put("hasAnythingLogged",
                !activities.isEmpty() || !meals.isEmpty() || !sleep.isEmpty() || !pain.isEmpty());
        return facts;
    }

    /**
     * Per-movement load trend: what went up, and what has been flat.
     *
     * <p>The same estimated-1RM comparison {@code ProgressionService} makes for a single
     * rated workout, widened to every movement trained in the week. This is the signal
     * the old coaching prompt could never have mentioned, because sets and loads were
     * not in it.
     */
    private Map<String, Object> strengthFacts(String userId, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> strength = new LinkedHashMap<>();

        Set<String> trainedThisWeek = new LinkedHashSet<>();
        for (Exercise e : exerciseRepository.findAllForUserWithActivity(userId)) {
            Activity activity = e.getActivity();
            if (activity == null || activity.getStartTime() == null) continue;
            if (activity.getStartTime().isBefore(from) || activity.getStartTime().isAfter(to)) continue;
            if (e.getNormalisedName() != null) trainedThisWeek.add(e.getNormalisedName());
        }

        List<String> improving = new ArrayList<>();
        List<String> stalled = new ArrayList<>();

        for (String movement : trainedThisWeek) {
            List<Exercise> sets = exerciseRepository.findForUserByName(userId, movement);
            List<Double> bySession = bestOneRepMaxPerSession(sets);
            if (bySession.size() < 2) continue;

            double latest = bySession.get(0);
            double previous = bySession.get(1);
            if (previous <= 0) continue;

            double change = ((latest - previous) / previous) * 100;
            String display = sets.isEmpty() ? movement : sets.get(0).getName();
            if (change > STALL_THRESHOLD_PERCENT) {
                improving.add(display + " +" + round1(change) + "%");
            } else if (change <= 0) {
                stalled.add(display);
            }
        }

        strength.put("movementsTrained", trainedThisWeek.size());
        strength.put("improving", improving);
        strength.put("stalled", stalled);
        return strength;
    }

    /** Best estimated 1RM per workout, newest session first. */
    private List<Double> bestOneRepMaxPerSession(List<Exercise> setsNewestFirst) {
        List<Double> perSession = new ArrayList<>();
        String currentActivity = null;
        double best = 0;

        for (Exercise e : setsNewestFirst) {
            String activityId = e.getActivity() != null ? e.getActivity().getId() : null;
            if (currentActivity != null && !currentActivity.equals(activityId)) {
                if (best > 0) perSession.add(best);
                best = 0;
            }
            currentActivity = activityId;
            Double oneRm = e.estimatedOneRepMax();
            if (oneRm != null && oneRm > best) best = oneRm;
        }
        if (best > 0) perSession.add(best);
        return perSession;
    }

    // ── Narrative ────────────────────────────────────────────────────────────

    /** The four things a digest says, whoever wrote them. */
    private record Narrative(String headline, String narrative,
                             List<String> observations, List<String> focusNextWeek) { }

    /**
     * Asks the model to interpret the facts. Returns null whenever it cannot — no key,
     * a blocked prompt, an unparseable reply — and the caller falls back.
     */
    @SuppressWarnings("unchecked")
    private Narrative askGemini(User user, Map<String, Object> facts) {
        if (!geminiService.isConfigured()) return null;

        String factsJson;
        try {
            factsJson = objectMapper.writeValueAsString(facts);
        } catch (Exception e) {
            log.error("Could not serialise digest facts: {}", e.getMessage());
            return null;
        }

        String prompt = """
                You are a strength and conditioning coach writing a weekly review for one
                athlete. Below is everything their training app recorded for the week, as
                JSON. Reply with JSON only, no explanation.

                Athlete: %s years old, %s kg.
                Week data: %s

                Return exactly this structure:
                {
                  "headline": "one sentence, the single thing to take away",
                  "narrative": "2-4 sentences connecting the numbers to each other",
                  "observations": ["what the data showed", "..."],
                  "focusNextWeek": ["what to do about it", "..."]
                }

                Rules:
                - Use ONLY the numbers given. Never invent a figure, a date or a trend
                  that is not in the data.
                - Where two signals plausibly relate — flat lifts and short sleep, high
                  training volume and a new pain entry, a calorie deficit and a stalled
                  lift — say so, and say it is a possible explanation rather than a fact.
                - Say plainly when something was not logged. Do not treat missing data as
                  a zero: "you logged three nights" is useful, "you only slept three
                  nights" is wrong.
                - A pain entry of severity 4 or 5 is the most important thing in the week.
                - 2 to 4 observations, 2 to 3 focus points. No preamble, no sign-off.
                """.formatted(
                        user.getAge() != null ? user.getAge() : "unknown",
                        user.getWeight() != null ? user.getWeight() : "unknown",
                        factsJson);

        Map<String, Object> response = geminiService.generateRecommendation(prompt);
        if (response == null) return null;

        String headline = text(response.get("headline"));
        String narrative = text(response.get("narrative"));
        if (headline == null && narrative == null) return null;

        return new Narrative(
                headline != null ? headline : "Your week in training",
                narrative != null ? narrative : "",
                stringList(response.get("observations")),
                stringList(response.get("focusNextWeek")));
    }

    /**
     * The digest with no model behind it.
     *
     * <p>Deliberately still a digest. The facts were computed either way, and a week's
     * numbers stated plainly are worth more than a message explaining that an API key is
     * missing — which is all this used to be able to say.
     */
    @SuppressWarnings("unchecked")
    private Narrative ruleBasedNarrative(Map<String, Object> facts) {
        Map<String, Object> training = (Map<String, Object>) facts.get("training");
        Map<String, Object> nutrition = (Map<String, Object>) facts.get("nutrition");
        Map<String, Object> sleep = (Map<String, Object>) facts.get("sleep");
        Map<String, Object> strength = (Map<String, Object>) facts.get("strength");
        Map<String, Object> pain = (Map<String, Object>) facts.get("pain");
        Map<String, Object> body = (Map<String, Object>) facts.get("bodyWeight");

        int sessions = intOf(training.get("sessions"));
        int minutes = intOf(training.get("minutes"));
        Double minutesChange = (Double) training.get("minutesChangePercent");

        List<String> observations = new ArrayList<>();
        List<String> focus = new ArrayList<>();

        if (minutesChange != null && sessions > 0) {
            observations.add(minutesChange >= 0
                    ? "Training time was up " + round1(minutesChange) + "% on the week before."
                    : "Training time was down " + round1(-minutesChange) + "% on the week before.");
        }

        List<String> stalled = (List<String>) strength.get("stalled");
        List<String> improving = (List<String>) strength.get("improving");
        if (improving != null && !improving.isEmpty()) {
            observations.add("Estimated 1RM went up on: " + String.join(", ", improving) + ".");
        }
        if (stalled != null && !stalled.isEmpty()) {
            observations.add("Flat or down since last session: " + String.join(", ", stalled) + ".");
            focus.add("Add a set or a small load increase to " + stalled.get(0) + ".");
        }

        int nights = intOf(sleep.get("nightsLogged"));
        if (nights > 0) {
            double avgHours = doubleOf(sleep.get("avgHours"));
            observations.add("Slept " + avgHours + "h on average across " + nights
                    + " logged night" + (nights == 1 ? "" : "s") + ".");
            if (avgHours > 0 && avgHours < 7) {
                focus.add("Sleep averaged under 7 hours — the cheapest thing to fix before adding volume.");
            }
        }

        int nutritionDays = intOf(nutrition.get("daysLogged"));
        if (nutritionDays > 0) {
            observations.add("Logged food on " + nutritionDays + " of 7 days, averaging "
                    + intOf(nutrition.get("avgCaloriesPerLoggedDay")) + " kcal and "
                    + nutrition.get("avgProteinPerLoggedDay") + "g protein.");
        }

        if (body != null && body.get("changeKg") != null) {
            double change = doubleOf(body.get("changeKg"));
            if (Math.abs(change) >= 0.1) {
                observations.add("Body weight " + (change < 0 ? "down " : "up ")
                        + Math.abs(change) + " kg over the week.");
            }
        }

        int painEntries = intOf(pain.get("entries"));
        if (painEntries > 0) {
            int severity = intOf(pain.get("maxSeverity"));
            observations.add(painEntries + " pain entr" + (painEntries == 1 ? "y" : "ies")
                    + " logged, worst severity " + severity + "/5.");
            if (severity >= 4) {
                focus.add("Severity " + severity + " pain was logged — back off the area that hurts before training it again.");
            }
        }

        if (focus.isEmpty()) {
            focus.add(sessions == 0
                    ? "Nothing was trained this week — one session back is the whole goal."
                    : "Keep the same schedule and add one small progression.");
        }

        String headline = sessions == 0
                ? "No training logged this week"
                : sessions + " session" + (sessions == 1 ? "" : "s") + ", " + minutes + " minutes trained";

        String narrative = "A summary of what you logged between "
                + facts.get("weekStart") + " and " + facts.get("weekEnd")
                + (geminiService.isConfigured()
                    ? ". The AI coach couldn't be reached this time, so these are your own "
                      + "numbers, listed rather than interpreted."
                    : ". These are your own numbers — set GEMINI_API_KEY to have them "
                      + "interpreted as well as listed.");

        return new Narrative(headline, narrative, observations, focus);
    }

    // ── Mapping and helpers ──────────────────────────────────────────────────

    private WeeklyDigestResponse toResponse(WeeklyDigest digest) {
        WeeklyDigestResponse r = new WeeklyDigestResponse();
        r.setId(digest.getId());
        r.setWeekStart(digest.getWeekStart());
        r.setWeekEnd(digest.getWeekStart() != null ? digest.getWeekStart().plusDays(6) : null);
        r.setHeadline(digest.getHeadline());
        r.setNarrative(digest.getNarrative());
        r.setObservations(digest.getObservations());
        r.setFocusNextWeek(digest.getFocusNextWeek());
        r.setDataPoints(digest.getDataPoints());
        r.setAiGenerated(Boolean.TRUE.equals(digest.getAiGenerated()));
        r.setCreatedAt(digest.getCreatedAt());
        return r;
    }

    private LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Null rather than 0 when there is no baseline — "up 100%" from nothing is noise. */
    private Double percentChange(Integer previous, int current) {
        if (previous == null || previous == 0) return null;
        return round1(((current - (double) previous) / previous) * 100);
    }

    private String text(Object value) {
        if (!(value instanceof String s)) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String s = text(item);
            if (s != null) out.add(s);
        }
        return out;
    }

    private int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private double doubleOf(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}

package com.project.fitness.service;

import com.project.fitness.dto.MealRequest;
import com.project.fitness.dto.MealResponse;
import com.project.fitness.exceptions.AccountNotFoundException;
import com.project.fitness.model.Activity;
import com.project.fitness.model.MealLog;
import com.project.fitness.model.MealType;
import com.project.fitness.model.User;
import com.project.fitness.repository.ActivityRepository;
import com.project.fitness.repository.MealLogRepository;
import com.project.fitness.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Nutrition and calorie intake (PLAN P2-1).
 *
 * <p>The half of the energy equation the app never had. Until this existed FitPilot
 * could tell you what you burned and had no idea what you ate, which is why no goal
 * could ever be about body weight — the thing most people open a fitness app for.
 *
 * <p><b>On honesty.</b> Two numbers here are estimates and both are labelled as such in
 * the response rather than quietly presented as fact:
 *
 * <ul>
 *   <li>Parsed calories. "Two eggs and toast" becomes a number by asking a language
 *       model, and that number is a guess. {@code MealLog.aiEstimated} records it so
 *       the UI can mark it and the user can correct it.</li>
 *   <li>Maintenance. Mifflin-St Jeor with no sex on the profile, so it carries roughly
 *       ±80 kcal of built-in error — see CalorieCalculatorService. The daily summary
 *       returns the components separately instead of only a single net figure, so the
 *       estimated part is visible rather than buried inside a number that looks exact.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NutritionService {

    private final MealLogRepository mealLogRepository;
    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final CalorieCalculatorService calorieCalculatorService;
    private final GoalService goalService;
    private final GeminiService geminiService;

    /** How many days a series may span, so a hand-edited query can't ask for everything. */
    private static final int MAX_SERIES_DAYS = 365;

    // ── Writes ───────────────────────────────────────────────────────────────

    @Transactional
    public MealResponse logMeal(String userId, MealRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        MealLog meal = MealLog.builder()
                .user(user)
                .description(trimmed(request.getDescription()))
                .mealType(request.getMealType() != null ? request.getMealType() : MealType.OTHER)
                .calories(request.getCalories())
                .proteinGrams(request.getProteinGrams())
                .carbsGrams(request.getCarbsGrams())
                .fatGrams(request.getFatGrams())
                .loggedAt(request.getLoggedAt() != null ? request.getLoggedAt() : LocalDateTime.now())
                .aiEstimated(false)
                .build();

        fillCaloriesFromMacros(meal);
        requireSomethingToRecord(meal);

        MealLog saved = mealLogRepository.save(meal);

        MealResponse response = mapToResponse(saved, user);
        response.setGoalAchievements(emptyToNull(goalService.refreshNutritionGoals(user)));
        return response;
    }

    /**
     * Logs a meal from a free-text description by asking Gemini for the numbers.
     *
     * <p>Degrades the same way natural-language workout logging does: with no API key the
     * call is refused with a message naming the fix, rather than failing as a 500 or —
     * worse — inventing a calorie count locally.
     */
    @Transactional
    public MealResponse parseAndLog(String userId, String description) {
        if (description == null || description.isBlank()) {
            throw new RuntimeException("Describe what you ate.");
        }

        Map<String, Object> parsed = geminiService.parseMeal(description);
        if (parsed == null) {
            // A missing key and a failed call are different problems. Telling someone
            // whose key is set to "set GEMINI_API_KEY" is what hid a retired model.
            throw new RuntimeException(geminiService.isConfigured()
                    ? "Couldn't estimate that meal — the AI service didn't respond. "
                      + "Try again in a moment, or enter it manually."
                    : "Meal parsing isn't set up on this server (no GEMINI_API_KEY) — "
                      + "enter the meal manually.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        MealLog meal = MealLog.builder()
                .user(user)
                .description(trimmed(description))
                .mealType(mealTypeOf(parsed.get("mealType")))
                .calories(intOrNull(parsed.get("calories")))
                .proteinGrams(doubleOrNull(parsed.get("proteinGrams")))
                .carbsGrams(doubleOrNull(parsed.get("carbsGrams")))
                .fatGrams(doubleOrNull(parsed.get("fatGrams")))
                .loggedAt(LocalDateTime.now())
                // The whole point of this flag: these numbers were guessed from a
                // sentence, and the app should never show them as if they were read
                // off a label.
                .aiEstimated(true)
                .build();

        fillCaloriesFromMacros(meal);

        if (meal.getCalories() == null) {
            throw new RuntimeException(
                    "Couldn't work out the calories for that. Try naming the quantity, "
                    + "or enter the meal manually.");
        }

        MealLog saved = mealLogRepository.save(meal);

        MealResponse response = mapToResponse(saved, user);
        response.setGoalAchievements(emptyToNull(goalService.refreshNutritionGoals(user)));
        response.setParseNote("Estimated from your description — tap to correct it.");
        return response;
    }

    /** Fields an edit may explicitly empty through {@link MealRequest#getClear()}. */
    private static final Set<String> CLEARABLE =
            Set.of("description", "calories", "proteinGrams", "carbsGrams", "fatGrams");

    /**
     * Edits a meal. Omitted fields keep their current value, the same partial-update
     * contract the activity endpoint uses; a field named in {@code clear} is emptied.
     */
    @Transactional
    public MealResponse updateMeal(String requestingUserId, String mealId, MealRequest request) {
        MealLog meal = loadOwned(mealId, requestingUserId);
        User user = meal.getUser();

        Set<String> clear = request.getClear() == null ? Set.of() : request.getClear();
        for (String field : clear) {
            if (!CLEARABLE.contains(field)) {
                throw new RuntimeException("Can't clear '" + field + "' on a meal.");
            }
        }

        if (request.getDescription() != null) meal.setDescription(trimmed(request.getDescription()));
        if (request.getMealType() != null)    meal.setMealType(request.getMealType());
        if (request.getLoggedAt() != null)    meal.setLoggedAt(request.getLoggedAt());
        if (clear.contains("description"))    meal.setDescription(null);

        // Compared, not merely present: the edit form sends every number back, so
        // "present" was true on every save and renaming an estimated meal un-flagged it.
        boolean changedNumbers = false;
        changedNumbers |= apply(request.getCalories(), clear.contains("calories"), meal.getCalories(), meal::setCalories);
        changedNumbers |= apply(request.getProteinGrams(), clear.contains("proteinGrams"), meal.getProteinGrams(), meal::setProteinGrams);
        changedNumbers |= apply(request.getCarbsGrams(), clear.contains("carbsGrams"), meal.getCarbsGrams(), meal::setCarbsGrams);
        changedNumbers |= apply(request.getFatGrams(), clear.contains("fatGrams"), meal.getFatGrams(), meal::setFatGrams);

        // Correcting an estimate makes it no longer an estimate. Leaving the flag set
        // would keep flagging a number the user typed themselves as a guess.
        if (changedNumbers) meal.setAiEstimated(false);

        requireSomethingToRecord(meal);
        mealLogRepository.save(meal);

        MealResponse response = mapToResponse(meal, user);
        response.setGoalAchievements(emptyToNull(goalService.refreshNutritionGoals(user)));
        return response;
    }

    @Transactional
    public void deleteMeal(String requestingUserId, String mealId) {
        MealLog meal = loadOwned(mealId, requestingUserId);
        User user = meal.getUser();

        mealLogRepository.delete(meal);
        mealLogRepository.flush();

        // Nutrition goals are recomputed from the log rather than decremented, so this
        // is all that is needed to make a deleted meal stop counting — see GoalService.
        goalService.refreshNutritionGoals(user);
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * One day, with both sides of the equation.
     *
     * <p>Returns the components rather than a single net number on purpose. "Net
     * calories" is intake minus maintenance minus exercise, and two of those three are
     * estimates; collapsing them into one figure would present a guess with the
     * confidence of a measurement.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getDailySummary(String userId, LocalDate date) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        LocalDate day = date != null ? date : LocalDate.now();
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.atTime(LocalTime.MAX);

        List<MealLog> meals = mealLogRepository
                .findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(userId, from, to);

        int caloriesIn = 0;
        double protein = 0, carbs = 0, fat = 0;
        Map<MealType, Integer> byMealType = new EnumMap<>(MealType.class);
        for (MealLog m : meals) {
            if (m.getCalories() != null) {
                caloriesIn += m.getCalories();
                byMealType.merge(m.getMealType() != null ? m.getMealType() : MealType.OTHER,
                        m.getCalories(), Integer::sum);
            }
            if (m.getProteinGrams() != null) protein += m.getProteinGrams();
            if (m.getCarbsGrams() != null)   carbs += m.getCarbsGrams();
            if (m.getFatGrams() != null)     fat += m.getFatGrams();
        }

        int exerciseBurn = 0;
        for (Activity a : activityRepository.findByUserIdAndStartTimeBetween(userId, from, to)) {
            if (a.getCaloriesBurned() != null) exerciseBurn += a.getCaloriesBurned();
        }

        Integer maintenance = calorieCalculatorService.maintenanceCalories(user);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date", day.toString());
        summary.put("mealsLogged", meals.size());
        summary.put("caloriesIn", caloriesIn);
        summary.put("proteinGrams", round1(protein));
        summary.put("carbsGrams", round1(carbs));
        summary.put("fatGrams", round1(fat));
        summary.put("caloriesBurnedExercise", exerciseBurn);
        summary.put("basalCalories", calorieCalculatorService.basalMetabolicRate(user));
        summary.put("maintenanceCalories", maintenance);

        if (maintenance != null) {
            int net = caloriesIn - maintenance - exerciseBurn;
            summary.put("netCalories", net);
            summary.put("balance", net < 0 ? "DEFICIT" : net > 0 ? "SURPLUS" : "MAINTENANCE");
            summary.put("estimateNote",
                    "Maintenance is estimated from your height, weight and age, and is "
                    + "sex-neutral because your profile doesn't record one — treat it as "
                    + "±100 kcal.");
        } else {
            // Better to say why than to show a confident zero.
            summary.put("netCalories", null);
            summary.put("balance", null);
            summary.put("estimateNote",
                    "Add your weight, height and age in Profile to see whether you're in "
                    + "a deficit or a surplus.");
        }

        Map<String, Integer> mealTypeTotals = new LinkedHashMap<>();
        for (MealType type : MealType.values()) {
            Integer total = byMealType.get(type);
            if (total != null) mealTypeTotals.put(type.name(), total);
        }
        summary.put("byMealType", mealTypeTotals);

        List<MealResponse> mealResponses = new ArrayList<>(meals.size());
        for (MealLog m : meals) mealResponses.add(mapToResponse(m, user));
        summary.put("meals", mealResponses);

        return summary;
    }

    @Transactional(readOnly = true)
    public Page<MealResponse> getHistory(String userId, Pageable pageable) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));
        return mealLogRepository.findByUserId(userId, pageable).map(m -> mapToResponse(m, user));
    }

    /**
     * Intake, burn and net per day, for the chart.
     *
     * <p>Every day in the span is present, including days with nothing logged. Same
     * reasoning as the volume chart (PLAN P1-3): a series that only contains the days
     * you logged draws a blank week as a straight line between two points and makes a
     * gap in the record look like consistency.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDailySeries(String userId, int days) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountNotFoundException(userId));

        int span = Math.max(1, Math.min(days, MAX_SERIES_DAYS));
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(span - 1L);
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = today.atTime(LocalTime.MAX);

        Map<LocalDate, Integer> intakeByDay = new HashMap<>();
        for (MealLog m : mealLogRepository
                .findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(userId, from, to)) {
            if (m.getLoggedAt() == null || m.getCalories() == null) continue;
            intakeByDay.merge(m.getLoggedAt().toLocalDate(), m.getCalories(), Integer::sum);
        }

        Map<LocalDate, Integer> burnByDay = new HashMap<>();
        for (Activity a : activityRepository.findByUserIdAndStartTimeBetween(userId, from, to)) {
            if (a.getStartTime() == null || a.getCaloriesBurned() == null) continue;
            burnByDay.merge(a.getStartTime().toLocalDate(), a.getCaloriesBurned(), Integer::sum);
        }

        Integer maintenance = calorieCalculatorService.maintenanceCalories(user);

        List<Map<String, Object>> series = new ArrayList<>(span);
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            int intake = intakeByDay.getOrDefault(d, 0);
            int burn = burnByDay.getOrDefault(d, 0);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("caloriesIn", intake);
            row.put("caloriesBurnedExercise", burn);
            row.put("maintenanceCalories", maintenance);
            // Null rather than 0 on a day with nothing logged: an unrecorded day is not
            // a 2000 kcal deficit, and plotting it as one would invent a trend.
            row.put("netCalories", (maintenance == null || intake == 0)
                    ? null : intake - maintenance - burn);
            series.add(row);
        }
        return series;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Fills in a missing calorie figure from the macros, 4/4/9 per gram.
     *
     * <p>Only ever fills a gap. An explicit figure always wins — the macros are the
     * rougher number of the two, and silently overwriting what someone typed with a
     * derived value is the kind of thing that makes a log untrustworthy.
     */
    private void fillCaloriesFromMacros(MealLog meal) {
        if (meal.getCalories() != null) return;
        Integer derived = meal.energyFromMacros();
        if (derived != null && derived > 0) meal.setCalories(derived);
    }

    /**
     * One number of an edit: cleared, replaced, or left alone. Returns whether the stored
     * value actually changed — a resubmitted identical figure is not a correction.
     */
    private static <T> boolean apply(T incoming, boolean clear, T current, Consumer<T> setter) {
        T next = clear ? null : (incoming != null ? incoming : current);
        if (Objects.equals(next, current)) return false;
        setter.accept(next);
        return true;
    }

    /** A meal with no calories and no macros records nothing, so it is rejected outright. */
    private void requireSomethingToRecord(MealLog meal) {
        boolean hasEnergy = meal.getCalories() != null && meal.getCalories() > 0;
        boolean hasMacros = meal.getProteinGrams() != null || meal.getCarbsGrams() != null || meal.getFatGrams() != null;
        if (!hasEnergy && !hasMacros) {
            throw new RuntimeException("A meal needs at least a calorie figure or its macros.");
        }
        if (meal.getCalories() != null && meal.getCalories() < 0) {
            throw new RuntimeException("Calories cannot be negative.");
        }
    }

    private MealLog loadOwned(String mealId, String requestingUserId) {
        MealLog meal = mealLogRepository.findById(mealId)
                .orElseThrow(() -> new RuntimeException("Meal not found: " + mealId));
        // A meal id is all the caller sends — without this check anyone could rewrite
        // or delete anyone else's food log (AGENT.md §2).
        if (!meal.getUser().getId().equals(requestingUserId)) {
            throw new AccessDeniedException("You don't have access to this meal.");
        }
        return meal;
    }

    private MealResponse mapToResponse(MealLog meal, User user) {
        MealResponse r = new MealResponse();
        r.setId(meal.getId());
        r.setUserId(user.getId());
        r.setDescription(meal.getDescription());
        r.setMealType(meal.getMealType());
        r.setCalories(meal.getCalories());
        r.setProteinGrams(meal.getProteinGrams());
        r.setCarbsGrams(meal.getCarbsGrams());
        r.setFatGrams(meal.getFatGrams());
        r.setLoggedAt(meal.getLoggedAt());
        r.setCreatedAt(meal.getCreatedAt());
        r.setUpdatedAt(meal.getUpdatedAt());
        r.setAiEstimated(Boolean.TRUE.equals(meal.getAiEstimated()));
        return r;
    }

    private MealType mealTypeOf(Object raw) {
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return MealType.valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                log.warn("Meal parser returned unknown meal type '{}', defaulting to OTHER", text);
            }
        }
        return MealType.OTHER;
    }

    private String trimmed(String value) {
        if (value == null) return null;
        String t = value.trim();
        // The column is 500 chars. Truncating beats a DataIntegrityViolationException
        // for someone who pasted a recipe in.
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    private Integer intOrNull(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Double doubleOrNull(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private <T> List<T> emptyToNull(List<T> list) {
        return list == null || list.isEmpty() ? null : list;
    }
}

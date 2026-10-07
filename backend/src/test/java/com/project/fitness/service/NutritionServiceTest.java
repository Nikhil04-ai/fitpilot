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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NutritionService — PLAN P2-1.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NutritionService Tests")
class NutritionServiceTest {

    @Mock private MealLogRepository mealLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private GoalService goalService;
    @Mock private GeminiService geminiService;

    // Real, not mocked: the maintenance figure is arithmetic under test here, and
    // stubbing it would test the stub rather than the formula every net-calorie
    // number in the app depends on.
    private final CalorieCalculatorService calorieCalculatorService = new CalorieCalculatorService();

    private NutritionService nutritionService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").weight(70.0).height(175.0).age(30).build();
        nutritionService = new NutritionService(mealLogRepository, userRepository,
                activityRepository, calorieCalculatorService, goalService, geminiService);
    }

    private MealRequest request(Integer calories, Double protein, Double carbs, Double fat) {
        MealRequest r = new MealRequest();
        r.setDescription("Porridge and berries");
        r.setMealType(MealType.BREAKFAST);
        r.setCalories(calories);
        r.setProteinGrams(protein);
        r.setCarbsGrams(carbs);
        r.setFatGrams(fat);
        return r;
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Logging a meal")
    class Logging {

        @Test
        @DisplayName("stores the meal against the caller and refreshes nutrition goals")
        void logsAndRefreshesGoals() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(mealLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(goalService.refreshNutritionGoals(user)).thenReturn(List.of());

            MealResponse response = nutritionService.logMeal("user-1", request(420, 12.0, 60.0, 9.0));

            assertThat(response.getCalories()).isEqualTo(420);
            assertThat(response.getUserId()).isEqualTo("user-1");
            assertThat(response.getAiEstimated()).isFalse();
            // Goals are recomputed from the log, so a save has to trigger the refresh
            // or a calorie budget silently stops counting.
            verify(goalService).refreshNutritionGoals(user);
        }

        @Test
        @DisplayName("defaults loggedAt to now, so the meal counts against today")
        void defaultsLoggedAt() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(mealLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            MealResponse response = nutritionService.logMeal("user-1", request(300, null, null, null));

            // Every daily total and the whole chart series bucket on this field. A meal
            // saved without one is invisible to all of them.
            assertThat(response.getLoggedAt()).isNotNull();
            assertThat(response.getLoggedAt().toLocalDate()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("derives calories from macros when none were given (4/4/9)")
        void derivesCaloriesFromMacros() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(mealLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            MealResponse response = nutritionService.logMeal("user-1", request(null, 20.0, 50.0, 10.0));

            assertThat(response.getCalories()).isEqualTo(20 * 4 + 50 * 4 + 10 * 9);
        }

        @Test
        @DisplayName("an explicit calorie figure is never overwritten by the macro estimate")
        void explicitCaloriesWin() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(mealLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            // The macros here imply 370 kcal. The user said 500 — off a packet, most
            // likely, which is the better number. Silently replacing what someone typed
            // with a derived value is how a log stops being trusted.
            MealResponse response = nutritionService.logMeal("user-1", request(500, 20.0, 50.0, 10.0));

            assertThat(response.getCalories()).isEqualTo(500);
        }

        @Test
        @DisplayName("a meal with neither calories nor macros is rejected")
        void emptyMealRejected() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> nutritionService.logMeal("user-1", request(null, null, null, null)))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("at least a calorie figure");
            verify(mealLogRepository, never()).save(any());
        }

        /**
         * The id reaching here always comes from the JWT, so "no such user" means the
         * session outlived the account. It has to be AccountNotFoundException
         * specifically — that is what becomes the 409 the frontend self-heals from.
         */
        @Test
        @DisplayName("a dead session throws AccountNotFoundException, not a plain 400")
        void deadSessionThrowsAccountNotFound() {
            when(userRepository.findById("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> nutritionService.logMeal("ghost", request(300, null, null, null)))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessageContaining("account that no longer exists");
        }
    }

    // ── Natural language ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Natural-language logging")
    class NaturalLanguage {

        @Test
        @DisplayName("marks parsed meals as estimated")
        void marksParsedMealsAsEstimated() {
            when(geminiService.parseMeal(anyString())).thenReturn(Map.of(
                    "mealType", "BREAKFAST", "calories", 320,
                    "proteinGrams", 18, "carbsGrams", 26, "fatGrams", 15));
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(mealLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            MealResponse response = nutritionService.parseAndLog("user-1", "two eggs and toast");

            // The flag is the whole reason a guessed number can be shown at all: without
            // it the UI would present a model's recollection of a portion size with the
            // same authority as a figure read off a packet.
            assertThat(response.getAiEstimated()).isTrue();
            assertThat(response.getCalories()).isEqualTo(320);
            assertThat(response.getMealType()).isEqualTo(MealType.BREAKFAST);
            assertThat(response.getParseNote()).isNotNull();
        }

        @Test
        @DisplayName("falls back to OTHER when the parser invents a meal type")
        void unknownMealTypeFallsBack() {
            when(geminiService.parseMeal(anyString())).thenReturn(Map.of(
                    "mealType", "ELEVENSES", "calories", 150));
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(mealLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            MealResponse response = nutritionService.parseAndLog("user-1", "a biscuit");

            assertThat(response.getMealType()).isEqualTo(MealType.OTHER);
        }

        @Test
        @DisplayName("with no API key, reports that manual entry is the way forward")
        void noKeyReportsUnavailable() {
            when(geminiService.parseMeal(anyString())).thenReturn(null);

            assertThatThrownBy(() -> nutritionService.parseAndLog("user-1", "two eggs"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("enter the meal manually");
            verify(mealLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("a parse with no usable calorie figure is refused rather than saved empty")
        void unusableParseRefused() {
            when(geminiService.parseMeal(anyString())).thenReturn(Map.of("mealType", "LUNCH"));
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> nutritionService.parseAndLog("user-1", "some food"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Couldn't work out the calories");
            verify(mealLogRepository, never()).save(any());
        }
    }

    // ── Editing and ownership ────────────────────────────────────────────────

    @Nested
    @DisplayName("Editing")
    class Editing {

        private MealLog existing() {
            return MealLog.builder()
                    .id("meal-1").user(user).description("Toast")
                    .mealType(MealType.BREAKFAST).calories(200)
                    .loggedAt(LocalDateTime.now()).aiEstimated(true)
                    .build();
        }

        @Test
        @DisplayName("correcting an estimated number clears the estimated flag")
        void correctingClearsEstimatedFlag() {
            MealLog meal = existing();
            when(mealLogRepository.findById("meal-1")).thenReturn(Optional.of(meal));
            when(mealLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            MealRequest edit = new MealRequest();
            edit.setCalories(260);

            MealResponse response = nutritionService.updateMeal("user-1", "meal-1", edit);

            // A number the user typed is not a guess any more, and continuing to badge
            // it as one would be the app doubting input it was just given.
            assertThat(response.getAiEstimated()).isFalse();
            assertThat(response.getCalories()).isEqualTo(260);
        }

        @Test
        @DisplayName("an edit that touches no numbers leaves the estimated flag alone")
        void renamingKeepsEstimatedFlag() {
            MealLog meal = existing();
            when(mealLogRepository.findById("meal-1")).thenReturn(Optional.of(meal));
            when(mealLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            MealRequest edit = new MealRequest();
            edit.setDescription("Toast with jam");

            MealResponse response = nutritionService.updateMeal("user-1", "meal-1", edit);

            assertThat(response.getAiEstimated()).isTrue();
            assertThat(response.getDescription()).isEqualTo("Toast with jam");
            assertThat(response.getCalories()).isEqualTo(200);
        }

        @Test
        @DisplayName("resending the same numbers alongside a rename leaves the estimated flag alone")
        void resubmittedNumbersKeepEstimatedFlag() {
            MealLog meal = existing();
            when(mealLogRepository.findById("meal-1")).thenReturn(Optional.of(meal));
            when(mealLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            // What the edit form actually sends: every field, whether or not it changed.
            MealRequest edit = new MealRequest();
            edit.setDescription("Toast with jam");
            edit.setMealType(MealType.BREAKFAST);
            edit.setCalories(200);

            MealResponse response = nutritionService.updateMeal("user-1", "meal-1", edit);

            assertThat(response.getAiEstimated()).isTrue();
            assertThat(response.getDescription()).isEqualTo("Toast with jam");
        }

        @Test
        @DisplayName("a field named in clear is emptied, and removing an estimated figure un-flags it")
        void clearEmptiesField() {
            MealLog meal = existing();
            meal.setProteinGrams(12.0);
            when(mealLogRepository.findById("meal-1")).thenReturn(Optional.of(meal));
            when(mealLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            MealRequest edit = new MealRequest();
            edit.setCalories(200);
            edit.setClear(java.util.Set.of("proteinGrams"));

            MealResponse response = nutritionService.updateMeal("user-1", "meal-1", edit);

            assertThat(response.getProteinGrams()).isNull();
            assertThat(response.getCalories()).isEqualTo(200);
            assertThat(response.getAiEstimated()).isFalse();
        }

        @Test
        @DisplayName("clear rejects a field that can't be emptied")
        void clearRejectsUnknownField() {
            when(mealLogRepository.findById("meal-1")).thenReturn(Optional.of(existing()));

            MealRequest edit = new MealRequest();
            edit.setClear(java.util.Set.of("mealType"));

            assertThatThrownBy(() -> nutritionService.updateMeal("user-1", "meal-1", edit))
                    .isExactlyInstanceOf(RuntimeException.class)
                    .hasMessageContaining("mealType");
            verify(mealLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("clearing every figure is refused — a meal needs calories or macros")
        void clearingEverythingRefused() {
            when(mealLogRepository.findById("meal-1")).thenReturn(Optional.of(existing()));

            MealRequest edit = new MealRequest();
            edit.setClear(java.util.Set.of("calories"));

            assertThatThrownBy(() -> nutritionService.updateMeal("user-1", "meal-1", edit))
                    .isExactlyInstanceOf(RuntimeException.class)
                    .hasMessageContaining("at least a calorie figure");
            verify(mealLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("another user cannot edit this meal")
        void editDeniedForNonOwner() {
            when(mealLogRepository.findById("meal-1")).thenReturn(Optional.of(existing()));

            assertThatThrownBy(() -> nutritionService.updateMeal("someone-else", "meal-1", new MealRequest()))
                    .isInstanceOf(AccessDeniedException.class);
            verify(mealLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("another user cannot delete this meal")
        void deleteDeniedForNonOwner() {
            when(mealLogRepository.findById("meal-1")).thenReturn(Optional.of(existing()));

            assertThatThrownBy(() -> nutritionService.deleteMeal("someone-else", "meal-1"))
                    .isInstanceOf(AccessDeniedException.class);
            verify(mealLogRepository, never()).delete(any());
        }

        @Test
        @DisplayName("deleting refreshes the goals, so the meal stops counting")
        void deleteRefreshesGoals() {
            when(mealLogRepository.findById("meal-1")).thenReturn(Optional.of(existing()));

            nutritionService.deleteMeal("user-1", "meal-1");

            verify(mealLogRepository).delete(any());
            verify(goalService).refreshNutritionGoals(user);
        }
    }

    // ── Daily summary ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Daily summary")
    class DailySummary {

        @Test
        @DisplayName("net calories subtract maintenance as well as exercise")
        void netSubtractsMaintenanceAndExercise() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                    eq("user-1"), any(), any()))
                    .thenReturn(List.of(meal(2200, 120.0, 250.0, 70.0)));
            when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                    .thenReturn(List.of(activity(400)));

            Map<String, Object> summary = nutritionService.getDailySummary("user-1", LocalDate.now());

            // 70kg / 175cm / 30y, sex-neutral Mifflin-St Jeor: 10*70 + 6.25*175 - 5*30 - 78
            // = 1566 BMR, x1.2 = 1879 maintenance.
            int maintenance = (int) summary.get("maintenanceCalories");
            assertThat(maintenance).isEqualTo(1879);

            // This is the assertion the whole feature turns on. "Eaten minus exercise"
            // would be 2200 - 400 = +1800, i.e. every honest day of eating reads as a
            // huge surplus. Subtracting maintenance is what makes the number mean
            // anything at all.
            assertThat(summary.get("netCalories")).isEqualTo(2200 - maintenance - 400);
            assertThat(summary.get("balance")).isEqualTo("DEFICIT");
        }

        @Test
        @DisplayName("says why rather than showing a confident zero when the profile is incomplete")
        void incompleteProfileExplainsItself() {
            User sparse = User.builder().id("user-1").weight(70.0).build();   // no height, no age
            when(userRepository.findById("user-1")).thenReturn(Optional.of(sparse));
            when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                    eq("user-1"), any(), any())).thenReturn(List.of(meal(600, null, null, null)));
            when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                    .thenReturn(List.of());

            Map<String, Object> summary = nutritionService.getDailySummary("user-1", LocalDate.now());

            assertThat(summary.get("caloriesIn")).isEqualTo(600);
            assertThat(summary.get("netCalories")).isNull();
            assertThat(summary.get("balance")).isNull();
            assertThat((String) summary.get("estimateNote")).contains("Profile");
        }

        @Test
        @DisplayName("totals the macros and splits calories by meal type")
        void totalsMacrosAndMealTypes() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            MealLog breakfast = meal(400, 20.0, 40.0, 12.0);
            MealLog lunch = meal(700, 45.0, 60.0, 22.0);
            lunch.setMealType(MealType.LUNCH);
            when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                    eq("user-1"), any(), any())).thenReturn(List.of(breakfast, lunch));
            when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                    .thenReturn(List.of());

            Map<String, Object> summary = nutritionService.getDailySummary("user-1", LocalDate.now());

            assertThat(summary.get("caloriesIn")).isEqualTo(1100);
            assertThat(summary.get("proteinGrams")).isEqualTo(65.0);

            @SuppressWarnings("unchecked")
            Map<String, Integer> byType = (Map<String, Integer>) summary.get("byMealType");
            assertThat(byType).containsEntry("BREAKFAST", 400).containsEntry("LUNCH", 700);
        }
    }

    // ── Daily series ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Daily series")
    class DailySeries {

        @Test
        @DisplayName("includes every day in the span, including days with nothing logged")
        void includesEmptyDays() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                    eq("user-1"), any(), any())).thenReturn(List.of());
            when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                    .thenReturn(List.of());

            List<Map<String, Object>> series = nutritionService.getDailySeries("user-1", 7);

            // Same rule the volume chart follows: a series containing only the days you
            // logged draws a blank week as a straight line between two points, which
            // makes a gap in the record look like consistency.
            assertThat(series).hasSize(7);
            assertThat(series.get(0)).containsEntry("caloriesIn", 0);
        }

        @Test
        @DisplayName("a day with nothing logged has a null net, not a fabricated deficit")
        void unloggedDayHasNullNet() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                    eq("user-1"), any(), any())).thenReturn(List.of());
            when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                    .thenReturn(List.of());

            List<Map<String, Object>> series = nutritionService.getDailySeries("user-1", 3);

            // Zero intake is "didn't log", not "fasted". Plotting it as a 1879 kcal
            // deficit would invent a trend out of an absence of data.
            assertThat(series).allSatisfy(row -> assertThat(row.get("netCalories")).isNull());
        }

        @Test
        @DisplayName("caps the span rather than letting a hand-edited query ask for everything")
        void capsSpan() {
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                    eq("user-1"), any(), any())).thenReturn(List.of());
            when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                    .thenReturn(List.of());

            assertThat(nutritionService.getDailySeries("user-1", 99999)).hasSize(365);
            assertThat(nutritionService.getDailySeries("user-1", -5)).hasSize(1);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MealLog meal(Integer calories, Double protein, Double carbs, Double fat) {
        return MealLog.builder()
                .id("meal-" + calories).user(user).description("food")
                .mealType(MealType.BREAKFAST)
                .calories(calories).proteinGrams(protein).carbsGrams(carbs).fatGrams(fat)
                .loggedAt(LocalDateTime.now()).aiEstimated(false)
                .build();
    }

    private Activity activity(int calories) {
        Activity a = new Activity();
        a.setCaloriesBurned(calories);
        a.setStartTime(LocalDateTime.now());
        return a;
    }
}

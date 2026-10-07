package com.project.fitness.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.fitness.dto.WeeklyDigestResponse;
import com.project.fitness.model.*;
import com.project.fitness.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WeeklyDigestService — PLAN P2-3.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WeeklyDigestService Tests")
class WeeklyDigestServiceTest {

    @Mock private WeeklyDigestRepository digestRepository;
    @Mock private UserRepository userRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ExerciseRepository exerciseRepository;
    @Mock private MealLogRepository mealLogRepository;
    @Mock private SleepLogRepository sleepLogRepository;
    @Mock private PainLogRepository painLogRepository;
    @Mock private BodyMetricsLogRepository bodyMetricsLogRepository;
    @Mock private GoalRepository goalRepository;
    @Mock private GeminiService geminiService;
    // The digest announces itself through the feed, and emails where a transport
    // exists (PLAN P2-2).
    @Mock private NotificationService notificationService;
    @Mock private EmailService emailService;

    private final CalorieCalculatorService calorieCalculatorService = new CalorieCalculatorService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WeeklyDigestService service;
    private User user;
    private LocalDate lastMonday;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").weight(80.0).height(180.0).age(30).currentStreak(4).build();
        lastMonday = LocalDate.now().minusWeeks(1)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        service = new WeeklyDigestService(digestRepository, userRepository, activityRepository,
                exerciseRepository, mealLogRepository, sleepLogRepository, painLogRepository,
                bodyMetricsLogRepository, goalRepository, calorieCalculatorService,
                geminiService, notificationService, emailService, objectMapper);

        lenient().when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        lenient().when(digestRepository.findByUserIdAndWeekStart(anyString(), any()))
                .thenReturn(Optional.empty());
        lenient().when(digestRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Nothing logged anywhere by default; each test adds only what it is about.
        lenient().when(activityRepository.findByUserIdAndStartTimeBetween(anyString(), any(), any()))
                .thenReturn(List.of());
        lenient().when(exerciseRepository.findAllForUserWithActivity(anyString())).thenReturn(List.of());
        lenient().when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                anyString(), any(), any())).thenReturn(List.of());
        lenient().when(sleepLogRepository.findByUserIdAndSleepDateBetweenOrderBySleepDateAsc(
                anyString(), any(), any())).thenReturn(List.of());
        lenient().when(painLogRepository.findByUserId(anyString())).thenReturn(List.of());
        lenient().when(bodyMetricsLogRepository.findByUserIdOrderByLoggedAtAsc(anyString()))
                .thenReturn(List.of());
        lenient().when(goalRepository.findByUserId(anyString())).thenReturn(List.of());
    }

    private Activity activity(LocalDateTime when, int minutes, int calories) {
        Activity a = new Activity();
        a.setId("act-" + when);
        a.setType(ActivityType.WEIGHT_TRAINING);
        a.setStartTime(when);
        a.setDuration(minutes);
        a.setCaloriesBurned(calories);
        return a;
    }

    private void trainedLastWeek(Activity... activities) {
        when(activityRepository.findByUserIdAndStartTimeBetween(eq("user-1"), any(), any()))
                .thenReturn(List.of(activities))      // the week under review
                .thenReturn(List.of());               // the week before it
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(WeeklyDigestResponse digest, String name) {
        return (Map<String, Object>) digest.getDataPoints().get(name);
    }

    // ── Facts ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("The facts")
    class Facts {

        @Test
        @DisplayName("are computed from the rows and stored with the digest")
        void computedAndStored() {
            trainedLastWeek(activity(lastMonday.atTime(9, 0), 45, 400),
                            activity(lastMonday.plusDays(2).atTime(18, 0), 60, 520));

            WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

            // Storing them is what makes a digest checkable months later, instead of
            // prose the reader has to take on faith.
            Map<String, Object> training = section(digest, "training");
            assertThat(training).containsEntry("sessions", 2);
            assertThat(training).containsEntry("minutes", 105);
            assertThat(training).containsEntry("calories", 920);
            assertThat(training).containsEntry("daysTrained", 2);
            assertThat(digest.getDataPoints()).containsEntry("currentStreak", 4);
        }

        @Test
        @DisplayName("average nutrition over the days logged, not over seven")
        void nutritionAveragesOverLoggedDays() {
            trainedLastWeek(activity(lastMonday.atTime(9, 0), 30, 300));
            when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                    eq("user-1"), any(), any()))
                    .thenReturn(List.of(
                            meal(lastMonday.atTime(8, 0), 600, 40.0),
                            meal(lastMonday.atTime(13, 0), 800, 50.0),
                            meal(lastMonday.plusDays(1).atTime(9, 0), 1000, 60.0)));

            WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

            Map<String, Object> nutrition = section(digest, "nutrition");
            // Two days logged: (1400 + 1000) / 2. Dividing by seven would report a
            // starvation week to anyone who simply didn't log on five of the days.
            assertThat(nutrition).containsEntry("daysLogged", 2);
            assertThat(nutrition).containsEntry("avgCaloriesPerLoggedDay", 1200);
            assertThat(nutrition).containsEntry("avgProteinPerLoggedDay", 75.0);
        }

        /**
         * Intake was averaged over the days with food logged, but the burn was the whole
         * week's training divided by that day count, so a workout on an unlogged day was
         * charged against the logged ones (PLAN P4-10).
         */
        @Test
        @DisplayName("net uses the burn from the logged days only")
        void netCountsBurnOnLoggedDaysOnly() {
            trainedLastWeek(activity(lastMonday.atTime(9, 0), 30, 300),            // a logged day
                            activity(lastMonday.plusDays(3).atTime(18, 0), 60, 600)); // not logged
            when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                    eq("user-1"), any(), any()))
                    .thenReturn(List.of(
                            meal(lastMonday.atTime(8, 0), 600, 40.0),
                            meal(lastMonday.atTime(13, 0), 800, 50.0),
                            meal(lastMonday.plusDays(1).atTime(9, 0), 1000, 60.0)));

            WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

            int maintenance = calorieCalculatorService.maintenanceCalories(user);
            Map<String, Object> nutrition = section(digest, "nutrition");
            // Intake (1400 + 1000) / 2 = 1200. Burn on the two logged days is 300 + 0,
            // so 150 a day. The old figure charged 900 / 2 = 450.
            assertThat(nutrition).containsEntry("maintenanceCalories", maintenance);
            assertThat(nutrition).containsEntry("avgNetPerLoggedDay", 1200 - maintenance - 150);
        }

        @Test
        @DisplayName("leave a week-on-week change out entirely when there is no baseline")
        void noBaselineMeansNoPercentage() {
            trainedLastWeek(activity(lastMonday.atTime(9, 0), 45, 400));

            WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

            // "Up 100% from zero" is noise, not a trend.
            assertThat(section(digest, "training").get("minutesChangePercent")).isNull();
        }

        @Test
        @DisplayName("name the movements whose estimated 1RM has gone flat")
        void flagsStalledMovements() {
            Activity thisWeek = activity(lastMonday.plusDays(1).atTime(18, 0), 50, 400);
            Activity previous = activity(lastMonday.minusDays(5).atTime(18, 0), 50, 400);
            previous.setId("act-prev");
            trainedLastWeek(thisWeek);

            Exercise latest = exercise("Bench Press", 3, 8, 60.0, thisWeek);
            Exercise earlier = exercise("Bench Press", 3, 8, 60.0, previous);
            when(exerciseRepository.findAllForUserWithActivity("user-1")).thenReturn(List.of(latest));
            when(exerciseRepository.findForUserByName("user-1", "bench press"))
                    .thenReturn(List.of(latest, earlier));

            WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

            // The signal the old coaching prompt could never have mentioned, because
            // sets and loads were not in it at all.
            @SuppressWarnings("unchecked")
            List<String> stalled = (List<String>) section(digest, "strength").get("stalled");
            assertThat(stalled).containsExactly("Bench Press");
        }
    }

    // ── The model ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("With Gemini configured")
    class WithGemini {

        @Test
        @DisplayName("the model's narrative is used and the digest is marked AI-generated")
        void usesModelNarrative() {
            trainedLastWeek(activity(lastMonday.atTime(9, 0), 45, 400));
            when(geminiService.isConfigured()).thenReturn(true);
            when(geminiService.generateRecommendation(anyString())).thenReturn(Map.of(
                    "headline", "Volume held steady while sleep slipped",
                    "narrative", "You trained twice and averaged under six hours.",
                    "observations", List.of("Sleep averaged 5h50."),
                    "focusNextWeek", List.of("Protect one extra hour of sleep.")));

            WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

            assertThat(digest.getHeadline()).isEqualTo("Volume held steady while sleep slipped");
            assertThat(digest.getObservations()).containsExactly("Sleep averaged 5h50.");
            assertThat(digest.getFocusNextWeek()).containsExactly("Protect one extra hour of sleep.");
            assertThat(digest.getAiGenerated()).isTrue();
        }

        @Test
        @DisplayName("the prompt carries the real figures and forbids inventing any")
        void promptCarriesFactsAndForbidsInvention() {
            trainedLastWeek(activity(lastMonday.atTime(9, 0), 45, 400));
            when(geminiService.isConfigured()).thenReturn(true);
            when(geminiService.generateRecommendation(anyString()))
                    .thenReturn(Map.of("headline", "ok", "narrative", "ok"));

            service.generate("user-1", lastMonday);

            ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
            verify(geminiService).generateRecommendation(prompt.capture());

            // The model interprets numbers; it is never the source of them.
            assertThat(prompt.getValue()).contains("\"minutes\":45");
            assertThat(prompt.getValue()).contains("Use ONLY the numbers given");
            assertThat(prompt.getValue()).contains("Do not treat missing data as");
        }

        @Test
        @DisplayName("an unusable reply falls back rather than saving an empty digest")
        void unusableReplyFallsBack() {
            trainedLastWeek(activity(lastMonday.atTime(9, 0), 45, 400));
            when(geminiService.isConfigured()).thenReturn(true);
            when(geminiService.generateRecommendation(anyString()))
                    .thenReturn(Map.of("something", "unexpected"));

            WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

            assertThat(digest.getAiGenerated()).isFalse();
            assertThat(digest.getHeadline()).contains("1 session");
        }
    }

    // ── The fallback ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Without a Gemini key")
    class WithoutGemini {

        @Test
        @DisplayName("still produces a real digest, marked as not AI-generated")
        void fallbackIsStillADigest() {
            trainedLastWeek(activity(lastMonday.atTime(9, 0), 45, 400),
                            activity(lastMonday.plusDays(2).atTime(18, 0), 60, 500));
            when(sleepLogRepository.findByUserIdAndSleepDateBetweenOrderBySleepDateAsc(
                    eq("user-1"), any(), any()))
                    .thenReturn(List.of(sleep(lastMonday, 6.0, 3), sleep(lastMonday.plusDays(1), 6.4, 3)));

            WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

            // The facts were computed either way. Degrading to "AI unavailable" would
            // throw away work that was already done.
            assertThat(digest.getAiGenerated()).isFalse();
            assertThat(digest.getHeadline()).isEqualTo("2 sessions, 105 minutes trained");
            assertThat(digest.getObservations())
                    .anySatisfy(o -> assertThat(o).contains("6.2h").contains("2 logged nights"));
            assertThat(digest.getFocusNextWeek())
                    .anySatisfy(f -> assertThat(f).contains("under 7 hours"));
            verify(geminiService, never()).generateRecommendation(anyString());
        }

        @Test
        @DisplayName("treats a severe pain entry as the most important thing in the week")
        void severePainLeadsTheFocus() {
            trainedLastWeek(activity(lastMonday.atTime(9, 0), 45, 400));
            PainLog p = new PainLog();
            p.setLoggedAt(lastMonday.plusDays(1).atTime(20, 0));
            p.setBodyPart(BodyPart.LOWER_BACK);
            p.setSeverity(4);
            when(painLogRepository.findByUserId("user-1")).thenReturn(List.of(p));

            WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

            assertThat(digest.getObservations()).anySatisfy(o -> assertThat(o).contains("severity 4/5"));
            assertThat(digest.getFocusNextWeek()).anySatisfy(f -> assertThat(f).contains("back off"));
        }

        @Test
        @DisplayName("says nothing was trained rather than reporting an empty week as normal")
        void emptyTrainingWeekIsNamed() {
            when(mealLogRepository.findByUserIdAndLoggedAtBetweenOrderByLoggedAtAsc(
                    eq("user-1"), any(), any()))
                    .thenReturn(List.of(meal(lastMonday.atTime(12, 0), 700, 40.0)));

            WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

            assertThat(digest.getHeadline()).isEqualTo("No training logged this week");
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Regenerating a week replaces its digest instead of adding another")
    void regeneratingReplaces() {
        trainedLastWeek(activity(lastMonday.atTime(9, 0), 45, 400));
        WeeklyDigest existing = WeeklyDigest.builder()
                .id("digest-1").user(user).weekStart(lastMonday).headline("Old headline").build();
        when(digestRepository.findByUserIdAndWeekStart("user-1", lastMonday))
                .thenReturn(Optional.of(existing));

        WeeklyDigestResponse digest = service.generate("user-1", lastMonday);

        assertThat(digest.getId()).isEqualTo("digest-1");
        assertThat(digest.getHeadline()).isNotEqualTo("Old headline");
    }

    @Test
    @DisplayName("A week with nothing logged at all is refused, not written as an empty digest")
    void emptyWeekRefused() {
        assertThatThrownBy(() -> service.generate("user-1", lastMonday))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessageContaining("nothing to summarise");

        verify(digestRepository, never()).save(any());
    }

    @Test
    @DisplayName("The scheduled run only touches users who actually used the app")
    void scheduledRunIsScopedToActiveUsers() {
        when(activityRepository.findUserIdsWithActivitySince(any())).thenReturn(List.of("user-1"));
        when(mealLogRepository.findUserIdsWithMealsSince(any())).thenReturn(List.of("user-1", "user-2"));
        when(userRepository.findById("user-2")).thenReturn(Optional.empty());
        trainedLastWeek(activity(lastMonday.atTime(9, 0), 45, 400));

        service.generateForActiveUsers();

        // Each digest costs a Gemini call against a shared quota, so dormant accounts
        // are not summarised — and one user failing must not stop the rest, which is
        // what user-2 (a deleted account) checks here.
        // atLeastOnce: generate() loads the user and deliver() loads it again to
        // address the notification and the email.
        verify(userRepository, atLeastOnce()).findById("user-1");
        verify(userRepository).findById("user-2");
        verify(digestRepository, times(1)).save(any());
        // The digest announces itself — that is the whole point of writing one on a
        // schedule (PLAN P2-2).
        verify(notificationService).notify(eq(user), eq(NotificationType.WEEKLY_DIGEST),
                anyString(), anyString(), eq("ai"), anyString());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MealLog meal(LocalDateTime when, int calories, Double protein) {
        return MealLog.builder()
                .id("meal-" + when).user(user).mealType(MealType.LUNCH)
                .calories(calories).proteinGrams(protein).loggedAt(when).build();
    }

    private SleepLog sleep(LocalDate date, double hours, int quality) {
        SleepLog s = new SleepLog();
        s.setSleepDate(date);
        s.setHoursSlept(hours);
        s.setQualityRating(quality);
        return s;
    }

    private Exercise exercise(String name, int sets, int reps, Double weight, Activity activity) {
        Exercise e = new Exercise();
        e.setName(name);
        e.setSets(sets);
        e.setReps(reps);
        e.setWeightKg(weight);
        e.setActivity(activity);
        return e;
    }
}

package com.project.fitness.service;

import com.project.fitness.model.*;
import com.project.fitness.repository.PersonalBestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PersonalBestService — Feature 3
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalBestService Tests")
class PersonalBestServiceTest {

    @Mock
    private PersonalBestRepository personalBestRepository;

    @InjectMocks
    private PersonalBestService personalBestService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").firstName("Nikhil").build();
    }

    private Activity buildActivity(ActivityType type, Integer duration, Integer calories) {
        Activity a = new Activity();
        a.setUser(user);
        a.setType(type);
        a.setDuration(duration);
        a.setCaloriesBurned(calories);
        return a;
    }

    // ── First activity of a type ──────────────────────────────────────────────

    @Test
    @DisplayName("First activity of type: always a personal best")
    void firstActivity_isPersonalBest() {
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.RUNNING))
                .thenReturn(Optional.empty());
        when(personalBestRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = buildActivity(ActivityType.RUNNING, 30, 300);
        boolean result = personalBestService.checkAndUpdate(user, activity);

        assertThat(result).isTrue();
        verify(personalBestRepository).save(any(PersonalBest.class));
    }

    // ── Better duration beats old record ──────────────────────────────────────

    @Test
    @DisplayName("Longer duration: is a personal best and record is updated")
    void longerDuration_isPersonalBest() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.RUNNING)
                .bestDuration(20).bestCalories(200).build();
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.RUNNING))
                .thenReturn(Optional.of(existing));
        when(personalBestRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = buildActivity(ActivityType.RUNNING, 35, 150); // longer but fewer calories
        boolean result = personalBestService.checkAndUpdate(user, activity);

        assertThat(result).isTrue();
        assertThat(existing.getBestDuration()).isEqualTo(35);
        assertThat(existing.getBestCalories()).isEqualTo(200); // old calories kept, not lower
    }

    // ── Higher calories beats old record ──────────────────────────────────────

    @Test
    @DisplayName("Higher calories: is a personal best")
    void higherCalories_isPersonalBest() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.CYCLING)
                .bestDuration(30).bestCalories(250).build();
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.CYCLING))
                .thenReturn(Optional.of(existing));
        when(personalBestRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = buildActivity(ActivityType.CYCLING, 25, 400);
        boolean result = personalBestService.checkAndUpdate(user, activity);

        assertThat(result).isTrue();
        assertThat(existing.getBestCalories()).isEqualTo(400);
        assertThat(existing.getBestDuration()).isEqualTo(30); // old duration kept
    }

    // ── No improvement — not a personal best ─────────────────────────────────

    @Test
    @DisplayName("Shorter duration, fewer calories: NOT a personal best")
    void noImprovement_notPersonalBest() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.SWIMMING)
                .bestDuration(60).bestCalories(500).build();
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.SWIMMING))
                .thenReturn(Optional.of(existing));

        Activity activity = buildActivity(ActivityType.SWIMMING, 30, 200);
        boolean result = personalBestService.checkAndUpdate(user, activity);

        assertThat(result).isFalse();
        verify(personalBestRepository, never()).save(any()); // no save on no improvement
    }

    // ── Null duration activity doesn't blow up ────────────────────────────────

    @Test
    @DisplayName("Null duration: does not throw, evaluates calories only")
    void nullDuration_handledSafely() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.YOGA)
                .bestDuration(null).bestCalories(100).build();
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.YOGA))
                .thenReturn(Optional.of(existing));
        when(personalBestRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = buildActivity(ActivityType.YOGA, null, 200);
        boolean result = personalBestService.checkAndUpdate(user, activity);

        assertThat(result).isTrue();
    }

    // ── Different activity types are tracked independently ────────────────────

    @Test
    @DisplayName("Personal best for RUNNING does not affect CYCLING record")
    void differentTypes_trackedIndependently() {
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.HIIT))
                .thenReturn(Optional.empty());
        when(personalBestRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Activity activity = buildActivity(ActivityType.HIIT, 20, 250);
        boolean result = personalBestService.checkAndUpdate(user, activity);

        assertThat(result).isTrue();
        // Only called with HIIT — not with any other type
        verify(personalBestRepository).findByUserIdAndActivityType("user-1", ActivityType.HIIT);
        verifyNoMoreInteractions(personalBestRepository);
    }

    // ── Which record broke (PLAN P0-6) ───────────────────────────────────────
    // The message was hardcoded to "longest session" regardless of what was beaten,
    // so breaking a calorie record was announced as a duration record.

    @Test
    @DisplayName("Beating only the calorie record says so, not 'longest session'")
    void message_namesCalorieRecord() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.RUNNING)
                .bestDuration(60).bestCalories(300).build();
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.RUNNING))
                .thenReturn(Optional.of(existing));

        Activity activity = new Activity();
        activity.setType(ActivityType.RUNNING);
        activity.setDuration(30);       // shorter than the record
        activity.setCaloriesBurned(500); // but harder

        PersonalBestService.Result result = personalBestService.evaluate(user, activity);

        assertThat(result.isNewBest()).isTrue();
        assertThat(result.message()).contains("calories").doesNotContain("Longest");
    }

    @Test
    @DisplayName("Beating only the duration record says 'longest'")
    void message_namesDurationRecord() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.RUNNING)
                .bestDuration(30).bestCalories(900).build();
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.RUNNING))
                .thenReturn(Optional.of(existing));

        Activity activity = new Activity();
        activity.setType(ActivityType.RUNNING);
        activity.setDuration(90);
        activity.setCaloriesBurned(400);

        PersonalBestService.Result result = personalBestService.evaluate(user, activity);

        // The message now lists the records that broke rather than picking one phrasing,
        // so it reads "longest at 90 min" mid-sentence — still naming duration, and
        // still not claiming the calorie record it did not beat.
        assertThat(result.message())
                .containsIgnoringCase("longest")
                .doesNotContain("calories");
    }

    // ── Distance (PLAN P1-1: distance became a column, so it can be a record) ──

    @Test
    @DisplayName("A longer run sets a distance record even when it was quicker and easier")
    void distanceRecordStandsAlone() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.RUNNING)
                .bestDuration(90).bestCalories(900).bestDistanceKm(5.0).build();
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.RUNNING))
                .thenReturn(Optional.of(existing));

        Activity activity = new Activity();
        activity.setType(ActivityType.RUNNING);
        activity.setDuration(40);          // shorter
        activity.setCaloriesBurned(400);   // easier
        activity.setDistanceKm(8.0);       // but further

        PersonalBestService.Result result = personalBestService.evaluate(user, activity);

        assertThat(result.isNewBest()).isTrue();
        assertThat(result.message()).contains("furthest at 8 km");
        assertThat(existing.getBestDistanceKm()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("An activity with no distance leaves the distance record alone")
    void noDistanceLeavesRecordUntouched() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.RUNNING)
                .bestDuration(30).bestCalories(300).bestDistanceKm(12.0).build();
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.RUNNING))
                .thenReturn(Optional.of(existing));

        Activity activity = new Activity();
        activity.setType(ActivityType.RUNNING);
        activity.setDuration(60);
        activity.setCaloriesBurned(200);

        personalBestService.evaluate(user, activity);

        assertThat(existing.getBestDistanceKm()).isEqualTo(12.0);
    }

    @Test
    @DisplayName("Breaking all three records names all three")
    void messageNamesEveryRecordBroken() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.RUNNING)
                .bestDuration(20).bestCalories(200).bestDistanceKm(3.0).build();
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.RUNNING))
                .thenReturn(Optional.of(existing));

        Activity activity = new Activity();
        activity.setType(ActivityType.RUNNING);
        activity.setDuration(60);
        activity.setCaloriesBurned(600);
        activity.setDistanceKm(10.0);

        String message = personalBestService.evaluate(user, activity).message();

        assertThat(message)
                .contains("furthest at 10 km")
                .contains("longest at 60 min")
                .contains("most calories at 600");
    }

    @Test
    @DisplayName("Beating neither record produces no message at all")
    void message_absentWhenNothingBroken() {
        PersonalBest existing = PersonalBest.builder()
                .user(user).activityType(ActivityType.RUNNING)
                .bestDuration(90).bestCalories(900).build();
        when(personalBestRepository.findByUserIdAndActivityType("user-1", ActivityType.RUNNING))
                .thenReturn(Optional.of(existing));

        Activity activity = new Activity();
        activity.setType(ActivityType.RUNNING);
        activity.setDuration(20);
        activity.setCaloriesBurned(100);

        PersonalBestService.Result result = personalBestService.evaluate(user, activity);

        assertThat(result.isNewBest()).isFalse();
        assertThat(result.message()).isNull();
    }
}

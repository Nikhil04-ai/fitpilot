package com.project.fitness.service;

import com.project.fitness.model.Activity;
import com.project.fitness.model.ActivityType;
import com.project.fitness.model.Exercise;
import com.project.fitness.model.ExercisePersonalBest;
import com.project.fitness.model.User;
import com.project.fitness.repository.ExercisePersonalBestRepository;
import com.project.fitness.repository.ExerciseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-movement strength records — the half of PLAN P0-6 that P1-1 closes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExercisePersonalBestService Tests")
class ExercisePersonalBestServiceTest {

    @Mock private ExercisePersonalBestRepository repository;
    @Mock private ExerciseRepository exerciseRepository;
    @InjectMocks private ExercisePersonalBestService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").build();
    }

    private Exercise set(String name, int sets, int reps, Double weight) {
        Exercise e = new Exercise();
        e.setName(name);
        e.setSets(sets);
        e.setReps(reps);
        e.setWeightKg(weight);
        return e;
    }

    private Activity workout(LocalDateTime when, Exercise... exercises) {
        Activity a = Activity.builder()
                .user(user).type(ActivityType.WEIGHT_TRAINING).startTime(when).build();
        a.replaceExercises(List.of(exercises));
        return a;
    }

    private ExercisePersonalBest record(Double weight, Double oneRm, Double volume) {
        return ExercisePersonalBest.builder()
                .user(user).normalisedName("bench press").displayName("Bench Press")
                .bestWeightKg(weight).bestEstimatedOneRepMax(oneRm).bestVolume(volume)
                .build();
    }

    // ── First time a movement is seen ────────────────────────────────────────

    @Test
    @DisplayName("the first time a movement is logged it is a benchmark, not a record")
    void firstEntryCreatesRecordButAnnouncesNothing() {
        when(repository.findByUserIdAndNormalisedName("user-1", "bench press"))
                .thenReturn(Optional.empty());

        List<String> broken = service.evaluate(user,
                workout(LocalDateTime.now(), set("Bench Press", 3, 8, 60.0)));

        // Saying "new personal best!" for something with nothing to beat is the kind
        // of praise that stops meaning anything.
        assertThat(broken).isEmpty();

        ArgumentCaptor<ExercisePersonalBest> saved = ArgumentCaptor.forClass(ExercisePersonalBest.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getNormalisedName()).isEqualTo("bench press");
        assertThat(saved.getValue().getBestWeightKg()).isEqualTo(60.0);
        assertThat(saved.getValue().getBestEstimatedOneRepMax()).isEqualTo(76.0);
        assertThat(saved.getValue().getBestVolume()).isEqualTo(1440.0);
    }

    // ── Beating a record ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a heavier set breaks the weight record and says which movement")
    void heavierSetBreaksWeightRecord() {
        when(repository.findByUserIdAndNormalisedName("user-1", "bench press"))
                .thenReturn(Optional.of(record(60.0, 76.0, 1440.0)));

        List<String> broken = service.evaluate(user,
                workout(LocalDateTime.now(), set("Bench Press", 3, 8, 65.0)));

        assertThat(broken).hasSize(1);
        assertThat(broken.get(0)).startsWith("Bench Press —").contains("65kg x 8");
    }

    @Test
    @DisplayName("matching the record exactly is not beating it")
    void matchingRecordIsNotABest() {
        when(repository.findByUserIdAndNormalisedName("user-1", "bench press"))
                .thenReturn(Optional.of(record(60.0, 76.0, 1440.0)));

        List<String> broken = service.evaluate(user,
                workout(LocalDateTime.now(), set("Bench Press", 3, 8, 60.0)));

        assertThat(broken).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("more volume at the same weight still counts as a record")
    void moreVolumeAtSameWeight() {
        when(repository.findByUserIdAndNormalisedName("user-1", "bench press"))
                .thenReturn(Optional.of(record(60.0, 76.0, 1440.0)));

        List<String> broken = service.evaluate(user,
                workout(LocalDateTime.now(), set("Bench Press", 5, 8, 60.0)));   // 2400

        assertThat(broken).hasSize(1);
        assertThat(broken.get(0)).contains("total volume");
        assertThat(broken.get(0)).doesNotContain("60kg x 8");   // weight itself unchanged
    }

    @Test
    @DisplayName("the same movement typed differently updates one record, not two")
    void matchesRegardlessOfCasing() {
        when(repository.findByUserIdAndNormalisedName("user-1", "bench press"))
                .thenReturn(Optional.of(record(60.0, 76.0, 1440.0)));

        List<String> broken = service.evaluate(user,
                workout(LocalDateTime.now(), set("  bench   PRESS ", 3, 8, 70.0)));

        assertThat(broken).hasSize(1);
        verify(repository).findByUserIdAndNormalisedName("user-1", "bench press");
    }

    @Test
    @DisplayName("a bodyweight set never beats a loaded weight record")
    void bodyweightSetDoesNotBeatLoadedRecord() {
        ExercisePersonalBest existing = record(60.0, 76.0, 1440.0);
        existing.setBestBodyweightReps(20);   // already ahead on reps too
        when(repository.findByUserIdAndNormalisedName("user-1", "bench press"))
                .thenReturn(Optional.of(existing));

        List<String> broken = service.evaluate(user,
                workout(LocalDateTime.now(), set("Bench Press", 3, 8, null)));

        assertThat(broken).isEmpty();
        assertThat(existing.getBestWeightKg()).isEqualTo(60.0);
    }

    // ── Bodyweight movements ─────────────────────────────────────────────────

    @Test
    @DisplayName("an unloaded movement records its rep count instead of an empty card")
    void bodyweightRecordsReps() {
        when(repository.findByUserIdAndNormalisedName("user-1", "pull ups"))
                .thenReturn(Optional.empty());

        service.evaluate(user, workout(LocalDateTime.now(), set("Pull Ups", 3, 10, null)));

        ArgumentCaptor<ExercisePersonalBest> saved = ArgumentCaptor.forClass(ExercisePersonalBest.class);
        verify(repository).save(saved.capture());
        // Weight, 1RM and volume are all meaningless here; reps are the achievement.
        assertThat(saved.getValue().getBestBodyweightReps()).isEqualTo(10);
        assertThat(saved.getValue().getBestWeightKg()).isNull();
        assertThat(saved.getValue().getBestEstimatedOneRepMax()).isNull();
    }

    @Test
    @DisplayName("more unloaded reps than before is a record")
    void moreBodyweightRepsIsARecord() {
        ExercisePersonalBest existing = ExercisePersonalBest.builder()
                .user(user).normalisedName("pull ups").displayName("Pull Ups")
                .bestBodyweightReps(10).build();
        when(repository.findByUserIdAndNormalisedName("user-1", "pull ups"))
                .thenReturn(Optional.of(existing));

        List<String> broken = service.evaluate(user,
                workout(LocalDateTime.now(), set("Pull Ups", 3, 12, null)));

        assertThat(broken).hasSize(1);
        assertThat(broken.get(0)).contains("Pull Ups").contains("12 reps unloaded");
        assertThat(existing.getBestBodyweightReps()).isEqualTo(12);
    }

    @Test
    @DisplayName("reps under load don't count toward the bodyweight rep record")
    void loadedRepsDoNotCountAsBodyweightReps() {
        ExercisePersonalBest existing = ExercisePersonalBest.builder()
                .user(user).normalisedName("pull ups").displayName("Pull Ups")
                .bestBodyweightReps(10).build();
        when(repository.findByUserIdAndNormalisedName("user-1", "pull ups"))
                .thenReturn(Optional.of(existing));

        // 15 reps with a 10kg belt is a different achievement from 15 strict ones.
        service.evaluate(user, workout(LocalDateTime.now(), set("Pull Ups", 3, 15, 10.0)));

        assertThat(existing.getBestBodyweightReps()).isEqualTo(10);
    }

    @Test
    @DisplayName("a movement with neither load nor reps gets no record row at all")
    void nothingMeasurableMeansNoRecord() {
        Exercise vague = new Exercise();
        vague.setName("Stretching");

        Activity a = Activity.builder().user(user).type(ActivityType.STRETCHING)
                .startTime(LocalDateTime.now()).build();
        a.replaceExercises(List.of(vague));

        assertThat(service.evaluate(user, a)).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an unnamed movement is skipped rather than creating a nameless record")
    void skipsUnnamedExercise() {
        Exercise blank = new Exercise();
        blank.setName("   ");
        blank.setWeightKg(200.0);

        Activity a = Activity.builder().user(user).type(ActivityType.WEIGHT_TRAINING)
                .startTime(LocalDateTime.now()).build();
        a.replaceExercises(List.of(blank));

        assertThat(service.evaluate(user, a)).isEmpty();
        verify(repository, never()).save(any());
    }

    // ── Rebuild after edit / delete ──────────────────────────────────────────

    @Test
    @DisplayName("deleting the session that set a 1RM lowers the record to what remains")
    void recalculateLowersRecord() {
        Exercise remaining = set("Bench Press", 3, 8, 60.0);
        remaining.setActivity(Activity.builder().user(user)
                .startTime(LocalDateTime.now().minusDays(3)).build());

        when(exerciseRepository.findAllForUser("user-1")).thenReturn(List.of(remaining));
        // The record still claims the 80kg from the workout that was just deleted.
        ExercisePersonalBest stale = record(80.0, 101.3, 2400.0);
        when(repository.findByUserId("user-1")).thenReturn(List.of(stale));

        service.recalculateFor(user);

        assertThat(stale.getBestWeightKg()).isEqualTo(60.0);
        assertThat(stale.getBestEstimatedOneRepMax()).isEqualTo(76.0);
        assertThat(stale.getBestVolume()).isEqualTo(1440.0);
        verify(repository).save(stale);
    }

    @Test
    @DisplayName("a movement with no sets left loses its record row entirely")
    void recalculateDeletesOrphanedRecord() {
        when(exerciseRepository.findAllForUser("user-1")).thenReturn(List.of());
        ExercisePersonalBest orphan = record(80.0, 101.3, 2400.0);
        when(repository.findByUserId("user-1")).thenReturn(List.of(orphan));

        service.recalculateFor(user);

        verify(repository).delete(orphan);
    }

    @Test
    @DisplayName("rebuilding creates a record for a movement that never had one")
    void recalculateCreatesMissingRecord() {
        Exercise e = set("Front Squat", 5, 5, 90.0);
        e.setActivity(Activity.builder().user(user).startTime(LocalDateTime.now()).build());

        when(exerciseRepository.findAllForUser("user-1")).thenReturn(List.of(e));
        when(repository.findByUserId("user-1")).thenReturn(List.of());

        service.recalculateFor(user);

        ArgumentCaptor<ExercisePersonalBest> saved = ArgumentCaptor.forClass(ExercisePersonalBest.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getNormalisedName()).isEqualTo("front squat");
        assertThat(saved.getValue().getBestWeightKg()).isEqualTo(90.0);
    }

    // ── Badging ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an entry matching any of its movement's records is badged")
    void holdsRecordWhenItMatches() {
        when(repository.findByUserIdAndNormalisedName("user-1", "bench press"))
                .thenReturn(Optional.of(record(60.0, 76.0, 1440.0)));

        assertThat(service.holdsRecord("user-1", set("Bench Press", 3, 8, 60.0))).isTrue();
        assertThat(service.holdsRecord("user-1", set("Bench Press", 3, 8, 50.0))).isFalse();
    }

    @Test
    @DisplayName("a movement with no record row is not badged")
    void holdsRecordFalseWithoutRecord() {
        when(repository.findByUserIdAndNormalisedName(eq("user-1"), any()))
                .thenReturn(Optional.empty());

        assertThat(service.holdsRecord("user-1", set("Bench Press", 3, 8, 60.0))).isFalse();
    }
}

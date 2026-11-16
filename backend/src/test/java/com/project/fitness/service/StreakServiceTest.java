package com.project.fitness.service;

import com.project.fitness.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for StreakService — Feature 2. No Spring context, no DB — pure logic tests.
 *
 * <p>The streak is derived from the workout dates (recompute) and read as of today
 * (currentAsOf). The two failures the full-app test run of 2026-11-16 found are pinned
 * here: backdated workouts not extending a streak, and a streak that never ran out.
 */
@DisplayName("StreakService Tests")
class StreakServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").currentStreak(0).longestStreak(0).build();
    }

    private static LocalDateTime day(int daysAgo) {
        return TODAY.minusDays(daysAgo).atTime(7, 30);
    }

    private void recompute(LocalDateTime... startTimes) {
        StreakService.recompute(user, List.of(startTimes), TODAY);
    }

    @Test
    @DisplayName("No workouts: everything is zero")
    void noWorkouts() {
        recompute();
        assertThat(user.getCurrentStreak()).isZero();
        assertThat(user.getLongestStreak()).isZero();
        assertThat(user.getLastActivityDate()).isNull();
    }

    @Test
    @DisplayName("First workout today: streak 1")
    void firstWorkout() {
        recompute(day(0));
        assertThat(user.getCurrentStreak()).isEqualTo(1);
        assertThat(user.getLongestStreak()).isEqualTo(1);
        assertThat(user.getLastActivityDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("Consecutive days count; two workouts on one day count once")
    void consecutiveAndSameDay() {
        recompute(day(2), day(1), day(0), day(0));
        assertThat(user.getCurrentStreak()).isEqualTo(3);
        assertThat(user.getLongestStreak()).isEqualTo(3);
    }

    @Test
    @DisplayName("A streak ending yesterday is still current: the day isn't out")
    void endingYesterdayIsCurrent() {
        recompute(day(3), day(2), day(1));
        assertThat(user.getCurrentStreak()).isEqualTo(3);
    }

    @Test
    @DisplayName("A gap breaks the current run but the longest run is kept")
    void gapKeepsLongest() {
        recompute(day(10), day(9), day(8), day(7), day(1), day(0));
        assertThat(user.getCurrentStreak()).isEqualTo(2);
        assertThat(user.getLongestStreak()).isEqualTo(4);
    }

    @Test
    @DisplayName("A last workout two or more days ago means no current streak")
    void lapsedRunIsNotCurrent() {
        recompute(day(10), day(9), day(8));
        assertThat(user.getCurrentStreak()).isZero();
        assertThat(user.getLongestStreak()).isEqualTo(3);
    }

    @Test
    @DisplayName("Backdated workouts extend the streak whatever order they were logged in")
    void backdatedWorkoutsExtendTheStreak() {
        // Logged newest first: today, then yesterday, then the day before. The old
        // incremental update ignored the last two and left the streak at 1.
        recompute(day(0), day(1), day(2));
        assertThat(user.getCurrentStreak()).isEqualTo(3);
        assertThat(user.getLongestStreak()).isEqualTo(3);
    }

    @Test
    @DisplayName("A workout a few hours ahead (allowed) doesn't break today's streak")
    void slightlyFutureWorkoutDoesNotBreakIt() {
        recompute(day(1), day(0), TODAY.plusDays(1).atTime(0, 30));
        assertThat(user.getCurrentStreak()).isEqualTo(2);
    }

    @Test
    @DisplayName("Read as of today: a stored streak lapses once the last workout is older than yesterday")
    void storedStreakLapses() {
        recompute(day(10), day(9), day(8));
        user.setCurrentStreak(3);           // as it was stored eight days ago
        assertThat(StreakService.currentAsOf(user, TODAY)).isZero();
        assertThat(StreakService.currentAsOf(user, day(7).toLocalDate())).isEqualTo(3);  // the next day: still alive
    }

    @Test
    @DisplayName("Read as of today: a streak through yesterday or today is reported as stored")
    void liveStreakIsReported() {
        recompute(day(2), day(1));
        assertThat(StreakService.currentAsOf(user, TODAY)).isEqualTo(2);
        user.setLastActivityDate(null);
        assertThat(StreakService.currentAsOf(user, TODAY)).isZero();
    }
}

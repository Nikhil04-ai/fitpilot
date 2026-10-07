package com.project.fitness.service;

import com.project.fitness.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for StreakService — Feature 2
 * No Spring context, no DB — pure logic tests.
 */
@DisplayName("StreakService Tests")
class StreakServiceTest {

    private StreakService streakService;
    private User user;

    @BeforeEach
    void setUp() {
        streakService = new StreakService();
        user = User.builder()
                .id("user-1")
                .currentStreak(0)
                .longestStreak(0)
                .build();
    }

    // ── First activity ────────────────────────────────────────────────────────

    @Test
    @DisplayName("First activity ever: streak should become 1")
    void firstActivity_setsStreakToOne() {
        user.setLastActivityDate(null);

        streakService.updateStreak(user);

        assertThat(user.getCurrentStreak()).isEqualTo(1);
        assertThat(user.getLongestStreak()).isEqualTo(1);
        assertThat(user.getLastActivityDate()).isEqualTo(LocalDate.now());
    }

    // ── Consecutive day ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Activity yesterday + activity today: streak increments")
    void consecutiveDay_incrementsStreak() {
        user.setLastActivityDate(LocalDate.now().minusDays(1));
        user.setCurrentStreak(3);
        user.setLongestStreak(3);

        streakService.updateStreak(user);

        assertThat(user.getCurrentStreak()).isEqualTo(4);
        assertThat(user.getLongestStreak()).isEqualTo(4);
    }

    // ── Same day logged twice ─────────────────────────────────────────────────

    @Test
    @DisplayName("Two activities on the same day: streak does NOT change")
    void sameDay_noChange() {
        user.setLastActivityDate(LocalDate.now());
        user.setCurrentStreak(5);
        user.setLongestStreak(10);

        streakService.updateStreak(user);

        assertThat(user.getCurrentStreak()).isEqualTo(5);
        assertThat(user.getLongestStreak()).isEqualTo(10);
    }

    // ── Gap > 1 day resets streak ─────────────────────────────────────────────

    @Test
    @DisplayName("2-day gap resets streak to 1")
    void twoDayGap_resetsStreak() {
        user.setLastActivityDate(LocalDate.now().minusDays(2));
        user.setCurrentStreak(10);
        user.setLongestStreak(10);

        streakService.updateStreak(user);

        assertThat(user.getCurrentStreak()).isEqualTo(1);
        assertThat(user.getLongestStreak()).isEqualTo(10); // longest stays
    }

    @Test
    @DisplayName("Week-long gap resets streak to 1")
    void weekGap_resetsStreak() {
        user.setLastActivityDate(LocalDate.now().minusDays(7));
        user.setCurrentStreak(20);
        user.setLongestStreak(20);

        streakService.updateStreak(user);

        assertThat(user.getCurrentStreak()).isEqualTo(1);
        assertThat(user.getLongestStreak()).isEqualTo(20);
    }

    // ── Longest streak logic ──────────────────────────────────────────────────

    @Test
    @DisplayName("New streak beats previous longest: longestStreak updates")
    void newBestStreak_updatesLongest() {
        user.setLastActivityDate(LocalDate.now().minusDays(1));
        user.setCurrentStreak(7);
        user.setLongestStreak(7);

        streakService.updateStreak(user);

        assertThat(user.getCurrentStreak()).isEqualTo(8);
        assertThat(user.getLongestStreak()).isEqualTo(8);
    }

    @Test
    @DisplayName("Current streak below longest: longestStreak stays unchanged")
    void currentBelowLongest_longestUnchanged() {
        user.setLastActivityDate(LocalDate.now().minusDays(1));
        user.setCurrentStreak(3);
        user.setLongestStreak(15);

        streakService.updateStreak(user);

        assertThat(user.getCurrentStreak()).isEqualTo(4);
        assertThat(user.getLongestStreak()).isEqualTo(15);
    }
}

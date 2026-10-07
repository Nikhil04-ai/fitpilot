package com.project.fitness.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard that makes Flyway worth having (PLAN P1-2).
 *
 * <p>Everything else in the suite runs on {@code ddl-auto=create-drop}, where Hibernate
 * builds the schema from the entities — so the entities can never disagree with it, and
 * a missing migration is invisible. This class is the opposite way round: Flyway builds
 * the schema from {@code db/migration}, then Hibernate is set to {@code validate} and
 * has to accept it.
 *
 * <p>That means the context refresh below <em>is</em> the assertion. Add a field or an
 * entity without writing the matching {@code V<n>__} file and this test fails to start
 * with a schema-validation error naming the column — which is exactly the drift that
 * {@code ddl-auto=update} used to hide until it reached the Aiven instance.
 *
 * <p>Its own H2 database ({@code MODE=MySQL}, separate URL from the shared {@code testdb})
 * so migrating here cannot disturb the create-drop tests.
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@ActiveProfiles({"test", "flywaytest"})
@DisplayName("Flyway migrations")
class FlywayMigrationTest {

    /** Every table the entity model expects. Kept explicit so a dropped one is loud. */
    private static final List<String> EXPECTED_TABLES = List.of(
            "activity", "body_metrics_log", "challenge", "challenge_leaderboard_entry",
            "challenge_participant", "friendship",
            "exercise", "exercise_personal_best", "fitness_user", "goal", "meal_log",
            "muscle_recovery", "notification", "pain_log", "password_reset_token", "personal_best",
            "weekly_digest",
            "recommendation", "refresh_token", "sleep_log", "user_plan_enrollment",
            "water_log", "workout_plan", "workout_plan_day");

    @Autowired private Flyway flyway;
    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("apply cleanly and leave the schema at the latest version")
    void migrationsApply() {
        MigrationInfo current = flyway.info().current();
        assertThat(current).as("no migration was applied").isNotNull();

        // Not pinned to a number — this has to keep passing as migrations are added.
        String latest = flyway.info().all()[flyway.info().all().length - 1].getVersion().toString();
        assertThat(current.getVersion().toString()).isEqualTo(latest);

        assertThat(flyway.info().pending())
                .as("a migration was left unapplied")
                .isEmpty();
    }

    /**
     * Versions must be unique and strictly increasing — but not gapless.
     *
     * <p>Vendor-specific migrations (db/vendor/mysql) share this version namespace
     * while only resolving on their own database, so the sequence H2 sees legitimately
     * skips those numbers. What must never happen is two migrations claiming the same
     * version, which Flyway refuses to run at all on whichever database sees both.
     */
    @Test
    @DisplayName("are versioned, uniquely numbered and strictly increasing")
    void migrationsAreWellFormed() {
        List<MigrationVersion> versions = new ArrayList<>();
        for (MigrationInfo info : flyway.info().all()) {
            assertThat(info.getVersion())
                    .as("repeatable migrations aren't used here — %s", info.getScript())
                    .isNotNull();
            versions.add(info.getVersion());
        }

        assertThat(versions).as("duplicate version number").doesNotHaveDuplicates();

        for (int i = 1; i < versions.size(); i++) {
            assertThat(versions.get(i))
                    .as("migrations must be resolved in increasing version order")
                    .isGreaterThan(versions.get(i - 1));
        }
    }

    @Test
    @DisplayName("create every table the entity model maps")
    void everyEntityTableExists() throws Exception {
        List<String> actual = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            try (ResultSet rs = meta.getTables(c.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    actual.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        assertThat(actual).containsAll(EXPECTED_TABLES);
    }
}

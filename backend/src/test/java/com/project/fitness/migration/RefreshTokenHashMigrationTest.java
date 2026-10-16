package com.project.fitness.migration;

import com.project.fitness.security.TokenHash;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10 hashes the refresh tokens already in the table (PLAN P5-13).
 *
 * <p>{@code FlywayMigrationTest} proves the migrations build a schema the entities accept,
 * but it starts from an empty database, so V10 has nothing to convert there. This one
 * stops at V9, stores tokens the old way, and then lets V10 run: a session that was live
 * before the upgrade has to still be found by its hash after it.
 *
 * <p>Its own H2 database ({@code MODE=MySQL}), driven by Flyway directly, without Spring.
 */
@DisplayName("V10: refresh tokens hashed in place")
class RefreshTokenHashMigrationTest {

    private static final String URL = "jdbc:h2:mem:v10_hash;DB_CLOSE_DELAY=-1;MODE=MySQL";

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    @DisplayName("a token stored raw before the upgrade is stored as its SHA-256 after it; a hashed one is left alone")
    void existingTokensAreHashed() throws Exception {
        flyway("9").migrate();

        String raw = "3f2b8c1e-7a4d-4e9b-9c6f-1d2e3f4a5b6c";        // a UUID, as issued
        String alreadyHashed = TokenHash.of("from-a-partial-earlier-run");
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("insert into fitness_user (id, created_at, updated_at) values ('u-1', now(), now())");
            s.execute("insert into refresh_token (id, user_id, token, created_at, expires_at, revoked) values "
                    + "('rt-raw', 'u-1', '" + raw + "', now(), now(), false), "
                    + "('rt-hashed', 'u-1', '" + alreadyHashed + "', now(), now(), false)");
        }

        flyway("latest").migrate();

        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select id, token_hash from refresh_token order by id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo("rt-hashed");
            assertThat(rs.getString("token_hash")).isEqualTo(alreadyHashed);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo("rt-raw");
            assertThat(rs.getString("token_hash")).isEqualTo(TokenHash.of(raw));
        }
    }
}

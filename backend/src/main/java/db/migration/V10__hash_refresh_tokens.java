package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * V10 — Store refresh tokens hashed (PLAN P5-13).
 *
 * <p>Refresh tokens were stored exactly as issued, so a read of {@code refresh_token}
 * (a backup, a leaked dump) handed over a 30-day session for every account. Reset tokens
 * were already stored as a SHA-256. The column becomes {@code token_hash}, and every
 * token already in it is replaced by its hash, so nobody is signed out by the upgrade.
 *
 * <p>Java rather than SQL because SHA-256 is {@code SHA2(x, 256)} on MySQL and
 * {@code HASH('SHA-256', x)} on H2: this runs the same on both, including the H2 that
 * {@code FlywayMigrationTest} migrates. It deliberately repeats the digest rather than
 * calling the application's {@code TokenHash}: a migration has to go on producing the
 * same result whatever later happens to the code around it.
 *
 * <p>Safe to run again after a partial failure (MySQL commits DDL as it goes): the
 * rename is skipped once done, and a value that is already a hash is left alone.
 */
public class V10__hash_refresh_tokens extends BaseJavaMigration {

    private static final Pattern HASHED = Pattern.compile("[0-9a-f]{64}");

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();

        if (hasColumn(c, "refresh_token", "token")) {
            try (Statement s = c.createStatement()) {
                s.execute("alter table refresh_token rename column token to token_hash");
            }
        }

        Map<String, String> tokens = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select id, token_hash from refresh_token")) {
            while (rs.next()) tokens.put(rs.getString(1), rs.getString(2));
        }

        try (PreparedStatement update = c.prepareStatement(
                "update refresh_token set token_hash = ? where id = ?")) {
            for (Map.Entry<String, String> row : tokens.entrySet()) {
                if (HASHED.matcher(row.getValue()).matches()) continue;
                update.setString(1, sha256(row.getValue()));
                update.setString(2, row.getKey());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    /** Case-insensitive, since H2 reports names in upper case and MySQL as written. */
    private static boolean hasColumn(Connection c, String table, String column) throws Exception {
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet rs = meta.getColumns(c.getCatalog(), null, "%", "%")) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String sha256(String raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    }
}

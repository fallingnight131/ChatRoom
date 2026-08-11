package com.fallingnight.chat.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fallingnight.chat.persistence.postgres.PostgresMigrator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityMigrationPostgresTest {
    private static final String URL = System.getenv("CHATROOM_TEST_POSTGRES_URL");
    private static final String USER = System.getenv("CHATROOM_TEST_POSTGRES_USER");
    private static final String PASSWORD = System.getenv("CHATROOM_TEST_POSTGRES_PASSWORD");

    @TempDir
    Path temporary;

    @Test
    void runsBackupPreviewAndExplicitApplyAgainstRealPostgres() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(),
                "set CHATROOM_TEST_POSTGRES_URL to run migration command tests");
        new PostgresMigrator(URL, USER, PASSWORD).migrate();
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE chat.account, chat.identity_import_run CASCADE");
        }

        Path source = temporary.resolve("source.db");
        Path backup = temporary.resolve("backup.db");
        Path proof = temporary.resolve("proof.properties");
        createSource(source);
        CommandResult backupResult = run(
                new String[] {"backup", source.toString(), backup.toString(), proof.toString()},
                Map.of());
        assertEquals(0, backupResult.status());
        String fingerprint = value(backupResult.output(), "source_fingerprint_sha256");

        Map<String, String> database = Map.of(
                "CHATROOM_MIGRATION_POSTGRES_URL", URL,
                "CHATROOM_MIGRATION_POSTGRES_USER", USER,
                "CHATROOM_MIGRATION_POSTGRES_PASSWORD", PASSWORD);
        CommandResult preview = run(
                new String[] {"preview", source.toString()}, database);
        assertEquals(0, preview.status());
        assertTrue(preview.output().contains("status=READY"));
        assertEquals(0, count("chat.account"));

        CommandResult applied = run(new String[] {
                "apply", source.toString(), backup.toString(), proof.toString(), fingerprint
        }, database);
        assertEquals(0, applied.status());
        assertTrue(applied.output().contains("status=APPLIED"));
        assertTrue(applied.output().contains("inserted_rows=1"));
        assertFalse(applied.output().contains(source.toString()));
        assertEquals(1, count("chat.account"));
        assertEquals(1, count("chat.legacy_v1_account_map"));
        assertEquals(1, count("chat.identity_import_run"));

        CommandResult repeated = run(new String[] {
                "apply", source.toString(), backup.toString(), proof.toString(), fingerprint
        }, database);
        assertEquals(0, repeated.status());
        assertTrue(repeated.output().contains("inserted_rows=0"));
        assertEquals(1, count("chat.account"));
        assertEquals(1, count("chat.legacy_v1_account_map"));
        assertEquals(2, count("chat.identity_import_run"));
    }

    private static CommandResult run(String[] args, Map<String, String> environment) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int status = IdentityMigrationMain.run(
                args,
                environment,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8),
                Clock.systemUTC());
        return new CommandResult(
                status,
                output.toString(StandardCharsets.UTF_8),
                error.toString(StandardCharsets.UTF_8));
    }

    private static String value(String output, String key) {
        return output.lines()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1))
                .findFirst()
                .orElseThrow();
    }

    private static int count(String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void createSource(Path source) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, "
                    + "username TEXT UNIQUE NOT NULL, display_name TEXT, "
                    + "password_hash TEXT NOT NULL, salt TEXT NOT NULL, created_at TEXT)");
            statement.execute("INSERT INTO users VALUES (1, 'operator-test', 'Operator Test', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA', '', "
                    + "'2026-01-02 03:04:05')");
        }
    }

    private record CommandResult(int status, String output, String error) {}
}

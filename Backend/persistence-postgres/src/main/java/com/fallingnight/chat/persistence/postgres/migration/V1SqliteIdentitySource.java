package com.fallingnight.chat.persistence.postgres.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reads the current V1 users projection through a query-only SQLite connection. */
public final class V1SqliteIdentitySource {
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "id", "username", "display_name", "password_hash", "salt", "created_at");
    private static final DateTimeFormatter SQLITE_UTC =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final Path database;
    private final V1IdentityImportPlanner planner;

    public V1SqliteIdentitySource(Path database) {
        this(database, new V1IdentityImportPlanner());
    }

    V1SqliteIdentitySource(Path database, V1IdentityImportPlanner planner) {
        this.database = requireReadableDatabase(database);
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    public V1IdentityImportPlan readPlan() {
        try (Connection connection = DriverManager.getConnection(readOnlyUrl(database))) {
            configureReadOnly(connection);
            requireHealthyDatabase(connection);
            requireCurrentUsersSchema(connection);
            return planner.plan(readUsers(connection));
        } catch (SQLException exception) {
            throw new V1IdentitySourceException("V1 identity source read failed", exception);
        }
    }

    private static void configureReadOnly(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
    }

    private static void requireHealthyDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equals(result.getString(1)) || result.next()) {
                throw new V1IdentitySourceException("V1 SQLite quick_check did not pass");
            }
        }
    }

    private static void requireCurrentUsersSchema(Connection connection) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA table_info(users)")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        if (!columns.containsAll(REQUIRED_COLUMNS)) {
            throw new V1IdentitySourceException(
                    "V1 users schema is missing required migrated columns");
        }
    }

    private static List<V1IdentityRow> readUsers(Connection connection) throws SQLException {
        List<V1IdentityRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT id, username, display_name, password_hash, salt, created_at "
                                + "FROM users ORDER BY id")) {
            while (result.next()) {
                rows.add(new V1IdentityRow(
                        result.getLong("id"),
                        result.getString("username"),
                        result.getString("display_name"),
                        result.getString("password_hash"),
                        result.getString("salt"),
                        parseTimestamp(result.getString("created_at"))));
            }
        }
        return List.copyOf(rows);
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // V1 CURRENT_TIMESTAMP uses the SQLite UTC form below.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Offset-less V1 values are interpreted as documented SQLite UTC.
        }
        try {
            return LocalDateTime.parse(value, SQLITE_UTC).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Path requireReadableDatabase(Path value) {
        Objects.requireNonNull(value, "database");
        try {
            Path real = value.toRealPath();
            if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
                throw new V1IdentitySourceException("V1 SQLite source must be a readable file");
            }
            return real;
        } catch (java.io.IOException exception) {
            throw new V1IdentitySourceException("V1 SQLite source is not readable", exception);
        }
    }

    private static String readOnlyUrl(Path database) {
        return "jdbc:sqlite:" + database.toUri() + "?mode=ro";
    }
}

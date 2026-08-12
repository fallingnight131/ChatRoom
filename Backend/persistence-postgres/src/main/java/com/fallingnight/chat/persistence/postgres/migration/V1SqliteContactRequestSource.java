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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads V1 friendship/request facts through WAL-aware query-only SQLite. */
public final class V1SqliteContactRequestSource {
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.of(
            "users", Set.of("id"),
            "friendships", Set.of("user_id1", "user_id2"),
            "friend_requests", Set.of(
                    "id", "from_user_id", "to_user_id", "status", "created_at"));
    private static final DateTimeFormatter SQLITE_UTC =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final Path database;
    private final V1ContactRequestImportPlanner planner;

    public V1SqliteContactRequestSource(Path database) {
        this(database, new V1ContactRequestImportPlanner());
    }

    V1SqliteContactRequestSource(Path database, V1ContactRequestImportPlanner planner) {
        this.database = requireReadableDatabase(database);
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    public V1ContactRequestImportPlan readPlan() {
        try (Connection connection = DriverManager.getConnection(readOnlyUrl(database))) {
            configureReadOnly(connection);
            requireHealthyDatabase(connection);
            requireCurrentSchema(connection);
            return planner.plan(new V1ContactRequestSourceSnapshot(
                    readUserIds(connection), readFriendships(connection), readRequests(connection)));
        } catch (SQLException exception) {
            throw new V1ContactRequestSourceException(
                    "V1 contact request source read failed", exception);
        }
    }

    private static Set<Long> readUserIds(Connection connection) throws SQLException {
        Set<Long> users = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT id FROM users ORDER BY id")) {
            while (result.next()) users.add(result.getLong("id"));
        }
        return Set.copyOf(users);
    }

    private static List<V1ExistingFriendPair> readFriendships(Connection connection)
            throws SQLException {
        List<V1ExistingFriendPair> friendships = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT user_id1, user_id2 FROM friendships ORDER BY user_id1, user_id2")) {
            while (result.next()) {
                friendships.add(new V1ExistingFriendPair(
                        result.getLong("user_id1"), result.getLong("user_id2")));
            }
        }
        return List.copyOf(friendships);
    }

    private static List<V1ContactRequestRow> readRequests(Connection connection)
            throws SQLException {
        List<V1ContactRequestRow> requests = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT id, from_user_id, to_user_id, status, created_at "
                                + "FROM friend_requests ORDER BY id")) {
            while (result.next()) {
                requests.add(new V1ContactRequestRow(
                        result.getLong("id"),
                        result.getLong("from_user_id"),
                        result.getLong("to_user_id"),
                        result.getString("status"),
                        parseTimestamp(result.getString("created_at"))));
            }
        }
        return List.copyOf(requests);
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
                throw new V1ContactRequestSourceException("V1 SQLite quick_check did not pass");
            }
        }
    }

    private static void requireCurrentSchema(Connection connection) throws SQLException {
        for (Map.Entry<String, Set<String>> required : REQUIRED_COLUMNS.entrySet()) {
            Set<String> actual = new HashSet<>();
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(
                            "PRAGMA table_info(" + required.getKey() + ")")) {
                while (result.next()) actual.add(result.getString("name"));
            }
            if (!actual.containsAll(required.getValue())) {
                throw new V1ContactRequestSourceException(
                        "V1 contact request schema is missing required columns");
            }
        }
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value, SQLITE_UTC).toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static Path requireReadableDatabase(Path database) {
        Objects.requireNonNull(database, "database");
        Path absolute = database.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute) || !Files.isReadable(absolute)) {
            throw new V1ContactRequestSourceException("V1 contact request source is not readable");
        }
        return absolute;
    }

    private static String readOnlyUrl(Path database) {
        return "jdbc:sqlite:file:" + database + "?mode=ro";
    }
}

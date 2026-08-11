package com.fallingnight.chat.persistence.postgres;

import java.util.Objects;
import org.flywaydb.core.Flyway;

/** Runs checksum-validated, forward-only migrations for the authoritative V2 store. */
public final class PostgresMigrator {
    private final Flyway flyway;

    public PostgresMigrator(String jdbcUrl, String username, String password) {
        requireJdbcUrl(jdbcUrl);
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .defaultSchema("chat")
                .schemas("chat")
                .createSchemas(true)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .locations("classpath:db/migration")
                .load();
    }

    public int migrate() {
        return flyway.migrate().migrationsExecuted;
    }

    public void validate() {
        flyway.validate();
    }

    private static void requireJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("a PostgreSQL JDBC URL is required");
        }
    }
}

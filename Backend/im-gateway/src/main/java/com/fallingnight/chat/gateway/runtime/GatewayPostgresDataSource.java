package com.fallingnight.chat.gateway.runtime;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/** Creates the owned, bounded PostgreSQL pool from already validated runtime config. */
public final class GatewayPostgresDataSource {
    private static final Duration VALIDATION_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(30);
    private static final Duration KEEPALIVE = Duration.ofMinutes(2);

    private GatewayPostgresDataSource() {}

    public static HikariDataSource create(GatewayRuntimeConfig runtime) {
        return new HikariDataSource(configuration(runtime));
    }

    static HikariConfig configuration(GatewayRuntimeConfig runtime) {
        Objects.requireNonNull(runtime, "runtime");
        HikariConfig config = new HikariConfig();
        config.setPoolName("chat-gateway-postgres");
        config.setJdbcUrl(runtime.postgresUrl());
        config.setUsername(runtime.postgresUser());
        config.setPassword(runtime.postgresPassword());
        config.setMaximumPoolSize(runtime.postgresPoolMaximum());
        config.setMinimumIdle(runtime.postgresPoolMinimumIdle());
        config.setConnectionTimeout(runtime.postgresConnectionTimeout().toMillis());
        config.setValidationTimeout(VALIDATION_TIMEOUT.toMillis());
        config.setIdleTimeout(IDLE_TIMEOUT.toMillis());
        config.setMaxLifetime(MAX_LIFETIME.toMillis());
        config.setKeepaliveTime(KEEPALIVE.toMillis());
        config.setInitializationFailTimeout(runtime.postgresConnectionTimeout().toMillis());
        config.setAutoCommit(true);
        config.addDataSourceProperty("tcpKeepAlive", "true");
        return config;
    }

    static boolean isReady(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (SQLException exception) {
            return false;
        }
    }
}

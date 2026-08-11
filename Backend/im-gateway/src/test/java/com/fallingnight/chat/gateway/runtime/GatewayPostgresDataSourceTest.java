package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayPostgresDataSourceTest {
    @TempDir
    Path temporary;

    @Test
    void buildsBoundedFailFastPoolConfigurationWithoutConnecting() throws Exception {
        GatewayRuntimeConfig runtime = runtime();

        HikariConfig config = GatewayPostgresDataSource.configuration(runtime);

        assertEquals("chat-gateway-postgres", config.getPoolName());
        assertEquals(8, config.getMaximumPoolSize());
        assertEquals(1, config.getMinimumIdle());
        assertEquals(Duration.ofSeconds(5).toMillis(), config.getConnectionTimeout());
        assertEquals(Duration.ofSeconds(3).toMillis(), config.getValidationTimeout());
        assertEquals(Duration.ofMinutes(10).toMillis(), config.getIdleTimeout());
        assertEquals(Duration.ofMinutes(30).toMillis(), config.getMaxLifetime());
        assertEquals(Duration.ofMinutes(2).toMillis(), config.getKeepaliveTime());
        assertEquals(Duration.ofSeconds(5).toMillis(), config.getInitializationFailTimeout());
        assertTrue(config.isAutoCommit());
        assertEquals("true", config.getDataSourceProperties().getProperty("tcpKeepAlive"));
        assertFalse(config.toString().contains("database-secret"));
    }

    private GatewayRuntimeConfig runtime() throws Exception {
        Path certificate = temporary.resolve("certificate.pem");
        Path key = temporary.resolve("key.pem");
        Files.writeString(certificate, "placeholder");
        Files.writeString(key, "placeholder-key");
        Map<String, String> environment = new HashMap<>();
        environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE", certificate.toString());
        environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY", key.toString());
        environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS", "gateway.example.com");
        environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.com");
        environment.put(
                "CHATROOM_POSTGRES_URL",
                "jdbc:postgresql://db.internal/chat?sslmode=verify-full");
        environment.put("CHATROOM_POSTGRES_USER", "chat_gateway");
        environment.put("CHATROOM_POSTGRES_PASSWORD", "database-secret");
        return GatewayRuntimeConfig.fromEnvironment(environment);
    }
}

package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionLimits;
import com.fallingnight.chat.gateway.transport.HttpHostPolicy;
import com.fallingnight.chat.gateway.transport.TrustedProxyPolicy;
import com.fallingnight.chat.gateway.transport.WebSocketEndpointPolicy;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict environment-only gateway runtime configuration with safe defaults. */
public final class GatewayRuntimeConfig {
    private final InetSocketAddress listenerAddress;
    private final InetSocketAddress adminAddress;
    private final Path tlsCertificateChain;
    private final Path tlsPrivateKey;
    private final String tlsPrivateKeyPassword;
    private final String postgresUrl;
    private final String postgresUser;
    private final String postgresPassword;
    private final HttpHostPolicy hostPolicy;
    private final WebSocketEndpointPolicy endpointPolicy;
    private final TrustedProxyPolicy proxyPolicy;
    private final int eventLoopWorkers;
    private final int authenticationWorkers;
    private final int authenticationQueueCapacity;
    private final Duration handshakeTimeout;
    private final Duration authenticationTimeout;
    private final Duration authenticatedIdleTimeout;
    private final AuthenticationAdmissionLimits admissionLimits;

    private GatewayRuntimeConfig(
            InetSocketAddress listenerAddress,
            InetSocketAddress adminAddress,
            Path tlsCertificateChain,
            Path tlsPrivateKey,
            String tlsPrivateKeyPassword,
            String postgresUrl,
            String postgresUser,
            String postgresPassword,
            HttpHostPolicy hostPolicy,
            WebSocketEndpointPolicy endpointPolicy,
            TrustedProxyPolicy proxyPolicy,
            int eventLoopWorkers,
            int authenticationWorkers,
            int authenticationQueueCapacity,
            Duration handshakeTimeout,
            Duration authenticationTimeout,
            Duration authenticatedIdleTimeout,
            AuthenticationAdmissionLimits admissionLimits) {
        this.listenerAddress = listenerAddress;
        this.adminAddress = adminAddress;
        this.tlsCertificateChain = tlsCertificateChain;
        this.tlsPrivateKey = tlsPrivateKey;
        this.tlsPrivateKeyPassword = tlsPrivateKeyPassword;
        this.postgresUrl = postgresUrl;
        this.postgresUser = postgresUser;
        this.postgresPassword = postgresPassword;
        this.hostPolicy = hostPolicy;
        this.endpointPolicy = endpointPolicy;
        this.proxyPolicy = proxyPolicy;
        this.eventLoopWorkers = eventLoopWorkers;
        this.authenticationWorkers = authenticationWorkers;
        this.authenticationQueueCapacity = authenticationQueueCapacity;
        this.handshakeTimeout = handshakeTimeout;
        this.authenticationTimeout = authenticationTimeout;
        this.authenticatedIdleTimeout = authenticatedIdleTimeout;
        this.admissionLimits = admissionLimits;
    }

    public static GatewayRuntimeConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        InetSocketAddress listener = address(
                environment.getOrDefault("CHATROOM_GATEWAY_BIND_ADDRESS", "127.0.0.1"),
                integer(environment, "CHATROOM_GATEWAY_PORT", 9443, 1, 65535),
                "gateway bind address");
        InetSocketAddress admin = address(
                environment.getOrDefault("CHATROOM_GATEWAY_ADMIN_ADDRESS", "127.0.0.1"),
                integer(environment, "CHATROOM_GATEWAY_ADMIN_PORT", 9090, 1, 65535),
                "admin bind address");
        if (!admin.getAddress().isLoopbackAddress()) {
            throw invalid("admin bind address must be loopback");
        }
        Path certificate = regularFile(required(environment,
                "CHATROOM_GATEWAY_TLS_CERTIFICATE"));
        Path privateKey = regularFile(required(environment,
                "CHATROOM_GATEWAY_TLS_PRIVATE_KEY"));
        if (certificate.equals(privateKey)) {
            throw invalid("TLS certificate and private key must be different files");
        }
        String postgresUrl = required(environment, "CHATROOM_POSTGRES_URL");
        if (!postgresUrl.startsWith("jdbc:postgresql://")) {
            throw invalid("PostgreSQL JDBC URL is required");
        }
        String postgresUser = required(environment, "CHATROOM_POSTGRES_USER");
        String postgresPassword = required(environment, "CHATROOM_POSTGRES_PASSWORD");

        List<String> hosts = csv(required(environment, "CHATROOM_GATEWAY_ALLOWED_HOSTS"));
        List<String> origins = csv(required(environment, "CHATROOM_GATEWAY_WEB_ORIGINS"));
        String proxyCidrs = environment.get("CHATROOM_GATEWAY_TRUSTED_PROXY_CIDRS");
        TrustedProxyPolicy proxyPolicy = proxyCidrs == null || proxyCidrs.isBlank()
                ? TrustedProxyPolicy.directOnly()
                : TrustedProxyPolicy.trusted(
                        csv(proxyCidrs),
                        integer(environment, "CHATROOM_GATEWAY_PROXY_MAX_HOPS", 4, 1, 16));

        int eventWorkers = integer(
                environment, "CHATROOM_GATEWAY_EVENT_LOOP_WORKERS", 4, 1, 64);
        int authWorkers = integer(
                environment, "CHATROOM_GATEWAY_AUTH_WORKERS", 4, 1, 64);
        int authQueue = integer(
                environment, "CHATROOM_GATEWAY_AUTH_QUEUE_CAPACITY", 256, 1, 100_000);
        Duration handshakeTimeout = seconds(
                environment, "CHATROOM_GATEWAY_HANDSHAKE_TIMEOUT_SECONDS", 10, 1, 60);
        Duration authenticationTimeout = seconds(
                environment, "CHATROOM_GATEWAY_AUTH_TIMEOUT_SECONDS", 30, 1, 300);
        Duration idleTimeout = seconds(
                environment, "CHATROOM_GATEWAY_IDLE_TIMEOUT_SECONDS", 120, 30, 3600);
        Duration admissionWindow = seconds(
                environment, "CHATROOM_GATEWAY_ADMISSION_WINDOW_SECONDS", 60, 1, 3600);
        AuthenticationAdmissionLimits limits = new AuthenticationAdmissionLimits(
                admissionWindow,
                integer(environment, "CHATROOM_GATEWAY_ATTEMPTS", 600, 1, 1_000_000),
                integer(environment, "CHATROOM_GATEWAY_PEER_ATTEMPTS", 60, 1, 100_000),
                integer(environment, "CHATROOM_GATEWAY_ACCOUNT_ATTEMPTS", 10, 1, 10_000),
                integer(environment, "CHATROOM_GATEWAY_MAX_LIMIT_KEYS", 10_000, 16, 1_000_000));
        return new GatewayRuntimeConfig(
                listener,
                admin,
                certificate,
                privateKey,
                environment.getOrDefault("CHATROOM_GATEWAY_TLS_PRIVATE_KEY_PASSWORD", ""),
                postgresUrl,
                postgresUser,
                postgresPassword,
                new HttpHostPolicy(hosts),
                new WebSocketEndpointPolicy(origins),
                proxyPolicy,
                eventWorkers,
                authWorkers,
                authQueue,
                handshakeTimeout,
                authenticationTimeout,
                idleTimeout,
                limits);
    }

    public InetSocketAddress listenerAddress() {
        return listenerAddress;
    }

    public InetSocketAddress adminAddress() {
        return adminAddress;
    }

    public Path tlsCertificateChain() {
        return tlsCertificateChain;
    }

    public Path tlsPrivateKey() {
        return tlsPrivateKey;
    }

    String tlsPrivateKeyPassword() {
        return tlsPrivateKeyPassword;
    }

    public String postgresUrl() {
        return postgresUrl;
    }

    public String postgresUser() {
        return postgresUser;
    }

    String postgresPassword() {
        return postgresPassword;
    }

    public HttpHostPolicy hostPolicy() {
        return hostPolicy;
    }

    public WebSocketEndpointPolicy endpointPolicy() {
        return endpointPolicy;
    }

    public TrustedProxyPolicy proxyPolicy() {
        return proxyPolicy;
    }

    public int eventLoopWorkers() {
        return eventLoopWorkers;
    }

    public int authenticationWorkers() {
        return authenticationWorkers;
    }

    public int authenticationQueueCapacity() {
        return authenticationQueueCapacity;
    }

    public Duration handshakeTimeout() {
        return handshakeTimeout;
    }

    public Duration authenticationTimeout() {
        return authenticationTimeout;
    }

    public Duration authenticatedIdleTimeout() {
        return authenticatedIdleTimeout;
    }

    public AuthenticationAdmissionLimits admissionLimits() {
        return admissionLimits;
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw invalid("required gateway environment is missing");
        }
        return value;
    }

    private static List<String> csv(String value) {
        List<String> values = Arrays.asList(value.split(",", -1));
        if (values.isEmpty() || values.stream().anyMatch(String::isBlank)) {
            throw invalid("gateway list configuration is invalid");
        }
        return List.copyOf(values);
    }

    private static int integer(
            Map<String, String> environment,
            String key,
            int defaultValue,
            int minimum,
            int maximum) {
        String raw = environment.get(key);
        final int value;
        try {
            value = raw == null ? defaultValue : Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw invalid("gateway numeric configuration is invalid");
        }
        if (value < minimum || value > maximum) {
            throw invalid("gateway numeric configuration is outside its safe range");
        }
        return value;
    }

    private static Duration seconds(
            Map<String, String> environment,
            String key,
            int defaultValue,
            int minimum,
            int maximum) {
        return Duration.ofSeconds(integer(environment, key, defaultValue, minimum, maximum));
    }

    private static Path regularFile(String value) {
        try {
            Path path = Path.of(value).toRealPath();
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw invalid("gateway TLS material must be readable regular files");
            }
            return path;
        } catch (IOException | RuntimeException exception) {
            throw invalid("gateway TLS material is not readable");
        }
    }

    private static InetSocketAddress address(String literal, int port, String name) {
        InetAddress address = numericAddress(literal);
        if (address == null) {
            throw invalid(name + " must be a numeric IP literal");
        }
        return new InetSocketAddress(address, port);
    }

    private static InetAddress numericAddress(String value) {
        if (value == null || value.isBlank() || value.contains("%")) {
            return null;
        }
        boolean ipv4 = value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}");
        boolean ipv6 = value.contains(":") && value.matches("[0-9a-fA-F:]+");
        if (!ipv4 && !ipv6) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            if ((ipv4 && address instanceof Inet4Address)
                    || (ipv6 && address instanceof Inet6Address)) {
                return address;
            }
        } catch (UnknownHostException exception) {
            return null;
        }
        return null;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}

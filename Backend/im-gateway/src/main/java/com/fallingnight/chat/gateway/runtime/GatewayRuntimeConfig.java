package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.gateway.operations.GatewayReleaseIdentity;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionLimits;
import com.fallingnight.chat.gateway.transport.HttpHostPolicy;
import com.fallingnight.chat.gateway.transport.MessageForwardAdmissionLimits;
import com.fallingnight.chat.gateway.transport.TrustedProxyPolicy;
import com.fallingnight.chat.gateway.transport.WebSocketEndpointPolicy;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private final boolean postgresAllowInsecureLocal;
    private final int postgresPoolMaximum;
    private final int postgresPoolMinimumIdle;
    private final Duration postgresConnectionTimeout;
    private final HttpHostPolicy hostPolicy;
    private final WebSocketEndpointPolicy endpointPolicy;
    private final TrustedProxyPolicy proxyPolicy;
    private final int eventLoopWorkers;
    private final int adminWorkers;
    private final int maximumConnections;
    private final int writeBufferLowWaterMark;
    private final int writeBufferHighWaterMark;
    private final int authenticationWorkers;
    private final int authenticationQueueCapacity;
    private final int messagingWorkers;
    private final int messagingQueueCapacity;
    private final Duration handshakeTimeout;
    private final Duration authenticationTimeout;
    private final Duration authenticatedIdleTimeout;
    private final Duration authenticatedHeartbeatInterval;
    private final Duration drainTimeout;
    private final AuthenticationAdmissionLimits admissionLimits;
    private final MessageForwardAdmissionLimits forwardAdmissionLimits;
    private final boolean messageForwardingEnabled;
    private final boolean messageSearchEnabled;
    private final boolean accountBlockingEnabled;
    private final boolean webPushEnabled;
    private final WebPushSubscriptionRuntimeConfig webPushSubscriptions;
    private final WebPushDeliveryRuntimeConfig webPushDelivery;
    private final DistributedGatewayRoutingConfig distributedRouting;
    private final GatewayReleaseIdentity releaseIdentity;

    private GatewayRuntimeConfig(
            InetSocketAddress listenerAddress,
            InetSocketAddress adminAddress,
            Path tlsCertificateChain,
            Path tlsPrivateKey,
            String tlsPrivateKeyPassword,
            String postgresUrl,
            String postgresUser,
            String postgresPassword,
            boolean postgresAllowInsecureLocal,
            int postgresPoolMaximum,
            int postgresPoolMinimumIdle,
            Duration postgresConnectionTimeout,
            HttpHostPolicy hostPolicy,
            WebSocketEndpointPolicy endpointPolicy,
            TrustedProxyPolicy proxyPolicy,
            int eventLoopWorkers,
            int adminWorkers,
            int maximumConnections,
            int writeBufferLowWaterMark,
            int writeBufferHighWaterMark,
            int authenticationWorkers,
            int authenticationQueueCapacity,
            int messagingWorkers,
            int messagingQueueCapacity,
            Duration handshakeTimeout,
            Duration authenticationTimeout,
            Duration authenticatedIdleTimeout,
            Duration authenticatedHeartbeatInterval,
            Duration drainTimeout,
            AuthenticationAdmissionLimits admissionLimits,
            MessageForwardAdmissionLimits forwardAdmissionLimits,
            boolean messageForwardingEnabled,
            boolean messageSearchEnabled,
            boolean accountBlockingEnabled,
            boolean webPushEnabled,
            WebPushSubscriptionRuntimeConfig webPushSubscriptions,
            WebPushDeliveryRuntimeConfig webPushDelivery,
            DistributedGatewayRoutingConfig distributedRouting,
            GatewayReleaseIdentity releaseIdentity) {
        this.listenerAddress = listenerAddress;
        this.adminAddress = adminAddress;
        this.tlsCertificateChain = tlsCertificateChain;
        this.tlsPrivateKey = tlsPrivateKey;
        this.tlsPrivateKeyPassword = tlsPrivateKeyPassword;
        this.postgresUrl = postgresUrl;
        this.postgresUser = postgresUser;
        this.postgresPassword = postgresPassword;
        this.postgresAllowInsecureLocal = postgresAllowInsecureLocal;
        this.postgresPoolMaximum = postgresPoolMaximum;
        this.postgresPoolMinimumIdle = postgresPoolMinimumIdle;
        this.postgresConnectionTimeout = postgresConnectionTimeout;
        this.hostPolicy = hostPolicy;
        this.endpointPolicy = endpointPolicy;
        this.proxyPolicy = proxyPolicy;
        this.eventLoopWorkers = eventLoopWorkers;
        this.adminWorkers = adminWorkers;
        this.maximumConnections = maximumConnections;
        this.writeBufferLowWaterMark = writeBufferLowWaterMark;
        this.writeBufferHighWaterMark = writeBufferHighWaterMark;
        this.authenticationWorkers = authenticationWorkers;
        this.authenticationQueueCapacity = authenticationQueueCapacity;
        this.messagingWorkers = messagingWorkers;
        this.messagingQueueCapacity = messagingQueueCapacity;
        this.handshakeTimeout = handshakeTimeout;
        this.authenticationTimeout = authenticationTimeout;
        this.authenticatedIdleTimeout = authenticatedIdleTimeout;
        this.authenticatedHeartbeatInterval = authenticatedHeartbeatInterval;
        this.drainTimeout = drainTimeout;
        this.admissionLimits = admissionLimits;
        this.forwardAdmissionLimits = forwardAdmissionLimits;
        this.messageForwardingEnabled = messageForwardingEnabled;
        this.messageSearchEnabled = messageSearchEnabled;
        this.accountBlockingEnabled = accountBlockingEnabled;
        this.webPushEnabled = webPushEnabled;
        this.webPushSubscriptions = Objects.requireNonNull(
                webPushSubscriptions, "webPushSubscriptions");
        this.webPushDelivery = Objects.requireNonNull(webPushDelivery, "webPushDelivery");
        this.distributedRouting = Objects.requireNonNull(distributedRouting, "distributedRouting");
        this.releaseIdentity = Objects.requireNonNull(releaseIdentity, "releaseIdentity");
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
        boolean allowInsecureLocal = bool(
                environment, "CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", false);
        validatePostgresUrl(postgresUrl, allowInsecureLocal);
        String postgresUser = required(environment, "CHATROOM_POSTGRES_USER");
        String postgresPassword = required(environment, "CHATROOM_POSTGRES_PASSWORD");
        int postgresPoolMaximum = integer(
                environment, "CHATROOM_POSTGRES_POOL_MAXIMUM", 8, 1, 64);
        int postgresPoolMinimumIdle = integer(
                environment, "CHATROOM_POSTGRES_POOL_MINIMUM_IDLE", 1, 0, 64);
        if (postgresPoolMinimumIdle > postgresPoolMaximum) {
            throw invalid("PostgreSQL minimum idle connections exceed pool maximum");
        }
        Duration postgresConnectionTimeout = seconds(
                environment, "CHATROOM_POSTGRES_CONNECTION_TIMEOUT_SECONDS", 5, 1, 30);

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
        int adminWorkers = integer(
                environment, "CHATROOM_GATEWAY_ADMIN_WORKERS", 2, 1, 4);
        int maximumConnections = integer(
                environment, "CHATROOM_GATEWAY_MAX_CONNECTIONS", 10_000, 1, 1_000_000);
        int writeBufferLowWaterMark = integer(
                environment, "CHATROOM_GATEWAY_WRITE_BUFFER_LOW_BYTES", 65_536, 1_024, 8_388_608);
        int writeBufferHighWaterMark = integer(
                environment,
                "CHATROOM_GATEWAY_WRITE_BUFFER_HIGH_BYTES",
                262_144,
                2_048,
                16_777_216);
        if (writeBufferHighWaterMark <= writeBufferLowWaterMark) {
            throw invalid("gateway write buffer high water mark must exceed low water mark");
        }
        int authWorkers = integer(
                environment, "CHATROOM_GATEWAY_AUTH_WORKERS", 4, 1, 64);
        int authQueue = integer(
                environment, "CHATROOM_GATEWAY_AUTH_QUEUE_CAPACITY", 256, 1, 100_000);
        int messagingWorkers = integer(
                environment, "CHATROOM_GATEWAY_MESSAGING_WORKERS", 4, 1, 64);
        int messagingQueue = integer(
                environment, "CHATROOM_GATEWAY_MESSAGING_QUEUE_CAPACITY", 512, 1, 100_000);
        Duration handshakeTimeout = seconds(
                environment, "CHATROOM_GATEWAY_HANDSHAKE_TIMEOUT_SECONDS", 10, 1, 60);
        Duration authenticationTimeout = seconds(
                environment, "CHATROOM_GATEWAY_AUTH_TIMEOUT_SECONDS", 30, 1, 300);
        Duration idleTimeout = seconds(
                environment, "CHATROOM_GATEWAY_IDLE_TIMEOUT_SECONDS", 120, 30, 3600);
        Duration heartbeatInterval = seconds(
                environment, "CHATROOM_GATEWAY_HEARTBEAT_INTERVAL_SECONDS", 30, 5, 300);
        if (heartbeatInterval.compareTo(idleTimeout) >= 0) {
            throw invalid("gateway heartbeat interval must be shorter than idle timeout");
        }
        Duration drainTimeout = seconds(
                environment, "CHATROOM_GATEWAY_DRAIN_TIMEOUT_SECONDS", 15, 0, 300);
        Duration admissionWindow = seconds(
                environment, "CHATROOM_GATEWAY_ADMISSION_WINDOW_SECONDS", 60, 1, 3600);
        AuthenticationAdmissionLimits limits = new AuthenticationAdmissionLimits(
                admissionWindow,
                integer(environment, "CHATROOM_GATEWAY_ATTEMPTS", 600, 1, 1_000_000),
                integer(environment, "CHATROOM_GATEWAY_PEER_ATTEMPTS", 60, 1, 100_000),
                integer(environment, "CHATROOM_GATEWAY_ACCOUNT_ATTEMPTS", 10, 1, 10_000),
                integer(environment, "CHATROOM_GATEWAY_MAX_LIMIT_KEYS", 10_000, 16, 1_000_000));
        Duration forwardWindow = seconds(
                environment, "CHATROOM_GATEWAY_FORWARD_WINDOW_SECONDS", 60, 1, 3600);
        MessageForwardAdmissionLimits forwardLimits = new MessageForwardAdmissionLimits(
                forwardWindow,
                integer(environment, "CHATROOM_GATEWAY_FORWARD_ATTEMPTS", 120, 1, 10_000),
                integer(environment, "CHATROOM_GATEWAY_FORWARD_MAX_KEYS",
                        10_000, 16, 1_000_000));
        boolean messageForwardingEnabled = bool(
                environment, "CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED", false);
        boolean messageSearchEnabled = bool(
                environment, "CHATROOM_GATEWAY_MESSAGE_SEARCH_ENABLED", false);
        boolean accountBlockingEnabled = bool(
                environment, "CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED", false);
        boolean webPushEnabled = bool(
                environment, "CHATROOM_GATEWAY_WEB_PUSH_ENABLED", false);
        WebPushSubscriptionRuntimeConfig webPushSubscriptions =
                WebPushSubscriptionRuntimeConfig.fromEnvironment(
                        environment, webPushEnabled, origins);
        return new GatewayRuntimeConfig(
                listener,
                admin,
                certificate,
                privateKey,
                environment.getOrDefault("CHATROOM_GATEWAY_TLS_PRIVATE_KEY_PASSWORD", ""),
                postgresUrl,
                postgresUser,
                postgresPassword,
                allowInsecureLocal,
                postgresPoolMaximum,
                postgresPoolMinimumIdle,
                postgresConnectionTimeout,
                new HttpHostPolicy(hosts),
                new WebSocketEndpointPolicy(origins),
                proxyPolicy,
                eventWorkers,
                adminWorkers,
                maximumConnections,
                writeBufferLowWaterMark,
                writeBufferHighWaterMark,
                authWorkers,
                authQueue,
                messagingWorkers,
                messagingQueue,
                handshakeTimeout,
                authenticationTimeout,
                idleTimeout,
                heartbeatInterval,
                drainTimeout,
                limits,
                forwardLimits,
                messageForwardingEnabled,
                messageSearchEnabled,
                accountBlockingEnabled,
                webPushEnabled,
                webPushSubscriptions,
                WebPushDeliveryRuntimeConfig.fromEnvironment(
                        environment, webPushSubscriptions.enabled()),
                DistributedGatewayRoutingConfig.fromEnvironment(environment),
                GatewayReleaseIdentity.fromEnvironment(environment));
    }

    public InetSocketAddress listenerAddress() {
        return listenerAddress;
    }

    public GatewayReleaseIdentity releaseIdentity() {
        return releaseIdentity;
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

    boolean postgresAllowInsecureLocal() {
        return postgresAllowInsecureLocal;
    }

    public int postgresPoolMaximum() {
        return postgresPoolMaximum;
    }

    public int postgresPoolMinimumIdle() {
        return postgresPoolMinimumIdle;
    }

    public Duration postgresConnectionTimeout() {
        return postgresConnectionTimeout;
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

    public int adminWorkers() {
        return adminWorkers;
    }

    public int maximumConnections() {
        return maximumConnections;
    }

    public int writeBufferLowWaterMark() {
        return writeBufferLowWaterMark;
    }

    public int writeBufferHighWaterMark() {
        return writeBufferHighWaterMark;
    }

    public int authenticationWorkers() {
        return authenticationWorkers;
    }

    public int authenticationQueueCapacity() {
        return authenticationQueueCapacity;
    }

    public int messagingWorkers() {
        return messagingWorkers;
    }

    public int messagingQueueCapacity() {
        return messagingQueueCapacity;
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

    public Duration authenticatedHeartbeatInterval() {
        return authenticatedHeartbeatInterval;
    }

    public Duration drainTimeout() {
        return drainTimeout;
    }

    public AuthenticationAdmissionLimits admissionLimits() {
        return admissionLimits;
    }

    public MessageForwardAdmissionLimits forwardAdmissionLimits() {
        return forwardAdmissionLimits;
    }

    public boolean messageForwardingEnabled() {
        return messageForwardingEnabled;
    }

    public boolean messageSearchEnabled() {
        return messageSearchEnabled;
    }

    public boolean accountBlockingEnabled() {
        return accountBlockingEnabled;
    }

    public boolean webPushEnabled() {
        return webPushEnabled;
    }

    public WebPushSubscriptionRuntimeConfig webPushSubscriptions() {
        return webPushSubscriptions;
    }

    public WebPushDeliveryRuntimeConfig webPushDelivery() {
        return webPushDelivery;
    }

    public DistributedGatewayRoutingConfig distributedRouting() {
        return distributedRouting;
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

    private static boolean bool(
            Map<String, String> environment, String key, boolean defaultValue) {
        String raw = environment.get(key);
        if (raw == null) {
            return defaultValue;
        }
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        throw invalid("gateway boolean configuration is invalid");
    }

    private static void validatePostgresUrl(String jdbcUrl, boolean allowInsecureLocal) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw invalid("PostgreSQL JDBC URL is required");
        }
        final URI uri;
        try {
            uri = new URI(jdbcUrl.substring("jdbc:".length()));
        } catch (URISyntaxException exception) {
            throw invalid("PostgreSQL JDBC URL is invalid");
        }
        if (!"postgresql".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawPath() == null
                || uri.getRawPath().length() < 2
                || uri.getRawFragment() != null) {
            throw invalid("PostgreSQL JDBC URL is invalid");
        }
        Map<String, String> parameters = postgresQueryParameters(uri.getRawQuery());
        if (parameters.containsKey("user") || parameters.containsKey("password")) {
            throw invalid("PostgreSQL credentials must not be embedded in the JDBC URL");
        }
        boolean verifyFull = "verify-full".equalsIgnoreCase(parameters.get("sslmode"));
        InetAddress databaseAddress = numericAddress(stripIpv6Brackets(uri.getHost()));
        boolean numericLoopback = databaseAddress != null && databaseAddress.isLoopbackAddress();
        if (!verifyFull && !(allowInsecureLocal && numericLoopback)) {
            throw invalid("PostgreSQL TLS verify-full is required");
        }
    }

    private static Map<String, String> postgresQueryParameters(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        Map<String, String> parameters = new HashMap<>();
        for (String parameter : rawQuery.split("&", -1)) {
            String[] pair = parameter.split("=", 2);
            if (pair.length != 2
                    || !pair[0].matches("[A-Za-z][A-Za-z0-9]*")
                    || pair[1].isEmpty()) {
                throw invalid("PostgreSQL JDBC query configuration is invalid");
            }
            String key = pair[0].toLowerCase(Locale.ROOT);
            if (parameters.putIfAbsent(key, pair[1]) != null) {
                throw invalid("PostgreSQL JDBC query keys must be unique");
            }
        }
        return Map.copyOf(parameters);
    }

    private static String stripIpv6Brackets(String value) {
        return value.startsWith("[") && value.endsWith("]")
                ? value.substring(1, value.length() - 1)
                : value;
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

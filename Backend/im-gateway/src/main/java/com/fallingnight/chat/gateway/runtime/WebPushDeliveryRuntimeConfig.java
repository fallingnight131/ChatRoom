package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.gateway.operations.ExactWebPushProviderOriginPolicy;
import com.fallingnight.chat.gateway.operations.WebPushDeliveryLoopBackoff;
import com.fallingnight.chat.gateway.operations.WebPushDeliveryReadinessPolicy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Path-only, exact-default-off configuration for provider delivery composition. */
public record WebPushDeliveryRuntimeConfig(
        boolean enabled,
        Path vapidPrivateKeyFile,
        Path vapidPublicKeyFile,
        URI vapidSubject,
        Duration vapidTokenLifetime,
        Set<String> providerOrigins,
        Duration lease,
        int batchSize,
        WebPushDeliveryLoopBackoff backoff,
        Duration shutdownTimeout,
        WebPushDeliveryReadinessPolicy readinessPolicy) {
    public static final String ENABLED = "CHATROOM_GATEWAY_WEB_PUSH_DELIVERY_ENABLED";
    public static final String VAPID_PRIVATE_KEY = "CHATROOM_WEB_PUSH_VAPID_PRIVATE_KEY";
    public static final String VAPID_PUBLIC_KEY = "CHATROOM_WEB_PUSH_VAPID_PUBLIC_KEY";
    public static final String VAPID_SUBJECT = "CHATROOM_WEB_PUSH_VAPID_SUBJECT";
    public static final String PROVIDER_ORIGINS = "CHATROOM_WEB_PUSH_PROVIDER_ORIGINS";

    public WebPushDeliveryRuntimeConfig {
        Objects.requireNonNull(vapidPrivateKeyFile, "vapidPrivateKeyFile");
        Objects.requireNonNull(vapidPublicKeyFile, "vapidPublicKeyFile");
        Objects.requireNonNull(vapidSubject, "vapidSubject");
        Objects.requireNonNull(vapidTokenLifetime, "vapidTokenLifetime");
        providerOrigins = Set.copyOf(Objects.requireNonNull(providerOrigins, "providerOrigins"));
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(backoff, "backoff");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        Objects.requireNonNull(readinessPolicy, "readinessPolicy");
        if (enabled) {
            if (!vapidPrivateKeyFile.isAbsolute() || !vapidPublicKeyFile.isAbsolute()
                    || !vapidPrivateKeyFile.normalize().equals(vapidPrivateKeyFile)
                    || !vapidPublicKeyFile.normalize().equals(vapidPublicKeyFile)
                    || vapidPrivateKeyFile.equals(vapidPublicKeyFile)) {
                throw new IllegalArgumentException("VAPID key paths must be distinct canonical absolute paths");
            }
            validateSubject(vapidSubject);
            if (vapidTokenLifetime.compareTo(Duration.ofMinutes(1)) < 0
                    || vapidTokenLifetime.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("VAPID token lifetime outside reviewed range");
            }
            new ExactWebPushProviderOriginPolicy(providerOrigins);
            if (lease.compareTo(Duration.ofSeconds(1)) < 0
                    || lease.compareTo(Duration.ofMinutes(5)) > 0
                    || batchSize < 1 || batchSize > 100
                    || shutdownTimeout.compareTo(Duration.ofMillis(100)) < 0
                    || shutdownTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("Web Push delivery runtime bounds are invalid");
            }
        }
    }

    public static WebPushDeliveryRuntimeConfig fromEnvironment(
            Map<String, String> environment,
            boolean subscriptionApiEnabled) {
        Objects.requireNonNull(environment, "environment");
        if (!exactBoolean(environment, ENABLED, false)) return disabled();
        if (!subscriptionApiEnabled) {
            throw new IllegalArgumentException(
                    ENABLED + " requires " + WebPushSubscriptionRuntimeConfig.ENABLED + "=true");
        }
        Path privateKey = path(environment, VAPID_PRIVATE_KEY);
        Path publicKey = path(environment, VAPID_PUBLIC_KEY);
        if (privateKey.equals(publicKey)) {
            throw new IllegalArgumentException("VAPID key paths must be distinct");
        }
        URI subject;
        try {
            subject = URI.create(required(environment, VAPID_SUBJECT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid VAPID subject", exception);
        }
        validateSubject(subject);
        Duration tokenLifetime = seconds(environment,
                "CHATROOM_WEB_PUSH_VAPID_TOKEN_SECONDS", 43_200, 60, 86_400);
        Set<String> origins = providerOrigins(required(environment, PROVIDER_ORIGINS));
        new ExactWebPushProviderOriginPolicy(origins);
        Duration lease = seconds(environment,
                "CHATROOM_WEB_PUSH_DELIVERY_LEASE_SECONDS", 30, 1, 300);
        int batchSize = integer(environment,
                "CHATROOM_WEB_PUSH_DELIVERY_BATCH_SIZE", 100, 1, 100);
        Duration healthyPoll = millis(environment,
                "CHATROOM_WEB_PUSH_HEALTHY_POLL_MILLIS", 1_000, 100, 60_000);
        Duration fullBatchPoll = millis(environment,
                "CHATROOM_WEB_PUSH_FULL_BATCH_POLL_MILLIS", 10, 10, 1_000);
        Duration initialFailure = millis(environment,
                "CHATROOM_WEB_PUSH_INITIAL_FAILURE_MILLIS", 100, 100, 60_000);
        Duration maximumFailure = millis(environment,
                "CHATROOM_WEB_PUSH_MAXIMUM_FAILURE_MILLIS", 30_000, 100, 300_000);
        Duration shutdown = millis(environment,
                "CHATROOM_WEB_PUSH_SHUTDOWN_MILLIS", 5_000, 100, 30_000);
        var backoff = new WebPushDeliveryLoopBackoff(
                fullBatchPoll, healthyPoll, initialFailure, maximumFailure);
        var readiness = new WebPushDeliveryReadinessPolicy(
                integer(environment, "CHATROOM_WEB_PUSH_READY_MAX_PENDING",
                        10_000, 1, 1_000_000),
                seconds(environment, "CHATROOM_WEB_PUSH_READY_MAX_AGE_SECONDS",
                        300, 1, 86_400),
                integer(environment, "CHATROOM_WEB_PUSH_READY_MAX_EXPIRED",
                        100, 0, 100_000),
                integer(environment, "CHATROOM_WEB_PUSH_READY_FAILURE_THRESHOLD",
                        5, 1, 64));
        return new WebPushDeliveryRuntimeConfig(
                true, privateKey, publicKey, subject, tokenLifetime, origins,
                lease, batchSize, backoff, shutdown, readiness);
    }

    private static WebPushDeliveryRuntimeConfig disabled() {
        return new WebPushDeliveryRuntimeConfig(
                false, Path.of("disabled-private"), Path.of("disabled-public"),
                URI.create("mailto:disabled@example.invalid"), Duration.ofMinutes(1),
                Set.of(), Duration.ofSeconds(30), 1,
                new WebPushDeliveryLoopBackoff(
                        Duration.ofMillis(10), Duration.ofSeconds(1),
                        Duration.ofMillis(100), Duration.ofSeconds(30)),
                Duration.ofSeconds(5),
                new WebPushDeliveryReadinessPolicy(
                        1, Duration.ofMinutes(1), 0, 1));
    }

    private static Set<String> providerOrigins(String value) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        for (String part : value.split(",", -1)) {
            if (part.isBlank() || !origins.add(part)) {
                throw new IllegalArgumentException(
                        PROVIDER_ORIGINS + " must contain unique nonblank origins");
            }
        }
        return Set.copyOf(origins);
    }

    private static void validateSubject(URI subject) {
        String value = subject.toASCIIString();
        if (value.isEmpty() || value.length() > 256 || value.indexOf('"') >= 0
                || value.indexOf('\\') >= 0
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)
                || !("mailto".equals(subject.getScheme())
                        || "https".equals(subject.getScheme()))
                || ("https".equals(subject.getScheme())
                        && (subject.getHost() == null || subject.getUserInfo() != null))) {
            throw new IllegalArgumentException("invalid VAPID subject");
        }
    }

    private static Path path(Map<String, String> environment, String name) {
        return Path.of(required(environment, name)).toAbsolutePath().normalize();
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static boolean exactBoolean(
            Map<String, String> environment, String name, boolean fallback) {
        String value = environment.get(name);
        if (value == null) return fallback;
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException(name + " must be exactly true or false");
    }

    private static Duration seconds(
            Map<String, String> environment, String name,
            int fallback, int minimum, int maximum) {
        return Duration.ofSeconds(integer(environment, name, fallback, minimum, maximum));
    }

    private static Duration millis(
            Map<String, String> environment, String name,
            int fallback, int minimum, int maximum) {
        return Duration.ofMillis(integer(environment, name, fallback, minimum, maximum));
    }

    private static int integer(
            Map<String, String> environment, String name,
            int fallback, int minimum, int maximum) {
        String value = environment.get(name);
        int parsed;
        try { parsed = value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    name + " must be in [" + minimum + ", " + maximum + "]");
        }
        return parsed;
    }
}

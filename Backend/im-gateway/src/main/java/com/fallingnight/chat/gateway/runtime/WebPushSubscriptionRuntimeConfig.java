package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.gateway.transport.WebPushHttpApiPolicy;
import com.fallingnight.chat.gateway.transport.WebPushSubscriptionAdmissionLimits;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Path-only, exact-default-off subscription API and key-ring configuration. */
public record WebPushSubscriptionRuntimeConfig(
        boolean enabled,
        String activeEncryptionKeyId,
        Map<String, Path> encryptionKeyFiles,
        Path endpointLookupKeyFile,
        WebPushHttpApiPolicy httpPolicy,
        WebPushSubscriptionAdmissionLimits admissionLimits) {
    public static final String ENABLED = "CHATROOM_GATEWAY_WEB_PUSH_SUBSCRIPTIONS_ENABLED";
    public static final String KEY_DIRECTORY = "CHATROOM_WEB_PUSH_KEY_DIRECTORY";
    public static final String ACTIVE_KEY_ID = "CHATROOM_WEB_PUSH_ACTIVE_ENCRYPTION_KEY_ID";
    public static final String KEY_IDS = "CHATROOM_WEB_PUSH_ENCRYPTION_KEY_IDS";
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int MAX_KEYS = 8;

    public WebPushSubscriptionRuntimeConfig {
        Objects.requireNonNull(activeEncryptionKeyId, "activeEncryptionKeyId");
        encryptionKeyFiles = Map.copyOf(
                Objects.requireNonNull(encryptionKeyFiles, "encryptionKeyFiles"));
        Objects.requireNonNull(endpointLookupKeyFile, "endpointLookupKeyFile");
        Objects.requireNonNull(httpPolicy, "httpPolicy");
        Objects.requireNonNull(admissionLimits, "admissionLimits");
    }

    public static WebPushSubscriptionRuntimeConfig fromEnvironment(
            Map<String, String> environment,
            boolean credentialIssuerEnabled,
            List<String> allowedWebOrigins) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(allowedWebOrigins, "allowedWebOrigins");
        if (!exactBoolean(environment, ENABLED, false)) return disabled();
        if (!credentialIssuerEnabled) {
            throw new IllegalArgumentException(
                    ENABLED + " requires CHATROOM_GATEWAY_WEB_PUSH_ENABLED=true");
        }
        Path directory = Path.of(required(environment, KEY_DIRECTORY))
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException(KEY_DIRECTORY + " must be a non-link directory");
        }
        String activeKeyId = keyId(required(environment, ACTIVE_KEY_ID));
        LinkedHashSet<String> ids = csvKeyIds(required(environment, KEY_IDS));
        if (!ids.contains(activeKeyId)) {
            throw new IllegalArgumentException(ACTIVE_KEY_ID + " must be listed in " + KEY_IDS);
        }
        Map<String, Path> files = new LinkedHashMap<>();
        for (String id : ids) files.put(id, directory.resolve("encryption-" + id + ".key"));
        var limits = new WebPushSubscriptionAdmissionLimits(
                Duration.ofSeconds(integer(environment,
                        "CHATROOM_WEB_PUSH_MUTATION_WINDOW_SECONDS", 60, 1, 3600)),
                integer(environment,
                        "CHATROOM_WEB_PUSH_MUTATION_ATTEMPTS", 10, 1, 10_000),
                integer(environment,
                        "CHATROOM_WEB_PUSH_MUTATION_MAX_KEYS", 10_000, 16, 1_000_000));
        return new WebPushSubscriptionRuntimeConfig(
                true,
                activeKeyId,
                files,
                directory.resolve("endpoint-lookup.key"),
                WebPushHttpApiPolicy.enabled(Set.copyOf(allowedWebOrigins)),
                limits);
    }

    private static WebPushSubscriptionRuntimeConfig disabled() {
        return new WebPushSubscriptionRuntimeConfig(
                false,
                "disabled",
                Map.of(),
                Path.of("disabled"),
                WebPushHttpApiPolicy.DISABLED,
                new WebPushSubscriptionAdmissionLimits(Duration.ofMinutes(1), 10, 16));
    }

    private static boolean exactBoolean(
            Map<String, String> environment, String name, boolean fallback) {
        String value = environment.get(name);
        if (value == null) return fallback;
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException(name + " must be exactly true or false");
    }

    private static LinkedHashSet<String> csvKeyIds(String value) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String part : value.split(",", -1)) {
            String id = keyId(part);
            if (!ids.add(id)) throw new IllegalArgumentException(KEY_IDS + " contains duplicates");
        }
        if (ids.isEmpty() || ids.size() > MAX_KEYS) {
            throw new IllegalArgumentException(KEY_IDS + " must contain 1..8 key IDs");
        }
        return ids;
    }

    private static String keyId(String value) {
        if (!KEY_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid Web Push encryption key ID");
        }
        return value;
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static int integer(
            Map<String, String> environment, String name, int fallback, int minimum, int maximum) {
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

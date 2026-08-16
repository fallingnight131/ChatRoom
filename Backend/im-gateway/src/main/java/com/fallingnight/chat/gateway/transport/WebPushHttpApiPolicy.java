package com.fallingnight.chat.gateway.transport;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Exact-default-off Web Push HTTP policy with a bounded HTTPS Origin allowlist. */
public final class WebPushHttpApiPolicy {
    public static final WebPushHttpApiPolicy DISABLED = new WebPushHttpApiPolicy(false, Set.of());
    public static final int MAX_ORIGINS = 8;

    private final boolean enabled;
    private final Set<String> allowedOrigins;

    private WebPushHttpApiPolicy(boolean enabled, Set<String> allowedOrigins) {
        this.enabled = enabled;
        this.allowedOrigins = allowedOrigins;
    }

    public static WebPushHttpApiPolicy enabled(Set<String> allowedOrigins) {
        Objects.requireNonNull(allowedOrigins, "allowedOrigins");
        if (allowedOrigins.isEmpty() || allowedOrigins.size() > MAX_ORIGINS) {
            throw new IllegalArgumentException("allowedOrigins must contain 1..8 origins");
        }
        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        for (String origin : allowedOrigins) {
            canonical.add(canonicalOrigin(origin));
        }
        if (canonical.size() != allowedOrigins.size()) {
            throw new IllegalArgumentException("allowedOrigins contains duplicates");
        }
        return new WebPushHttpApiPolicy(true, Set.copyOf(canonical));
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean allows(List<String> originHeaders) {
        Objects.requireNonNull(originHeaders, "originHeaders");
        return enabled && originHeaders.size() == 1
                && allowedOrigins.contains(originHeaders.getFirst());
    }

    private static String canonicalOrigin(String value) {
        Objects.requireNonNull(value, "origin");
        try {
            URI origin = new URI(value);
            String host = origin.getHost();
            if (!"https".equals(origin.getScheme()) || host == null || host.isBlank()
                    || !host.equals(host.toLowerCase(Locale.ROOT))
                    || origin.getRawUserInfo() != null || origin.getRawPath().length() != 0
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || origin.getPort() == 443 || !value.equals(origin.toASCIIString())) {
                throw new IllegalArgumentException("origin must be canonical HTTPS authority");
            }
            return value;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("origin must be canonical HTTPS authority", exception);
        }
    }
}

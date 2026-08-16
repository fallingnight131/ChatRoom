package com.fallingnight.chat.gateway.operations;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Exact HTTPS origin allowlist for provider egress. Network egress filtering remains required. */
public final class ExactWebPushProviderOriginPolicy implements Predicate<URI> {
    private static final int MAX_ORIGINS = 32;
    private final Set<String> allowedOrigins;

    public ExactWebPushProviderOriginPolicy(Set<String> allowedOrigins) {
        Objects.requireNonNull(allowedOrigins, "allowedOrigins");
        if (allowedOrigins.isEmpty() || allowedOrigins.size() > MAX_ORIGINS) {
            throw new IllegalArgumentException("allowed provider origins must contain 1..32 values");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : allowedOrigins) normalized.add(configuredOrigin(value));
        if (normalized.size() != allowedOrigins.size()) {
            throw new IllegalArgumentException("allowed provider origins must be unique");
        }
        this.allowedOrigins = Set.copyOf(normalized);
    }

    @Override
    public boolean test(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        try {
            return allowedOrigins.contains(origin(endpoint));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String configuredOrigin(String value) {
        Objects.requireNonNull(value, "allowed provider origin");
        URI uri = URI.create(value);
        String origin = origin(uri);
        if (!origin.equals(value)) {
            throw new IllegalArgumentException("allowed provider origin must be canonical");
        }
        return origin;
    }

    private static String origin(URI uri) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getPort() < -1) {
            throw new IllegalArgumentException("provider origin must be HTTPS");
        }
        try {
            return new URI("https", null, uri.getHost(), uri.getPort(), null, null, null)
                    .toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("invalid provider origin", exception);
        }
    }
}

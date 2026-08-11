package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.ClientPlatform;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Immutable Web/Windows endpoint and browser-origin policy. */
public final class WebSocketEndpointPolicy {
    public static final String WEB_PATH = "/v2/web";
    public static final String WINDOWS_PATH = "/v2/windows";
    private final Set<String> allowedWebOrigins;

    public WebSocketEndpointPolicy(List<String> allowedWebOrigins) {
        Objects.requireNonNull(allowedWebOrigins, "allowedWebOrigins");
        if (allowedWebOrigins.isEmpty() || allowedWebOrigins.size() > 32) {
            throw new IllegalArgumentException("allowed Web origin count must be 1..32");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String origin : allowedWebOrigins) {
            normalized.add(normalizeOrigin(origin));
        }
        if (normalized.size() != allowedWebOrigins.size()) {
            throw new IllegalArgumentException("allowed Web origins must be unique");
        }
        this.allowedWebOrigins = Set.copyOf(normalized);
    }

    public ClientPlatform expectedPlatform(String path, List<String> originValues) {
        List<String> origins = originValues == null ? List.of() : List.copyOf(originValues);
        return switch (path) {
            case WEB_PATH -> {
                if (origins.size() != 1
                        || !allowedWebOrigins.contains(normalizeOrigin(origins.getFirst()))) {
                    throw new IllegalArgumentException("Web origin is not allowed");
                }
                yield ClientPlatform.WEB;
            }
            case WINDOWS_PATH -> {
                if (!origins.isEmpty()) {
                    throw new IllegalArgumentException("Windows endpoint cannot carry Origin");
                }
                yield ClientPlatform.WINDOWS;
            }
            default -> throw new IllegalArgumentException("WebSocket endpoint is unsupported");
        };
    }

    private static String normalizeOrigin(String value) {
        Objects.requireNonNull(value, "origin");
        if (value.length() > 512 || !value.equals(value.trim())) {
            throw new IllegalArgumentException("origin is invalid");
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("origin is invalid", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() == 0
                || uri.getPort() < -1
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("origin must be an HTTPS authority");
        }
        int port = uri.getPort() == 443 ? -1 : uri.getPort();
        try {
            return new URI(
                    "https",
                    null,
                    uri.getHost().toLowerCase(Locale.ROOT),
                    port,
                    null,
                    null,
                    null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("origin is invalid", exception);
        }
    }
}

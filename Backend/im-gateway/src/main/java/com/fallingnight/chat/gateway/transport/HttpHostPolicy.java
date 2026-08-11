package com.fallingnight.chat.gateway.transport;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Exact TLS Host authority allowlist; never performs DNS resolution. */
public final class HttpHostPolicy {
    private final Set<String> allowedAuthorities;

    public HttpHostPolicy(List<String> allowedAuthorities) {
        Objects.requireNonNull(allowedAuthorities, "allowedAuthorities");
        if (allowedAuthorities.isEmpty() || allowedAuthorities.size() > 32) {
            throw new IllegalArgumentException("allowed Host count must be 1..32");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String authority : allowedAuthorities) {
            normalized.add(normalize(authority));
        }
        if (normalized.size() != allowedAuthorities.size()) {
            throw new IllegalArgumentException("allowed Hosts must be unique");
        }
        this.allowedAuthorities = Set.copyOf(normalized);
    }

    public boolean allows(List<String> hostValues) {
        if (hostValues == null || hostValues.size() != 1) {
            return false;
        }
        try {
            return allowedAuthorities.contains(normalize(hostValues.getFirst()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String normalize(String value) {
        Objects.requireNonNull(value, "Host authority");
        if (value.isEmpty() || value.length() > 255 || !value.equals(value.trim())
                || value.contains("/") || value.contains("@")) {
            throw new IllegalArgumentException("Host authority is invalid");
        }
        final URI uri;
        try {
            uri = new URI("https://" + value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Host authority is invalid", exception);
        }
        if (uri.getHost() == null
                || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() == 0
                || uri.getPort() < -1
                || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Host authority is invalid");
        }
        int port = uri.getPort() == 443 ? -1 : uri.getPort();
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        return port == -1 ? host : host + ":" + port;
    }
}

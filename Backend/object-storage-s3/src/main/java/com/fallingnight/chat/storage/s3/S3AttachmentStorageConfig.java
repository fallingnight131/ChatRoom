package com.fallingnight.chat.storage.s3;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import software.amazon.awssdk.regions.Region;

/** Strict non-secret configuration for an inactive S3-compatible attachment store. */
public record S3AttachmentStorageConfig(
        URI endpoint,
        Region region,
        String bucket,
        boolean pathStyleAccess) {
    private static final Pattern REGION = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    public S3AttachmentStorageConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(region, "region");
        bucket = requireText(bucket, "bucket", 255);
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/"))) {
            throw new IllegalArgumentException(
                    "attachment S3 endpoint must be an HTTPS origin");
        }
        if (!REGION.matcher(region.id()).matches()) {
            throw new IllegalArgumentException("attachment S3 region is invalid");
        }
    }

    public static S3AttachmentStorageConfig fromEnvironment(
            Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String endpoint = required(environment, "CHATROOM_ATTACHMENT_S3_ENDPOINT");
        String region = required(environment, "CHATROOM_ATTACHMENT_S3_REGION");
        String bucket = required(environment, "CHATROOM_ATTACHMENT_S3_BUCKET");
        String pathStyle = environment.getOrDefault(
                "CHATROOM_ATTACHMENT_S3_PATH_STYLE", "false");
        if (!pathStyle.equals("true") && !pathStyle.equals("false")) {
            throw new IllegalArgumentException(
                    "CHATROOM_ATTACHMENT_S3_PATH_STYLE must be true or false");
        }
        try {
            return new S3AttachmentStorageConfig(
                    URI.create(endpoint), Region.of(region), bucket,
                    Boolean.parseBoolean(pathStyle));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "invalid attachment S3 configuration", exception);
        }
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required environment value: " + name);
        }
        return value;
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}

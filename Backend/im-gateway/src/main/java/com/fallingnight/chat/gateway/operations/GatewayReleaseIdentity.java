package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.protocol.V2Protocol;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Non-secret immutable release identity used to guard mixed-version rollouts. */
public record GatewayReleaseIdentity(
        String releaseVersion,
        String sourceRevision,
        int protocolVersion,
        int compatibilityEpoch) {
    public static final String RELEASE_VERSION = "CHATROOM_GATEWAY_RELEASE_VERSION";
    public static final String SOURCE_REVISION = "CHATROOM_GATEWAY_SOURCE_REVISION";
    public static final String COMPATIBILITY_EPOCH = "CHATROOM_GATEWAY_COMPATIBILITY_EPOCH";
    private static final String NUMBER = "(?:0|[1-9][0-9]*)";
    private static final String PRERELEASE_IDENTIFIER =
            "(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)";
    private static final Pattern SEMVER = Pattern.compile(
            NUMBER + "\\." + NUMBER + "\\." + NUMBER
                    + "(?:-" + PRERELEASE_IDENTIFIER
                    + "(?:\\." + PRERELEASE_IDENTIFIER + ")*)?");
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");

    public GatewayReleaseIdentity {
        Objects.requireNonNull(releaseVersion, "releaseVersion");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        if (!("development".equals(releaseVersion) && "unknown".equals(sourceRevision))
                && (!SEMVER.matcher(releaseVersion).matches()
                        || !REVISION.matcher(sourceRevision).matches())) {
            throw new IllegalArgumentException("release identity must be development or SemVer plus revision");
        }
        if (protocolVersion != V2Protocol.VERSION) {
            throw new IllegalArgumentException("release protocol version differs from runtime");
        }
        if (compatibilityEpoch < 1 || compatibilityEpoch > 1_000_000) {
            throw new IllegalArgumentException("compatibility epoch must be in 1..1000000");
        }
    }

    public static GatewayReleaseIdentity fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String version = environment.get(RELEASE_VERSION);
        String revision = environment.get(SOURCE_REVISION);
        if ((version == null) != (revision == null)) {
            throw new IllegalArgumentException(
                    "gateway release version and source revision must be configured together");
        }
        int epoch = 1;
        String configuredEpoch = environment.get(COMPATIBILITY_EPOCH);
        if (configuredEpoch != null) {
            try {
                epoch = Integer.parseInt(configuredEpoch);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("compatibility epoch must be an integer", exception);
            }
        }
        return new GatewayReleaseIdentity(
                version == null ? "development" : version,
                revision == null ? "unknown" : revision,
                V2Protocol.VERSION,
                epoch);
    }

    public String json() {
        return "{\"schemaVersion\":1,\"releaseVersion\":\"" + releaseVersion
                + "\",\"sourceRevision\":\"" + sourceRevision
                + "\",\"protocolVersion\":" + protocolVersion
                + ",\"compatibilityEpoch\":" + compatibilityEpoch + "}\n";
    }

    public String prometheus() {
        return "# HELP chat_gateway_release_info Immutable running gateway release identity.\n"
                + "# TYPE chat_gateway_release_info gauge\n"
                + "chat_gateway_release_info{release_version=\"" + releaseVersion
                + "\",source_revision=\"" + sourceRevision
                + "\",protocol_version=\"" + protocolVersion
                + "\",compatibility_epoch=\"" + compatibilityEpoch + "\"} 1\n";
    }
}

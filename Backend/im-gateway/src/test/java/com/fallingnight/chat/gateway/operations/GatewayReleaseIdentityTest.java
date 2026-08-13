package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayReleaseIdentityTest {
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void usesExplicitPairedReleaseIdentityOrHonestDevelopmentDefault() {
        GatewayReleaseIdentity development = GatewayReleaseIdentity.fromEnvironment(Map.of());
        assertEquals("development", development.releaseVersion());
        assertEquals("unknown", development.sourceRevision());
        assertEquals(2, development.protocolVersion());
        assertEquals(1, development.compatibilityEpoch());

        GatewayReleaseIdentity release = GatewayReleaseIdentity.fromEnvironment(Map.of(
                GatewayReleaseIdentity.RELEASE_VERSION, "2.3.4-rc.1",
                GatewayReleaseIdentity.SOURCE_REVISION, REVISION,
                GatewayReleaseIdentity.COMPATIBILITY_EPOCH, "7"));
        assertEquals("2.3.4-rc.1", release.releaseVersion());
        assertEquals(REVISION, release.sourceRevision());
        assertEquals(7, release.compatibilityEpoch());
    }

    @Test
    void rejectsPartialMalformedOrRuntimeIncompatibleIdentity() {
        assertThrows(IllegalArgumentException.class, () ->
                GatewayReleaseIdentity.fromEnvironment(Map.of(
                        GatewayReleaseIdentity.RELEASE_VERSION, "1.0.0")));

        Map<String, String> malformedVersion = configured();
        malformedVersion.put(GatewayReleaseIdentity.RELEASE_VERSION, "v1");
        assertThrows(IllegalArgumentException.class, () ->
                GatewayReleaseIdentity.fromEnvironment(malformedVersion));

        Map<String, String> leadingZeroPrerelease = configured();
        leadingZeroPrerelease.put(GatewayReleaseIdentity.RELEASE_VERSION, "1.0.0-01");
        assertThrows(IllegalArgumentException.class, () ->
                GatewayReleaseIdentity.fromEnvironment(leadingZeroPrerelease));

        Map<String, String> uppercaseRevision = configured();
        uppercaseRevision.put(
                GatewayReleaseIdentity.SOURCE_REVISION, REVISION.toUpperCase());
        assertThrows(IllegalArgumentException.class, () ->
                GatewayReleaseIdentity.fromEnvironment(uppercaseRevision));

        Map<String, String> zeroEpoch = configured();
        zeroEpoch.put(GatewayReleaseIdentity.COMPATIBILITY_EPOCH, "0");
        assertThrows(IllegalArgumentException.class, () ->
                GatewayReleaseIdentity.fromEnvironment(zeroEpoch));
        assertThrows(IllegalArgumentException.class, () ->
                new GatewayReleaseIdentity("1.0.0", REVISION, 1, 1));
    }

    private static Map<String, String> configured() {
        Map<String, String> value = new HashMap<>();
        value.put(GatewayReleaseIdentity.RELEASE_VERSION, "1.0.0");
        value.put(GatewayReleaseIdentity.SOURCE_REVISION, REVISION);
        return value;
    }
}

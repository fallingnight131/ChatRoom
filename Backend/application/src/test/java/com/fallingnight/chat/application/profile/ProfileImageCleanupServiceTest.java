package com.fallingnight.chat.application.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProfileImageCleanupServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final ProfileImageCleanupClaim CLAIM = new ProfileImageCleanupClaim(
            UUID.randomUUID(), "avatars/sha256/" + "00".repeat(32) + ".png", NOW);

    @Test void claimsWithSafetyWindowsThenDeletesAndConfirmsExactToken() {
        AtomicReference<Instant> requested = new AtomicReference<>(), stale = new AtomicReference<>();
        AtomicReference<ProfileImageCleanupClaim> confirmed = new AtomicReference<>();
        ProfileImageCleanupPort port = new StubPort() {
            @Override public List<ProfileImageCleanupClaim> claim(Instant requestBefore,
                    Instant staleBefore, Instant claimedAt, int limit) {
                requested.set(requestBefore); stale.set(staleBefore);
                assertEquals(NOW, claimedAt); assertEquals(10, limit); return List.of(CLAIM);
            }
            @Override public boolean confirmDeleted(ProfileImageCleanupClaim claim, Instant at) {
                confirmed.set(claim); assertEquals(NOW, at); return true;
            }
        };
        var report = service(port, key -> assertEquals(CLAIM.objectKey(), key), 10).runOnce();
        assertEquals(NOW.minus(Duration.ofMinutes(10)), requested.get());
        assertEquals(NOW.minus(Duration.ofMinutes(5)), stale.get());
        assertEquals(CLAIM, confirmed.get());
        assertEquals(new ProfileImageCleanupReport(1, 1, 0, 0), report);
    }

    @Test void releasesClaimAfterProviderFailureAndCountsConfirmationFailure() {
        AtomicBoolean released = new AtomicBoolean();
        ProfileImageCleanupPort providerPort = new StubPort() {
            @Override public List<ProfileImageCleanupClaim> claim(Instant a, Instant b,
                    Instant c, int limit) { return List.of(CLAIM); }
            @Override public boolean release(ProfileImageCleanupClaim claim) {
                released.set(true); return true;
            }
        };
        assertEquals(new ProfileImageCleanupReport(1, 0, 1, 0),
                service(providerPort, key -> { throw new IllegalStateException(); }, 1).runOnce());
        assertTrue(released.get());

        ProfileImageCleanupPort confirmationPort = new StubPort() {
            @Override public List<ProfileImageCleanupClaim> claim(Instant a, Instant b,
                    Instant c, int limit) { return List.of(CLAIM); }
            @Override public boolean confirmDeleted(ProfileImageCleanupClaim claim, Instant at) {
                return false;
            }
        };
        assertEquals(new ProfileImageCleanupReport(1, 0, 0, 1),
                service(confirmationPort, key -> { }, 1).runOnce());
    }

    private static ProfileImageCleanupService service(ProfileImageCleanupPort port,
            ProfileImageObjectDeletionPort objects, int batch) {
        return new ProfileImageCleanupService(port, objects, Duration.ofMinutes(10),
                Duration.ofMinutes(5), batch, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private abstract static class StubPort implements ProfileImageCleanupPort {
        @Override public List<ProfileImageCleanupClaim> claim(Instant a, Instant b,
                Instant c, int limit) { return List.of(); }
        @Override public boolean release(ProfileImageCleanupClaim claim) { return false; }
        @Override public boolean confirmDeleted(ProfileImageCleanupClaim claim, Instant at) {
            return false;
        }
    }
}

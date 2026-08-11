package com.fallingnight.chat.application.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.security.SecretBytes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionResumeServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void delegatesOwnedProofAndReturnsRotatedSession() {
        byte[] proof = new byte[32];
        proof[0] = 7;
        IssuedSession rotated = issuedSession();
        SessionResumeService service = service((sessionId, token, client, now) -> {
            assertEquals(SESSION_ID, sessionId);
            assertEquals(NOW, now);
            assertEquals("device-1", client.clientDeviceId());
            assertArrayEquals(proof, token.withCopy(byte[]::clone));
            return Optional.of(rotated);
        });
        ResumeSessionCommand command = command(proof);

        AuthenticationResult.Established result = assertInstanceOf(
                AuthenticationResult.Established.class, service.resume(command));

        assertSame(rotated, result.session());
        assertFalse(result.credentialUpgradePending());
        assertTrue(command.isClosed());
        result.session().close();
    }

    @Test
    void mapsUnknownExpiredRevokedOrReplayedProofToOneRejection() {
        AtomicBoolean invoked = new AtomicBoolean();
        SessionResumeService service = service((sessionId, token, client, now) -> {
            invoked.set(true);
            return Optional.empty();
        });
        ResumeSessionCommand command = command(new byte[32]);

        assertSame(AuthenticationResult.Rejected.INSTANCE, service.resume(command));
        assertTrue(invoked.get());
        assertTrue(command.isClosed());
    }

    private static SessionResumeService service(SessionResumePort port) {
        return new SessionResumeService(port, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ResumeSessionCommand command(byte[] proof) {
        return new ResumeSessionCommand(
                SESSION_ID,
                proof,
                new ClientDescriptor("device-1", ClientPlatform.WEB, "0.1.0"));
    }

    private static IssuedSession issuedSession() {
        return new IssuedSession(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                SESSION_ID,
                SecretBytes.copyOf(new byte[32]),
                NOW.plusSeconds(3600),
                "Alice");
    }
}

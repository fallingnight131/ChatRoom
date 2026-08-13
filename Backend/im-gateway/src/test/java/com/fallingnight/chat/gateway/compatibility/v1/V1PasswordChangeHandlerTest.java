package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.compatibility.v1.*;
import com.fallingnight.chat.gateway.transport.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1PasswordChangeHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void codecOwnsBothSecretsAndCommandSurvivesDecoderCleanup() {
        var decoded = codec().decode(requestBytes());
        assertEquals(V1JsonPasswordChangeCodec.RequestKind.CHANGE, decoded.kind());
        var command = decoded.toCommand(UUID.randomUUID(), UUID.randomUUID());
        decoded.close(); assertTrue(decoded.isClosed()); assertFalse(command.isClosed());
        command.close(); assertTrue(command.isClosed());
        assertEquals(V1JsonPasswordChangeCodec.RequestKind.MALFORMED, codec().decode(bytes(
                "{\"type\":\"CHANGE_PASSWORD_REQ\",\"data\":{\"oldPassword\":\"old\"}}"
        )).kind());
        assertEquals(V1JsonPasswordChangeCodec.RequestKind.MALFORMED, codec().decode(bytes(
                "{\"type\":\"CHANGE_PASSWORD_REQ\",\"data\":{\"oldPassword\":\"old\","
                        + "\"newPassword\":\"new-password\",\"extra\":true}}" )).kind());
    }

    @Test void bindsAccountAndSessionClearsCommandAndNeverReturnsSecrets() {
        UUID account = UUID.randomUUID(), session = UUID.randomUUID();
        AtomicReference<LegacyV1PasswordChangeCommand> captured = new AtomicReference<>();
        AtomicBoolean admissionSuccess = new AtomicBoolean();
        AuthenticationAdmissionControl admission = new AuthenticationAdmissionControl() {
            @Override public AuthenticationAdmissionDecision acquire(String peer, String username) {
                assertEquals("owner", username); return AuthenticationAdmissionDecision.allow();
            }
            @Override public AuthenticationAdmissionDecision acquireResume(String peer) {
                throw new AssertionError();
            }
            @Override public void recordSuccess(String username) { admissionSuccess.set(true); }
        };
        EmbeddedChannel channel = channel(account, session, command -> {
            assertEquals(account, command.actorAccountId());
            assertEquals(session, command.currentSessionId()); captured.set(command);
            return new LegacyV1PasswordChangeResult.Changed(true, 2, NOW);
        }, Runnable::run, admission);
        try {
            channel.writeInbound(new TextWebSocketFrame(requestText())); channel.runPendingTasks();
            assertTrue(captured.get().isClosed()); assertTrue(admissionSuccess.get());
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"CHANGE_PASSWORD_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"otherSessionsRevoked\":2"));
                assertFalse(response.text().contains("old-password"));
                assertFalse(response.text().contains("new-password"));
                assertFalse(response.text().contains(account.toString()));
                assertFalse(response.text().contains(session.toString()));
            } finally { response.release(); }
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void denialStaysConnectedWhileMalformedAndSaturationClose() {
        AuthenticationAdmissionControl denied = new AuthenticationAdmissionControl() {
            @Override public AuthenticationAdmissionDecision acquire(String peer, String username) {
                return AuthenticationAdmissionDecision.deny(
                        AuthenticationLimitDimension.ACCOUNT, 1000);
            }
            @Override public AuthenticationAdmissionDecision acquireResume(String peer) {
                throw new AssertionError();
            }
            @Override public void recordSuccess(String username) { throw new AssertionError(); }
        };
        EmbeddedChannel limited = channel(UUID.randomUUID(), UUID.randomUUID(), command -> {
            throw new AssertionError();
        }, Runnable::run, denied);
        try {
            limited.writeInbound(new TextWebSocketFrame(requestText()));
            TextWebSocketFrame response = limited.readOutbound();
            try { assertTrue(response.text().contains("RATE_LIMITED")); }
            finally { response.release(); }
            assertTrue(limited.isActive());
        } finally { limited.finishAndReleaseAll(); }

        EmbeddedChannel malformed = channel(UUID.randomUUID(), UUID.randomUUID(), command -> {
            throw new AssertionError();
        }, Runnable::run, AuthenticationAdmissionControl.allowAll());
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"CHANGE_PASSWORD_REQ\",\"data\":{}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release(); assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(UUID.randomUUID(), UUID.randomUUID(), command -> {
            throw new AssertionError();
        }, task -> { throw new RejectedExecutionException(); },
                AuthenticationAdmissionControl.allowAll());
        try {
            saturated.writeInbound(new TextWebSocketFrame(requestText()));
            ((CloseWebSocketFrame) saturated.readOutbound()).release(); assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(UUID account, UUID session,
            LegacyV1PasswordChangeUseCase useCase, java.util.concurrent.Executor executor,
            AuthenticationAdmissionControl admission) {
        EmbeddedChannel channel = new EmbeddedChannel(new V1PasswordChangeHandler(
                useCase, codec(), executor, admission, V1PasswordChangeEventSink.noop()));
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(new LegacyV1AuthenticatedIdentity(
                1, account, UUID.randomUUID(), session, NOW.plusSeconds(60),
                "owner", "Owner", false));
        return channel;
    }
    private static V1JsonPasswordChangeCodec codec() {
        return new V1JsonPasswordChangeCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static byte[] requestBytes() { return bytes(requestText()); }
    private static String requestText() {
        return "{\"type\":\"CHANGE_PASSWORD_REQ\",\"data\":{"
                + "\"oldPassword\":\"old-password\",\"newPassword\":\"new-password\"}}";
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}

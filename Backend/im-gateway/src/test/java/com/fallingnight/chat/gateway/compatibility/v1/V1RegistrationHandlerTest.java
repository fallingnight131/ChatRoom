package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.compatibility.v1.*;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionControl;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import java.time.*;
import org.junit.jupiter.api.Test;

final class V1RegistrationHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    @Test void returnsCompatibleSecretFreeSuccessAndPassesLoginFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new V1RegistrationHandler(command -> {
            assertEquals("alice_01", command.username()); assertEquals("Alice", command.displayName());
            return new LegacyV1RegistrationResult.Registered(
                    42, "alice_01", "Alice", false, NOW);
        }, codec(), Runnable::run, AuthenticationAdmissionControl.allowAll(),
                V1RegistrationEventSink.noop()));
        try {
            channel.writeInbound(request()); channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"REGISTER_RSP\""));
                assertTrue(response.text().contains("\"userId\":42"));
                assertFalse(response.text().contains("safe-password"));
            } finally { response.release(); }
            TextWebSocketFrame login = new TextWebSocketFrame(
                    "{\"type\":\"LOGIN_REQ\",\"data\":{}}");
            channel.writeInbound(login); assertSame(login, channel.readInbound()); login.release();
        } finally { channel.finishAndReleaseAll(); }
    }
    @Test void malformedRegistrationCloses() {
        EmbeddedChannel channel = new EmbeddedChannel(new V1RegistrationHandler(command -> {
            throw new AssertionError();
        }, codec(), Runnable::run, AuthenticationAdmissionControl.allowAll(),
                V1RegistrationEventSink.noop()));
        try {
            channel.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"REGISTER_REQ\",\"data\":{}}"));
            ((CloseWebSocketFrame) channel.readOutbound()).release(); assertFalse(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }
    private static V1JsonRegistrationCodec codec() {
        return new V1JsonRegistrationCodec(Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame("{\"type\":\"REGISTER_REQ\",\"data\":{"
                + "\"username\":\"alice_01\",\"displayName\":\"Alice\","
                + "\"password\":\"safe-password\"}}");
    }
}

package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1PendingFriendRequest;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

final class V1PendingFriendRequestHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final LegacyV1AuthenticatedIdentity IDENTITY =
            new LegacyV1AuthenticatedIdentity(1, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), NOW.plusSeconds(60), "owner", "Owner", false);

    @Test
    void returnsExactFieldsForServerBoundRecipient() {
        EmbeddedChannel channel = channel(accountId -> {
            if (!accountId.equals(IDENTITY.accountId())) throw new AssertionError();
            return List.of(new LegacyV1PendingFriendRequest(
                    70, 44, "peer", "Peer", NOW));
        }, Runnable::run);
        try {
            authenticate(channel);
            channel.writeInbound(request());
            channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            assertTrue(response.text().contains("\"type\":\"FRIEND_PENDING_RSP\""));
            assertTrue(response.text().contains("\"requestId\":70"));
            assertTrue(response.text().contains("\"fromUserId\":44"));
            assertTrue(response.text().contains("\"timestamp\":" + NOW.toEpochMilli()));
            response.release();
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void closesWithoutPartialResponseOnMalformedFailureOrSaturation() {
        EmbeddedChannel malformed = channel(ignored -> List.of(), Runnable::run);
        try {
            authenticate(malformed);
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"FRIEND_PENDING_REQ\",\"data\":[]}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel failed = channel(ignored -> {
            throw new IllegalStateException("private");
        }, Runnable::run);
        try {
            authenticate(failed);
            failed.writeInbound(request());
            failed.runPendingTasks();
            ((CloseWebSocketFrame) failed.readOutbound()).release();
            assertFalse(failed.isActive());
        } finally { failed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(ignored -> List.of(), command -> {
            throw new RejectedExecutionException("full");
        });
        try {
            authenticate(saturated);
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.compatibility.v1
                    .LegacyV1PendingFriendRequestUseCase useCase,
            java.util.concurrent.Executor executor) {
        return new EmbeddedChannel(new V1PendingFriendRequestHandler(
                useCase,
                new V1JsonPendingFriendRequestCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                executor,
                V1PendingFriendRequestEventSink.noop()));
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(IDENTITY);
    }

    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame(
                "{\"type\":\"FRIEND_PENDING_REQ\",\"data\":{}}");
    }
}

package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestAcceptanceResult;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

final class V1FriendRequestAcceptanceHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID REQUESTER = UUID.randomUUID();
    private static final LegacyV1AuthenticatedIdentity RECIPIENT =
            new LegacyV1AuthenticatedIdentity(42, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), NOW.plusSeconds(60), "owner", "Owner", false);

    @Test
    void notifiesAuthoritativeRequesterOnlyOnFirstAcceptance() {
        V1AccountConnectionRegistry connections = new V1AccountConnectionRegistry();
        EmbeddedChannel requester = new EmbeddedChannel();
        connections.replace(REQUESTER, requester);
        int[] call = {0};
        EmbeddedChannel recipient = channel((accountId, requestId) ->
                new LegacyV1FriendRequestAcceptanceResult.Accepted(call[0]++ > 0, REQUESTER),
                Runnable::run, connections);
        try {
            authenticate(recipient);
            writeRequest(recipient, "spoofed");
            assertSuccess(recipient);
            requester.runPendingTasks();
            TextWebSocketFrame notification = requester.readOutbound();
            assertTrue(notification.text().contains("\"acceptedBy\":\"owner\""));
            assertFalse(notification.text().contains("spoofed"));
            notification.release();

            writeRequest(recipient, "spoofed-again");
            assertSuccess(recipient);
            requester.runPendingTasks();
            assertNull(requester.readOutbound());
        } finally {
            recipient.finishAndReleaseAll();
            requester.finishAndReleaseAll();
        }
    }

    @Test
    void keepsDomainRejectionUsableButClosesMalformedFailureAndSaturation() {
        V1AccountConnectionRegistry connections = new V1AccountConnectionRegistry();
        EmbeddedChannel rejected = channel((ignoredAccount, ignoredRequest) ->
                LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE,
                Runnable::run, connections);
        try {
            authenticate(rejected);
            writeRequest(rejected, null);
            rejected.runPendingTasks();
            TextWebSocketFrame response = rejected.readOutbound();
            assertTrue(response.text().contains("\"success\":false"));
            response.release();
            assertTrue(rejected.isActive());
        } finally { rejected.finishAndReleaseAll(); }

        EmbeddedChannel malformed = channel((ignoredAccount, ignoredRequest) ->
                LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE,
                Runnable::run, connections);
        try {
            authenticate(malformed);
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"FRIEND_ACCEPT_REQ\",\"data\":{\"requestId\":0}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel failed = channel((ignoredAccount, ignoredRequest) -> {
            throw new IllegalStateException("private");
        }, Runnable::run, connections);
        try {
            authenticate(failed);
            writeRequest(failed, null);
            failed.runPendingTasks();
            ((CloseWebSocketFrame) failed.readOutbound()).release();
            assertFalse(failed.isActive());
        } finally { failed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel((ignoredAccount, ignoredRequest) ->
                LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE, command -> {
                    throw new RejectedExecutionException("full");
                }, connections);
        try {
            authenticate(saturated);
            writeRequest(saturated, null);
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.compatibility.v1
                    .LegacyV1FriendRequestAcceptanceUseCase useCase,
            java.util.concurrent.Executor executor,
            V1AccountConnectionRegistry connections) {
        return new EmbeddedChannel(new V1FriendRequestAcceptanceHandler(
                useCase,
                new V1JsonFriendRequestAcceptanceCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                connections,
                executor,
                V1FriendRequestAcceptanceEventSink.noop()));
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(RECIPIENT);
    }
    private static void writeRequest(EmbeddedChannel channel, String fromUsername) {
        String hint = fromUsername == null ? "" : ",\"fromUsername\":\"" + fromUsername + "\"";
        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"FRIEND_ACCEPT_REQ\",\"data\":{\"requestId\":70"
                        + hint + "}}"));
    }
    private static void assertSuccess(EmbeddedChannel channel) {
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        assertTrue(response.text().contains("\"success\":true"));
        response.release();
    }
}

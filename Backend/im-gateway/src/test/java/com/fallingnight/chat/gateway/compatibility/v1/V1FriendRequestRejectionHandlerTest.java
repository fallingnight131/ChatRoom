package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestRejectionResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestRejectionUseCase;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1FriendRequestRejectionHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final LegacyV1AuthenticatedIdentity IDENTITY =
            new LegacyV1AuthenticatedIdentity(42, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), NOW.plusSeconds(60), "owner", "Owner", false);

    @Test
    void bindsRecipientAndReturnsCompatibleFirstDuplicateAndRejectedResults() {
        AtomicReference<UUID> account = new AtomicReference<>();
        AtomicReference<Long> requestId = new AtomicReference<>();
        var results = new LegacyV1FriendRequestRejectionResult[] {
                new LegacyV1FriendRequestRejectionResult.Accepted(false),
                new LegacyV1FriendRequestRejectionResult.Accepted(true),
                LegacyV1FriendRequestRejectionResult.Rejected.INSTANCE };
        int[] index = {0};
        EmbeddedChannel channel = channel((actualAccount, actualRequest) -> {
            account.set(actualAccount);
            requestId.set(actualRequest);
            return results[index[0]++];
        }, Runnable::run);
        try {
            authenticate(channel);
            assertResponse(channel, true);
            assertEquals(IDENTITY.accountId(), account.get());
            assertEquals(70, requestId.get());
            assertResponse(channel, true);
            assertResponse(channel, false);
            assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void forwardsUnrelatedTrafficAndSuppressesLateCompletion() {
        AtomicReference<Runnable> work = new AtomicReference<>();
        EmbeddedChannel channel = channel((ignoredAccount, ignoredRequest) ->
                new LegacyV1FriendRequestRejectionResult.Accepted(false), work::set);
        try {
            authenticate(channel);
            TextWebSocketFrame unrelated = new TextWebSocketFrame(
                    "{\"type\":\"ROOM_LIST_REQ\",\"data\":{}}");
            channel.writeInbound(unrelated);
            TextWebSocketFrame forwarded = channel.readInbound();
            assertSame(unrelated, forwarded);
            forwarded.release();

            channel.writeInbound(request());
            channel.close();
            work.get().run();
            channel.runPendingTasks();
            assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void closesOnMalformedDependencyFailureAndSaturation() {
        EmbeddedChannel malformed = channel((ignoredAccount, ignoredRequest) ->
                LegacyV1FriendRequestRejectionResult.Rejected.INSTANCE, Runnable::run);
        try {
            authenticate(malformed);
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"FRIEND_REJECT_REQ\",\"data\":{\"requestId\":0}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel failed = channel((ignoredAccount, ignoredRequest) -> {
            throw new IllegalStateException("private");
        }, Runnable::run);
        try {
            authenticate(failed);
            failed.writeInbound(request());
            failed.runPendingTasks();
            ((CloseWebSocketFrame) failed.readOutbound()).release();
            assertFalse(failed.isActive());
        } finally { failed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel((ignoredAccount, ignoredRequest) ->
                LegacyV1FriendRequestRejectionResult.Rejected.INSTANCE, command -> {
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
            LegacyV1FriendRequestRejectionUseCase useCase, Executor executor) {
        return new EmbeddedChannel(new V1FriendRequestRejectionHandler(
                useCase,
                new V1JsonFriendRequestRejectionCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                executor,
                V1FriendRequestRejectionEventSink.noop()));
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(IDENTITY);
    }

    private static void assertResponse(EmbeddedChannel channel, boolean success) {
        channel.writeInbound(request());
        channel.runPendingTasks();
        TextWebSocketFrame response = channel.readOutbound();
        assertTrue(response.text().contains("\"type\":\"FRIEND_REJECT_RSP\""));
        assertTrue(response.text().contains("\"success\":" + success));
        response.release();
    }

    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame(
                "{\"type\":\"FRIEND_REJECT_REQ\",\"data\":{\"requestId\":70}}");
    }
}

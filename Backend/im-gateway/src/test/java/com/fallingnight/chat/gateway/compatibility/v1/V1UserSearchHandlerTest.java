package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1UserSearchResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1UserSearchUser;
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

final class V1UserSearchHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final LegacyV1AuthenticatedIdentity IDENTITY =
            new LegacyV1AuthenticatedIdentity(42, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), NOW.plusSeconds(60), "owner", "Owner", false);

    @Test
    void bindsAuthenticatedExclusionAndReturnsBusinessRejectionWithoutClosing() {
        EmbeddedChannel channel = channel((accountId, keyword) -> {
            if (!accountId.equals(IDENTITY.accountId())) throw new AssertionError();
            return keyword.isBlank()
                    ? LegacyV1UserSearchResult.Rejected.INSTANCE
                    : new LegacyV1UserSearchResult.Found(List.of(
                            new LegacyV1UserSearchUser(44, "peer", "Peer", false)));
        }, Runnable::run);
        try {
            authenticate(channel);
            channel.writeInbound(request("peer"));
            channel.runPendingTasks();
            TextWebSocketFrame found = channel.readOutbound();
            assertTrue(found.text().contains("\"userId\":44"));
            found.release();
            channel.writeInbound(request(" "));
            channel.runPendingTasks();
            TextWebSocketFrame rejected = channel.readOutbound();
            assertTrue(rejected.text().contains("\"success\":false"));
            rejected.release();
            assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void closesOnMalformedDependencyFailureAndSaturation() {
        EmbeddedChannel malformed = channel((account, keyword) ->
                new LegacyV1UserSearchResult.Found(List.of()), Runnable::run);
        try {
            authenticate(malformed);
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"USER_SEARCH_REQ\",\"data\":{\"keyword\":1}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel failed = channel((account, keyword) -> {
            throw new IllegalStateException("private");
        }, Runnable::run);
        try {
            authenticate(failed);
            failed.writeInbound(request("peer"));
            failed.runPendingTasks();
            ((CloseWebSocketFrame) failed.readOutbound()).release();
            assertFalse(failed.isActive());
        } finally { failed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel((account, keyword) ->
                new LegacyV1UserSearchResult.Found(List.of()), command -> {
                    throw new RejectedExecutionException("full");
                });
        try {
            authenticate(saturated);
            saturated.writeInbound(request("peer"));
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.compatibility.v1.LegacyV1UserSearchUseCase useCase,
            java.util.concurrent.Executor executor) {
        return new EmbeddedChannel(new V1UserSearchHandler(
                useCase,
                new V1JsonUserSearchCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                executor,
                V1UserSearchEventSink.noop()));
    }
    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(IDENTITY);
    }
    private static TextWebSocketFrame request(String keyword) {
        return new TextWebSocketFrame(
                "{\"type\":\"USER_SEARCH_REQ\",\"data\":{\"keyword\":\""
                        + keyword + "\"}}");
    }
}

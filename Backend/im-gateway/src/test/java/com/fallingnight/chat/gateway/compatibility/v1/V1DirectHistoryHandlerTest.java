package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryMessage;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryQuery;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryResult;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class V1DirectHistoryHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final LegacyV1AuthenticatedIdentity IDENTITY =
            new LegacyV1AuthenticatedIdentity(42, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), NOW.plusSeconds(60), "owner", "Owner", false);

    @Test
    void bindsAuthenticatedActorNormalizesBoundAndEncodesSequencePage() {
        AtomicReference<LegacyV1DirectHistoryQuery> captured = new AtomicReference<>();
        EmbeddedChannel channel = channel(query -> {
            captured.set(query);
            return new LegacyV1DirectHistoryResult.Page(9, "peer", true,
                    List.of(new LegacyV1DirectHistoryMessage(
                                    102, 2, null, 2, "client-2", "peer", "Peer",
                                    "archive.zip", "file", 501, "archive.zip", 321,
                                    true, "源文件已清理", false, NOW.minusSeconds(6)),
                            new LegacyV1DirectHistoryMessage(
                            101, 1, 3L, 3, "client-1", "peer", "Peer", "hello",
                            "text", true, NOW.minusSeconds(5))), 3, 3, false);
        }, Runnable::run);
        try {
            authenticate(channel);
            channel.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"FRIEND_HISTORY_REQ\",\"data\":{"
                            + "\"friendUsername\":\"peer\",\"count\":999,"
                            + "\"afterSequence\":0}}"));
            channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"FRIEND_HISTORY_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"friendshipId\":9"));
                assertTrue(response.text().contains("\"id\":101"));
                assertTrue(response.text().contains("\"mutationSequence\":3"));
                assertTrue(response.text().contains("\"syncSequence\":3"));
                assertTrue(response.text().contains("\"contentType\":\"text\""));
                assertTrue(response.text().contains("\"contentType\":\"file\""));
                assertTrue(response.text().contains("\"fileId\":-501"));
                assertTrue(response.text().contains("\"fileName\":\"archive.zip\""));
                assertTrue(response.text().contains("\"fileSize\":321"));
                assertTrue(response.text().contains("\"fileCleared\":true"));
                assertTrue(response.text().contains("\"clearReason\":\"源文件已清理\""));
                assertTrue(response.text().contains("\"mode\":\"sequence\""));
                assertTrue(response.text().contains("\"hasMore\":false"));
            } finally { response.release(); }
            assertEquals(IDENTITY.accountId(), captured.get().accountId());
            assertEquals(100, captured.get().limit());
            assertEquals(0L, captured.get().afterSequence());
            assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void returnsBusinessRejectionWithoutClosing() {
        EmbeddedChannel channel = channel(query ->
                LegacyV1DirectHistoryResult.Rejected.INVALID_SEQUENCE_CURSOR, Runnable::run);
        try {
            authenticate(channel);
            channel.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"FRIEND_HISTORY_REQ\",\"data\":{"
                            + "\"friendUsername\":\"peer\",\"afterSequence\":-1}}"));
            channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"success\":false"));
                assertTrue(response.text().contains(
                        "\"errorCode\":\"INVALID_SEQUENCE_CURSOR\""));
            } finally { response.release(); }
            assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void closesOnMalformedDependencyFailureAndSaturation() {
        EmbeddedChannel malformed = channel(query ->
                LegacyV1DirectHistoryResult.Rejected.INVALID_REQUEST, Runnable::run);
        try {
            authenticate(malformed);
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"FRIEND_HISTORY_REQ\",\"data\":{\"count\":1.5}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally { malformed.finishAndReleaseAll(); }

        EmbeddedChannel failed = channel(query -> {
            throw new IllegalStateException("private");
        }, Runnable::run);
        try {
            authenticate(failed);
            failed.writeInbound(latestRequest());
            failed.runPendingTasks();
            ((CloseWebSocketFrame) failed.readOutbound()).release();
            assertFalse(failed.isActive());
        } finally { failed.finishAndReleaseAll(); }

        EmbeddedChannel saturated = channel(query ->
                LegacyV1DirectHistoryResult.Rejected.INVALID_REQUEST, command -> {
                    throw new RejectedExecutionException("full");
                });
        try {
            authenticate(saturated);
            saturated.writeInbound(latestRequest());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally { saturated.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryUseCase useCase,
            java.util.concurrent.Executor executor) {
        return new EmbeddedChannel(new V1DirectHistoryHandler(
                useCase,
                new V1JsonDirectHistoryCodec(Clock.fixed(NOW, ZoneOffset.UTC)),
                executor,
                V1DirectHistoryEventSink.noop()));
    }
    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(IDENTITY);
    }
    private static TextWebSocketFrame latestRequest() {
        return new TextWebSocketFrame(
                "{\"type\":\"FRIEND_HISTORY_REQ\",\"data\":{"
                        + "\"friendUsername\":\"peer\",\"count\":50}}");
    }
}

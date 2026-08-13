package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFile;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFiles;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFilesResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFilesUseCase;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
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

class V1RoomFilesHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();

    @Test
    void bindsActorAndEncodesExactCompatibleUuidFreeFileList() {
        AtomicReference<UUID> observed = new AtomicReference<>();
        EmbeddedChannel channel = channel((actor, room) -> {
            observed.set(actor);
            assertEquals(7, room);
            return new LegacyV1RoomFilesResult.Read(7,
                    new LegacyV1RoomFiles(List.of(
                            new LegacyV1RoomFile(9, "报告.pdf", 123,
                                    Instant.parse("2026-01-02T03:04:05Z"))),
                            123, 4096));
        }, Runnable::run);
        authenticate(channel);
        try {
            channel.writeInbound(request());
            channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"type\":\"ROOM_FILES_RSP\""));
                assertTrue(response.text().contains("\"success\":true"));
                assertTrue(response.text().contains("\"fileId\":9"));
                assertTrue(response.text().contains("\"fileName\":\"报告.pdf\""));
                assertTrue(response.text().contains("\"fileSize\":123"));
                assertTrue(response.text().contains("\"cleared\":false"));
                assertTrue(response.text().contains(
                        "\"createdAt\":\"2026-01-02 03:04:05\""));
                assertTrue(response.text().contains("\"usedFileSpace\":123"));
                assertTrue(response.text().contains("\"maxFileSpace\":4096"));
                assertFalse(response.text().contains(ACTOR.toString()));
                assertFalse(response.text().contains("attachmentId"));
                assertFalse(response.text().contains("objectKey"));
            } finally {
                response.release();
            }
            assertEquals(ACTOR, observed.get());
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void stableAdminRejectionKeepsConnectionUsable() {
        EmbeddedChannel channel = channel((actor, room) ->
                LegacyV1RoomFilesResult.Rejected.ROOM_ADMIN_REQUIRED, Runnable::run);
        authenticate(channel);
        try {
            channel.writeInbound(request());
            channel.runPendingTasks();
            TextWebSocketFrame response = channel.readOutbound();
            try {
                assertTrue(response.text().contains("\"success\":false"));
                assertTrue(response.text().contains(
                        "\"errorCode\":\"ROOM_ADMIN_REQUIRED\""));
                assertTrue(response.text().contains("\"roomId\":7"));
            } finally {
                response.release();
            }
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void malformedFailureSaturationAndConcurrentRequestCloseSafely() {
        EmbeddedChannel malformed = channel((actor, room) -> {
            throw new AssertionError();
        }, Runnable::run);
        authenticate(malformed);
        try {
            malformed.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"ROOM_FILES_REQ\",\"data\":{"
                            + "\"roomId\":7,\"delete\":true}}"));
            ((CloseWebSocketFrame) malformed.readOutbound()).release();
            assertFalse(malformed.isActive());
        } finally {
            malformed.finishAndReleaseAll();
        }

        EmbeddedChannel failed = channel((actor, room) -> {
            throw new IllegalStateException("private");
        }, Runnable::run);
        authenticate(failed);
        try {
            failed.writeInbound(request());
            failed.runPendingTasks();
            CloseWebSocketFrame close = failed.readOutbound();
            assertFalse(close.reasonText().contains("private"));
            close.release();
            assertFalse(failed.isActive());
        } finally {
            failed.finishAndReleaseAll();
        }

        EmbeddedChannel saturated = channel((actor, room) ->
                LegacyV1RoomFilesResult.Rejected.ROOM_ADMIN_REQUIRED, task -> {
                    throw new RejectedExecutionException("full");
                });
        authenticate(saturated);
        try {
            saturated.writeInbound(request());
            ((CloseWebSocketFrame) saturated.readOutbound()).release();
            assertFalse(saturated.isActive());
        } finally {
            saturated.finishAndReleaseAll();
        }

        AtomicReference<Runnable> queued = new AtomicReference<>();
        EmbeddedChannel concurrent = channel((actor, room) ->
                LegacyV1RoomFilesResult.Rejected.ROOM_ADMIN_REQUIRED, queued::set);
        authenticate(concurrent);
        try {
            concurrent.writeInbound(request());
            concurrent.writeInbound(request());
            ((CloseWebSocketFrame) concurrent.readOutbound()).release();
            assertFalse(concurrent.isActive());
        } finally {
            concurrent.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(
            LegacyV1RoomFilesUseCase useCase, java.util.concurrent.Executor executor) {
        return new EmbeddedChannel(new V1RoomFilesHandler(useCase,
                new V1JsonRoomFilesCodec(Clock.fixed(NOW, ZoneOffset.UTC)), executor,
                V1RoomFilesEventSink.noop()));
    }

    private static void authenticate(EmbeddedChannel channel) {
        channel.attr(V1ConnectionAttributes.AUTHENTICATED).set(
                new LegacyV1AuthenticatedIdentity(42, ACTOR, UUID.randomUUID(), UUID.randomUUID(),
                        NOW.plusSeconds(60), "owner", "Owner", false));
    }

    private static TextWebSocketFrame request() {
        return new TextWebSocketFrame(
                "{\"type\":\"ROOM_FILES_REQ\",\"data\":{\"roomId\":7}}");
    }
}

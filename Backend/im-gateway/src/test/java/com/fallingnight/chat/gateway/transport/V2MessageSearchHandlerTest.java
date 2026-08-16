package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.messaging.MessageMention;
import com.fallingnight.chat.application.messaging.MessageSearchPage;
import com.fallingnight.chat.application.messaging.MessageSearchQuery;
import com.fallingnight.chat.application.messaging.MessageSearchResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.ConversationMessageSearchPage;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.SearchConversationMessages;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2MessageSearchHandlerTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DEVICE = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID SESSION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID CONVERSATION = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID MESSAGE = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void bindsAuthenticatedAccountAndFiltersUnnegotiatedHitMetadata() throws Exception {
        AtomicReference<MessageSearchQuery> captured = new AtomicReference<>();
        EmbeddedChannel channel = channel(query -> {
            captured.set(query);
            StoredMessage hit = new StoredMessage(
                    MESSAGE, CONVERSATION, 9, ACCOUNT, DEVICE, "search-hit", 1,
                    "@Bob 聊天".getBytes(StandardCharsets.UTF_8),
                    Instant.parse("2026-08-16T11:00:00Z"),
                    Optional.empty(), 0, Optional.empty(),
                    List.of(new MessageMention(UUID.randomUUID(), 0, 4)), true);
            return new MessageSearchResult.Found(
                    new MessageSearchPage(CONVERSATION, List.of(hit), 9, false));
        }, Runnable::run, true);
        try {
            channel.writeInbound(command(CONVERSATION.toString(), "聊天", 0, 25));
            channel.runPendingTasks();
            Envelope response = channel.readOutbound();
            ConversationMessageSearchPage page =
                    ConversationMessageSearchPage.parseFrom(response.getPayload());
            assertEquals(ACCOUNT, captured.get().accountId());
            assertEquals("聊天", captured.get().literalQuery());
            assertEquals(MESSAGE.toString(), page.getHits(0).getMessageId());
            assertEquals(0, page.getHits(0).getMentionsCount());
            assertFalse(page.getHits(0).getForwarded());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsMissingCapabilityMalformedDeniedAndBusyWithoutUnsafeCalls() throws Exception {
        EmbeddedChannel uncapable = channel(query -> { throw new AssertionError(); },
                Runnable::run, false);
        try {
            uncapable.writeInbound(command(CONVERSATION.toString(), "聊天", 0, 1));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    error(uncapable).getCode());
        } finally {
            uncapable.finishAndReleaseAll();
        }

        EmbeddedChannel capable = channel(
                query -> MessageSearchResult.Rejected.NOT_AUTHORIZED, Runnable::run, true);
        try {
            capable.writeInbound(command("bad", "聊天", 0, 1));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    error(capable).getCode());
            capable.writeInbound(command(CONVERSATION.toString(), " 聊天 ", 0, 1));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    error(capable).getCode());
            capable.writeInbound(command(CONVERSATION.toString(), "聊天", 0, 1));
            capable.runPendingTasks();
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    error(capable).getCode());
        } finally {
            capable.finishAndReleaseAll();
        }

        Executor rejected = task -> { throw new RejectedExecutionException(); };
        EmbeddedChannel busy = channel(query -> { throw new AssertionError(); }, rejected, true);
        try {
            busy.writeInbound(command(CONVERSATION.toString(), "聊天", 0, 1));
            ProtocolError error = error(busy);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED, error.getCode());
            assertTrue(error.getRetryable());
        } finally {
            busy.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.messaging.MessageSearchPort port,
            Executor executor, boolean capable) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new V2MessageSearchHandler(port, executor, MessagingEventSink.noop(), CLOCK));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT, DEVICE, SESSION));
        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(capable
                ? Set.of(ClientCapability.CLIENT_CAPABILITY_MESSAGE_SEARCH) : Set.of());
        return channel;
    }

    private static Envelope command(
            String conversationId, String literal, long before, int limit) {
        SearchConversationMessages payload = SearchConversationMessages.newBuilder()
                .setConversationId(conversationId).setLiteralQuery(literal)
                .setBeforeSequence(before).setLimit(limit).build();
        return Envelope.newBuilder().setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(MessageType.MESSAGE_TYPE_SEARCH_CONVERSATION_MESSAGES_VALUE)
                .setRequestId("request-1").setSessionId(SESSION.toString())
                .setPayload(payload.toByteString()).build();
    }

    private static ProtocolError error(EmbeddedChannel channel) throws Exception {
        Envelope response = channel.readOutbound();
        assertFalse(response.getPayload().isEmpty());
        return ProtocolError.parseFrom(response.getPayload());
    }
}

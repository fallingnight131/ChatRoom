package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.conversation.ConversationParticipant;
import com.fallingnight.chat.application.conversation.ConversationParticipantPage;
import com.fallingnight.chat.application.conversation.ConversationParticipantQuery;
import com.fallingnight.chat.application.conversation.ConversationParticipantResult;
import com.fallingnight.chat.application.conversation.ConversationRole;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.ListConversationParticipants;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import io.netty.channel.embedded.EmbeddedChannel;
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

class V2ConversationParticipantHandlerTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DEVICE = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID SESSION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID CONVERSATION = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID PARTICIPANT = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void bindsRequesterIdentityAndProjectsAuthorizedPage() throws Exception {
        AtomicReference<ConversationParticipantQuery> captured = new AtomicReference<>();
        EmbeddedChannel channel = channel(query -> {
            captured.set(query);
            return new ConversationParticipantResult.Found(new ConversationParticipantPage(
                    CONVERSATION,
                    List.of(new ConversationParticipant(PARTICIPANT, "李", ConversationRole.MEMBER)),
                    Optional.of(PARTICIPANT), false));
        }, Runnable::run, true);
        try {
            channel.writeInbound(command(ListConversationParticipants.newBuilder()
                    .setConversationId(CONVERSATION.toString()).setLimit(25).build()));
            channel.runPendingTasks();
            Envelope response = channel.readOutbound();
            var page = com.fallingnight.chat.protocol.v2.ConversationParticipantPage
                    .parseFrom(response.getPayload());
            assertEquals(ACCOUNT, captured.get().requesterAccountId());
            assertEquals(PARTICIPANT.toString(), page.getParticipants(0).getAccountId());
            assertEquals("李", page.getParticipants(0).getDisplayName());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsMissingCapabilityMalformedDeniedAndBusyWithoutCallingUnsafePaths() throws Exception {
        EmbeddedChannel uncapable = channel(query -> { throw new AssertionError(); }, Runnable::run, false);
        try {
            uncapable.writeInbound(command(ListConversationParticipants.newBuilder()
                    .setConversationId(CONVERSATION.toString()).setLimit(1).build()));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE,
                    error(uncapable).getCode());
        } finally { uncapable.finishAndReleaseAll(); }

        EmbeddedChannel capable = channel(query ->
                ConversationParticipantResult.Rejected.NOT_AUTHORIZED, Runnable::run, true);
        try {
            capable.writeInbound(command(ListConversationParticipants.newBuilder()
                    .setConversationId("bad").setLimit(1).build()));
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD,
                    error(capable).getCode());
            capable.writeInbound(command(ListConversationParticipants.newBuilder()
                    .setConversationId(CONVERSATION.toString()).setLimit(1).build()));
            capable.runPendingTasks();
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED,
                    error(capable).getCode());
        } finally { capable.finishAndReleaseAll(); }

        Executor rejected = task -> { throw new RejectedExecutionException(); };
        EmbeddedChannel busy = channel(query -> { throw new AssertionError(); }, rejected, true);
        try {
            busy.writeInbound(command(ListConversationParticipants.newBuilder()
                    .setConversationId(CONVERSATION.toString()).setLimit(1).build()));
            ProtocolError error = error(busy);
            assertEquals(ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED, error.getCode());
            assertTrue(error.getRetryable());
        } finally { busy.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.conversation.ConversationParticipantPort port,
            Executor executor, boolean capable) {
        EmbeddedChannel channel = new EmbeddedChannel(new V2ConversationParticipantHandler(
                port, executor, MessagingEventSink.noop(), CLOCK));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT, DEVICE, SESSION));
        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(capable
                ? Set.of(ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS) : Set.of());
        return channel;
    }

    private static Envelope command(ListConversationParticipants payload) {
        return Envelope.newBuilder().setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(MessageType.MESSAGE_TYPE_LIST_CONVERSATION_PARTICIPANTS_VALUE)
                .setRequestId("request-1").setSessionId(SESSION.toString())
                .setPayload(payload.toByteString()).build();
    }

    private static ProtocolError error(EmbeddedChannel channel) throws Exception {
        Envelope response = channel.readOutbound();
        assertFalse(response.getPayload().isEmpty());
        return ProtocolError.parseFrom(response.getPayload());
    }
}

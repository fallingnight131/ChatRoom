package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.conversation.ConversationDirectoryCursor;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPage;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPort;
import com.fallingnight.chat.application.conversation.ConversationDirectoryQuery;
import com.fallingnight.chat.application.conversation.ConversationKind;
import com.fallingnight.chat.application.conversation.ConversationRole;
import com.fallingnight.chat.application.conversation.ConversationSummary;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.ConversationHistoryEntry;
import com.fallingnight.chat.application.messaging.MessageSubmission;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.ListConversations;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageAccepted;
import com.fallingnight.chat.protocol.v2.MessageContentType;
import com.fallingnight.chat.protocol.v2.MessageHistoryPage;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageRecord;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.ReadMessageHistory;
import com.fallingnight.chat.protocol.v2.SubmitMessage;
import com.google.protobuf.ByteString;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2MessagingHandlerTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DEVICE_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID SESSION_ID = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID CONVERSATION_ID = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID MESSAGE_ID = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-12T03:04:05Z");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-12T04:05:06Z"), ZoneOffset.UTC);

    @Test
    void submitsTextWithOnlyServerBoundIdentityAndReturnsAcceptance() throws Exception {
        AtomicReference<MessageSubmission> captured = new AtomicReference<>();
        EmbeddedChannel channel = channel(
                submission -> {
                    captured.set(submission);
                    return new MessageSubmissionResult.Accepted(
                            MESSAGE_ID, 7, ACCEPTED_AT, false);
                },
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                Runnable::run);
        try {
            channel.writeInbound(submitEnvelope("client-message-1", "hello"));
            channel.runPendingTasks();

            MessageSubmission submitted = captured.get();
            assertNotNull(submitted);
            assertEquals(ACCOUNT_ID, submitted.senderAccountId());
            assertEquals(DEVICE_ID, submitted.senderDeviceId());
            assertEquals(CONVERSATION_ID, submitted.conversationId());
            Envelope response = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE, response.getMessageType());
            assertEquals("request-1", response.getRequestId());
            MessageAccepted accepted = MessageAccepted.parseFrom(response.getPayload());
            assertEquals(MESSAGE_ID.toString(), accepted.getMessageId());
            assertEquals(7, accepted.getConversationSequence());
            assertEquals(ACCEPTED_AT.toEpochMilli(), accepted.getAcceptedAtEpochMs());
            assertFalse(accepted.getDuplicate());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void publishesOnlyNewDurableAcceptanceToCaughtUpActiveConversation() throws Exception {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(CLOCK);
        AtomicReference<Boolean> duplicate = new AtomicReference<>(false);
        EmbeddedChannel channel = new EmbeddedChannel(new V2MessagingHandler(
                submission -> new MessageSubmissionResult.Accepted(
                        MESSAGE_ID, 7, ACCEPTED_AT, duplicate.get()),
                query -> new MessageHistoryResult.Page(List.of(), 0, 0, false),
                query -> new ConversationDirectoryPage(
                        List.of(), java.util.Optional.empty(), false),
                Runnable::run,
                MessagingEventSink.noop(),
                router));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT_ID, DEVICE_ID, SESSION_ID));
        try {
            channel.writeInbound(historyEnvelope(0, 100));
            channel.runPendingTasks();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    ((Envelope) channel.readOutbound()).getMessageType());

            channel.writeInbound(submitEnvelope("client-message-1", "live"));
            channel.runPendingTasks();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                    ((Envelope) channel.readOutbound()).getMessageType());
            Envelope published = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED_VALUE,
                    published.getMessageType());
            assertEquals("live", MessageRecord.parseFrom(published.getPayload())
                    .getContent().toStringUtf8());

            duplicate.set(true);
            channel.writeInbound(submitEnvelope("client-message-1", "live"));
            channel.runPendingTasks();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                    ((Envelope) channel.readOutbound()).getMessageType());
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void returnsOrderedHistoryPage() throws Exception {
        StoredMessage stored = new StoredMessage(
                MESSAGE_ID,
                CONVERSATION_ID,
                9,
                ACCOUNT_ID,
                DEVICE_ID,
                "client-message-9",
                MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE,
                "history".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                ACCEPTED_AT);
        AtomicReference<MessageHistoryQuery> captured = new AtomicReference<>();
        EmbeddedChannel channel = channel(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> {
                    captured.set(query);
                    return new MessageHistoryResult.Page(List.of(stored), 9, 12, true);
                },
                Runnable::run);
        try {
            channel.writeInbound(historyEnvelope(8, 25));
            channel.runPendingTasks();

            assertEquals(ACCOUNT_ID, captured.get().accountId());
            Envelope response = channel.readOutbound();
            MessageHistoryPage page = MessageHistoryPage.parseFrom(response.getPayload());
            assertEquals(CONVERSATION_ID.toString(), page.getConversationId());
            assertEquals(1, page.getMessagesCount());
            assertEquals(1, page.getEntriesCount());
            assertEquals(MessageRecord.getDescriptor().getFullName(),
                    page.getEntries(0).getMessage().getDescriptorForType().getFullName());
            assertEquals(9, page.getMessages(0).getConversationSequence());
            assertEquals("history", page.getMessages(0).getContent().toStringUtf8());
            assertEquals(12, page.getLatestSequence());
            assertEquals(true, page.getHasMore());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void encodesRecallAndDeletionHistoryWithoutFabricatingRecallTime() throws Exception {
        ConversationHistoryEntry.Recall recall = new ConversationHistoryEntry.Recall(
                CONVERSATION_ID, 10, MESSAGE_ID, ACCOUNT_ID, "V1_IMPORT", Optional.empty());
        ConversationHistoryEntry.Deletion deletion = new ConversationHistoryEntry.Deletion(
                CONVERSATION_ID, 11, ACCOUNT_ID, "V1_IMPORT", "selected", "delete-1",
                List.of(MESSAGE_ID), 0, 1, "Operator", ACCEPTED_AT);
        EmbeddedChannel channel = channel(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> new MessageHistoryResult.Page(
                        List.of(), List.of(recall, deletion), 11, 11, false),
                Runnable::run);
        try {
            channel.writeInbound(historyEnvelope(9, 25));
            channel.runPendingTasks();

            MessageHistoryPage page = MessageHistoryPage.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertEquals(0, page.getMessagesCount());
            assertEquals(2, page.getEntriesCount());
            assertEquals(0, page.getEntries(0).getRecall().getOccurredAtEpochMs());
            assertEquals(MESSAGE_ID.toString(),
                    page.getEntries(1).getDeletion().getMessageIds(0));
            assertEquals(11, page.getNextSequence());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void mapsExpectedDenialsWithoutClosingConnection() throws Exception {
        EmbeddedChannel channel = channel(
                submission -> MessageSubmissionResult.Rejected.IDEMPOTENCY_CONFLICT,
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                Runnable::run);
        try {
            channel.writeInbound(submitEnvelope("client-message-1", "hello"));
            channel.runPendingTasks();
            assertError(channel, ProtocolErrorCode.PROTOCOL_ERROR_CODE_IDEMPOTENCY_CONFLICT, false);

            channel.writeInbound(historyEnvelope(0, 10));
            channel.runPendingTasks();
            assertError(channel, ProtocolErrorCode.PROTOCOL_ERROR_CODE_NOT_AUTHORIZED, false);
            assertEquals(true, channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsInvalidContentAndWorkerSaturationSafely() throws Exception {
        EmbeddedChannel invalid = channel(
                submission -> { throw new AssertionError("invalid content reached application"); },
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                Runnable::run);
        try {
            invalid.writeInbound(submitEnvelope("client-message-1", ""));
            assertError(invalid, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD, false);
        } finally {
            invalid.finishAndReleaseAll();
        }

        Executor rejecting = command -> { throw new RejectedExecutionException("full"); };
        EmbeddedChannel saturated = channel(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                rejecting);
        try {
            saturated.writeInbound(submitEnvelope("client-message-1", "hello"));
            assertError(saturated, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED, true);
            assertEquals(true, saturated.isActive());
        } finally {
            saturated.finishAndReleaseAll();
        }
    }

    @Test
    void normalizesUnexpectedApplicationFailureAndContinues() throws Exception {
        AtomicReference<Boolean> fail = new AtomicReference<>(true);
        EmbeddedChannel channel = channel(
                submission -> {
                    if (fail.getAndSet(false)) {
                        throw new IllegalStateException("database detail must stay private");
                    }
                    return new MessageSubmissionResult.Accepted(
                            MESSAGE_ID, 1, ACCEPTED_AT, false);
                },
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                Runnable::run);
        try {
            channel.writeInbound(submitEnvelope("client-message-1", "first"));
            channel.runPendingTasks();
            assertError(channel, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR, true);

            channel.writeInbound(submitEnvelope("client-message-2", "second"));
            channel.runPendingTasks();
            Envelope accepted = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                    accepted.getMessageType());
            assertEquals(true, channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void recordsOnlyFixedCardinalityMessagingOutcomes() {
        MessagingTelemetry telemetry = new MessagingTelemetry();
        EmbeddedChannel channel = channel(
                submission -> new MessageSubmissionResult.Accepted(
                        MESSAGE_ID, 1, ACCEPTED_AT, true),
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                Runnable::run,
                telemetry);
        try {
            channel.writeInbound(submitEnvelope("client-message-1", "hello"));
            channel.runPendingTasks();
            channel.readOutbound();
            channel.writeInbound(historyEnvelope(0, 10));
            channel.runPendingTasks();
            channel.readOutbound();

            MessagingTelemetrySnapshot snapshot = telemetry.snapshot();
            assertEquals(0, snapshot.accepted());
            assertEquals(1, snapshot.duplicates());
            assertEquals(1, snapshot.denied());
            assertEquals(0, snapshot.failed());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void listsDirectoryWithServerBoundAccountAndCompositeCursor() throws Exception {
        AtomicReference<ConversationDirectoryQuery> captured = new AtomicReference<>();
        Instant updated = Instant.parse("2026-08-12T05:06:07Z");
        ConversationDirectoryPort directory = query -> {
            captured.set(query);
            ConversationSummary summary = new ConversationSummary(
                    CONVERSATION_ID,
                    ConversationKind.GROUP,
                    "Project Room",
                    ConversationRole.OWNER,
                    8,
                    6,
                    updated);
            return new ConversationDirectoryPage(
                    List.of(summary),
                    java.util.Optional.of(new ConversationDirectoryCursor(
                            updated, CONVERSATION_ID)),
                    false);
        };
        MessagingTelemetry telemetry = new MessagingTelemetry();
        EmbeddedChannel channel = channel(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                directory,
                Runnable::run,
                telemetry);
        try {
            ListConversations payload = ListConversations.newBuilder()
                    .setLimit(25)
                    .build();
            channel.writeInbound(commandEnvelope(
                    MessageType.MESSAGE_TYPE_LIST_CONVERSATIONS,
                    "",
                    payload.toByteString()));
            channel.runPendingTasks();

            assertEquals(ACCOUNT_ID, captured.get().accountId());
            assertEquals(25, captured.get().limit());
            Envelope response = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_CONVERSATION_DIRECTORY_PAGE_VALUE,
                    response.getMessageType());
            com.fallingnight.chat.protocol.v2.ConversationDirectoryPage page =
                    com.fallingnight.chat.protocol.v2.ConversationDirectoryPage.parseFrom(
                            response.getPayload());
            assertEquals("Project Room", page.getConversations(0).getDisplayName());
            assertEquals(8, page.getConversations(0).getLatestSequence());
            assertEquals(CONVERSATION_ID.toString(), page.getNextConversationId());
            assertEquals(1, telemetry.snapshot().directoryPages());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void preservesPerConnectionOrderAcrossOffloadedDatabaseWork() throws Exception {
        ControllableExecutor executor = new ControllableExecutor();
        ArrayDeque<String> calls = new ArrayDeque<>();
        EmbeddedChannel channel = channel(
                submission -> {
                    calls.addLast("submit");
                    return new MessageSubmissionResult.Accepted(MESSAGE_ID, 1, ACCEPTED_AT, false);
                },
                query -> {
                    calls.addLast("history");
                    return new MessageHistoryResult.Page(List.of(), 0, 1, false);
                },
                executor);
        try {
            channel.writeInbound(submitEnvelope("client-message-1", "hello"));
            channel.writeInbound(historyEnvelope(0, 10));
            assertEquals(1, executor.size());

            executor.runNext();
            channel.runPendingTasks();
            assertEquals(List.of("submit"), List.copyOf(calls));
            assertEquals(1, executor.size());

            executor.runNext();
            channel.runPendingTasks();
            assertEquals(List.of("submit", "history"), List.copyOf(calls));
            assertInstanceOf(Envelope.class, channel.readOutbound());
            assertInstanceOf(Envelope.class, channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.messaging.MessageSubmissionPort submissions,
            com.fallingnight.chat.application.messaging.MessageHistoryPort history,
            Executor executor) {
        return channel(submissions, history, executor, MessagingEventSink.noop());
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.messaging.MessageSubmissionPort submissions,
            com.fallingnight.chat.application.messaging.MessageHistoryPort history,
            Executor executor,
            MessagingEventSink events) {
        return channel(
                submissions,
                history,
                query -> new ConversationDirectoryPage(
                        List.of(), java.util.Optional.empty(), false),
                executor,
                events);
    }

    private static EmbeddedChannel channel(
            com.fallingnight.chat.application.messaging.MessageSubmissionPort submissions,
            com.fallingnight.chat.application.messaging.MessageHistoryPort history,
            ConversationDirectoryPort directory,
            Executor executor,
            MessagingEventSink events) {
        EmbeddedChannel channel = new EmbeddedChannel(
                new V2MessagingHandler(
                        submissions, history, directory, executor, events, CLOCK));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT_ID, DEVICE_ID, SESSION_ID));
        return channel;
    }

    private static Envelope submitEnvelope(String clientMessageId, String content) {
        SubmitMessage payload = SubmitMessage.newBuilder()
                .setConversationId(CONVERSATION_ID.toString())
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8(content))
                .build();
        return commandEnvelope(
                MessageType.MESSAGE_TYPE_SUBMIT_MESSAGE,
                clientMessageId,
                payload.toByteString());
    }

    private static Envelope historyEnvelope(long afterSequence, int limit) {
        ReadMessageHistory payload = ReadMessageHistory.newBuilder()
                .setConversationId(CONVERSATION_ID.toString())
                .setAfterSequence(afterSequence)
                .setLimit(limit)
                .build();
        return commandEnvelope(
                MessageType.MESSAGE_TYPE_READ_MESSAGE_HISTORY,
                "",
                payload.toByteString());
    }

    private static Envelope commandEnvelope(
            MessageType type, String clientMessageId, ByteString payload) {
        return Envelope.newBuilder()
                .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(type.getNumber())
                .setRequestId("request-1")
                .setSessionId(SESSION_ID.toString())
                .setClientMessageId(clientMessageId)
                .setSentAtEpochMs(CLOCK.millis())
                .setPayload(payload)
                .build();
    }

    private static void assertError(
            EmbeddedChannel channel,
            ProtocolErrorCode expectedCode,
            boolean retryable) throws Exception {
        Envelope response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(MessageKind.MESSAGE_KIND_ERROR, response.getKind());
        ProtocolError error = ProtocolError.parseFrom(response.getPayload());
        assertEquals(expectedCode, error.getCode());
        assertEquals(retryable, error.getRetryable());
    }

    private static final class ControllableExecutor implements Executor {
        private final ArrayDeque<Runnable> commands = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            commands.addLast(command);
        }

        int size() {
            return commands.size();
        }

        void runNext() {
            commands.removeFirst().run();
        }
    }
}

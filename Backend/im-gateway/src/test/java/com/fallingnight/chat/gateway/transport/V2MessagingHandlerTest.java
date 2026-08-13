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
import com.fallingnight.chat.application.messaging.MessageReplyReference;
import com.fallingnight.chat.application.messaging.ConversationHistoryEntry;
import com.fallingnight.chat.application.messaging.MessageSubmission;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.application.messaging.MessageReactionCommand;
import com.fallingnight.chat.application.messaging.MessageReactionKind;
import com.fallingnight.chat.application.messaging.MessageReactionResult;
import com.fallingnight.chat.application.messaging.MessagePinCommand;
import com.fallingnight.chat.application.messaging.MessagePinResult;
import com.fallingnight.chat.application.messaging.MessageEditCommand;
import com.fallingnight.chat.application.messaging.MessageEditResult;
import com.fallingnight.chat.application.messaging.MessageForwardCommand;
import com.fallingnight.chat.application.messaging.MessageForwardResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.ListConversations;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageAccepted;
import com.fallingnight.chat.protocol.v2.MessageContentType;
import com.fallingnight.chat.protocol.v2.MessageHistoryPage;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageReactionApplied;
import com.fallingnight.chat.protocol.v2.MessageReactionChangedRecord;
import com.fallingnight.chat.protocol.v2.MessageRecord;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
import com.fallingnight.chat.protocol.v2.ReadMessageHistory;
import com.fallingnight.chat.protocol.v2.SubmitMessage;
import com.fallingnight.chat.protocol.v2.SubmitReplyMessage;
import com.fallingnight.chat.protocol.v2.SetMessageReaction;
import com.fallingnight.chat.protocol.v2.SetMessagePin;
import com.fallingnight.chat.protocol.v2.MessagePinApplied;
import com.fallingnight.chat.protocol.v2.MessagePinChangedRecord;
import com.fallingnight.chat.protocol.v2.EditMessage;
import com.fallingnight.chat.protocol.v2.MessageEditApplied;
import com.fallingnight.chat.protocol.v2.ForwardMessage;
import com.google.protobuf.ByteString;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    void requiresMentionCapabilityAndMapsStableTargetsIntoSubmission() throws Exception {
        AtomicReference<MessageSubmission> captured = new AtomicReference<>();
        EmbeddedChannel channel = channel(submission -> {
            captured.set(submission);
            return new MessageSubmissionResult.Accepted(MESSAGE_ID, 7, ACCEPTED_AT, false);
        }, query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED, Runnable::run);
        UUID target = UUID.fromString("60000000-0000-4000-8000-000000000006");
        try {
            channel.writeInbound(mentionedSubmitEnvelope("mention-1", target));
            assertError(channel, ProtocolErrorCode.PROTOCOL_ERROR_CODE_INVALID_PAYLOAD, false);
            assertNull(captured.get());

            channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                    ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS));
            channel.writeInbound(mentionedSubmitEnvelope("mention-1", target));
            channel.runPendingTasks();
            assertEquals(target, captured.get().mentions().getFirst().targetAccountId());
            assertEquals(4, captured.get().mentions().getFirst().lengthUtf8Bytes());
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                    ((Envelope) channel.readOutbound()).getMessageType());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void submitsReplyWithServerBoundIdentity()
            throws Exception {
        UUID target = UUID.fromString("50000000-0000-4000-8000-000000000006");
        MessageReplyReference reference = new MessageReplyReference(target, 6, ACCOUNT_ID);
        AtomicReference<MessageSubmission> captured = new AtomicReference<>();
        EmbeddedChannel channel = channel(
                submission -> {
                    captured.set(submission);
                    return new MessageSubmissionResult.Accepted(
                            MESSAGE_ID, 7, ACCEPTED_AT, false, Optional.of(reference));
                },
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                Runnable::run);
        try {
            channel.writeInbound(replyEnvelope("client-reply-1", target, "reply"));
            channel.runPendingTasks();
            assertEquals(Optional.of(target), captured.get().replyToMessageId());
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                    ((Envelope) channel.readOutbound()).getMessageType());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void forwardsOnlyWithCapabilityAndBindsAuthenticatedActor() throws Exception {
        UUID sourceConversation = UUID.fromString("70000000-0000-4000-8000-000000000007");
        UUID targetConversation = UUID.fromString("80000000-0000-4000-8000-000000000008");
        AtomicReference<MessageForwardCommand> captured = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(new V2MessagingHandler(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                query -> new ConversationDirectoryPage(List.of(), Optional.empty(), false),
                command -> MessageReactionResult.Rejected.NOT_AUTHORIZED,
                command -> MessagePinResult.Rejected.NOT_AUTHORIZED,
                command -> MessageEditResult.Rejected.NOT_AUTHORIZED,
                command -> {
                    captured.set(command);
                    return new MessageForwardResult.Accepted(new StoredMessage(
                            MESSAGE_ID, targetConversation, 7, ACCOUNT_ID, DEVICE_ID,
                            command.clientMessageId(), 1,
                            "server truth".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            ACCEPTED_AT, Optional.empty(), 0, Optional.empty(), List.of(), true),
                            false);
                }, Runnable::run, MessagingEventSink.noop(), ConversationLiveRouter.noop()));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT_ID, DEVICE_ID, SESSION_ID));
        try {
            Envelope request = forwardEnvelope(
                    "forward-1", sourceConversation, MESSAGE_ID, 3, targetConversation);
            channel.writeInbound(request);
            assertError(channel,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE, false);
            assertNull(captured.get());

            channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                    ClientCapability.CLIENT_CAPABILITY_MESSAGE_FORWARDING));
            channel.writeInbound(request);
            channel.runPendingTasks();
            assertEquals(ACCOUNT_ID, captured.get().actorAccountId());
            assertEquals(DEVICE_ID, captured.get().actorDeviceId());
            assertEquals(sourceConversation, captured.get().sourceConversationId());
            assertEquals(targetConversation, captured.get().targetConversationId());
            assertEquals(3, captured.get().expectedSourceContentRevision());
            MessageAccepted accepted = MessageAccepted.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertEquals(targetConversation.toString(), accepted.getConversationId());
            assertEquals(7, accepted.getConversationSequence());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void returnsRetryableFixedErrorWhenForwardAdmissionIsLimited() throws Exception {
        UUID sourceConversation = UUID.fromString("70000000-0000-4000-8000-000000000007");
        UUID targetConversation = UUID.fromString("80000000-0000-4000-8000-000000000008");
        MessagingTelemetry telemetry = new MessagingTelemetry();
        EmbeddedChannel channel = new EmbeddedChannel(new V2MessagingHandler(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                query -> new ConversationDirectoryPage(List.of(), Optional.empty(), false),
                command -> MessageReactionResult.Rejected.NOT_AUTHORIZED,
                command -> MessagePinResult.Rejected.NOT_AUTHORIZED,
                command -> MessageEditResult.Rejected.NOT_AUTHORIZED,
                command -> MessageForwardResult.Rejected.RATE_LIMITED,
                Runnable::run, telemetry, ConversationLiveRouter.noop()));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT_ID, DEVICE_ID, SESSION_ID));
        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_FORWARDING));
        try {
            channel.writeInbound(forwardEnvelope(
                    "forward-limited", sourceConversation, MESSAGE_ID, 3,
                    targetConversation));
            channel.runPendingTasks();
            assertError(channel, ProtocolErrorCode.PROTOCOL_ERROR_CODE_RATE_LIMITED, true);
            assertEquals(1, telemetry.snapshot().forwardRateLimited());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void filtersForwardMarkerFromHistoryWithoutCapability() throws Exception {
        StoredMessage forwarded = new StoredMessage(
                MESSAGE_ID, CONVERSATION_ID, 1, ACCOUNT_ID, DEVICE_ID, "forwarded-1", 1,
                "copied".getBytes(java.nio.charset.StandardCharsets.UTF_8), ACCEPTED_AT,
                Optional.empty(), 0, Optional.empty(), List.of(), true);
        EmbeddedChannel channel = channel(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> new MessageHistoryResult.Page(List.of(forwarded), 1, 1, false),
                Runnable::run);
        try {
            channel.writeInbound(historyEnvelope(0, 10));
            channel.runPendingTasks();
            MessageHistoryPage legacy = MessageHistoryPage.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertFalse(legacy.getMessages(0).getForwarded());

            channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                    ClientCapability.CLIENT_CAPABILITY_MESSAGE_FORWARDING));
            channel.writeInbound(historyEnvelope(0, 10));
            channel.runPendingTasks();
            MessageHistoryPage capable = MessageHistoryPage.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertEquals(true, capable.getMessages(0).getForwarded());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void publishesOnlyNewDurableAcceptanceToCaughtUpActiveConversation() throws Exception {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(CLOCK);
        AtomicReference<Boolean> duplicate = new AtomicReference<>(false);
        UUID replyTarget = UUID.fromString("50000000-0000-4000-8000-000000000006");
        EmbeddedChannel channel = new EmbeddedChannel(new V2MessagingHandler(
                submission -> new MessageSubmissionResult.Accepted(
                        MESSAGE_ID, 7, ACCEPTED_AT, duplicate.get(),
                        submission.replyToMessageId().map(target ->
                                new MessageReplyReference(target, 6, ACCOUNT_ID))),
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

            duplicate.set(false);
            channel.writeInbound(replyEnvelope("client-reply-1", replyTarget, "reply-live"));
            channel.runPendingTasks();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED_VALUE,
                    ((Envelope) channel.readOutbound()).getMessageType());
            MessageRecord replyPublished = MessageRecord.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertEquals(replyTarget.toString(),
                    replyPublished.getReply().getTargetMessageId());
            assertEquals(6, replyPublished.getReply().getTargetConversationSequence());
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
                ACCEPTED_AT,
                Optional.of(new MessageReplyReference(
                        UUID.fromString("50000000-0000-4000-8000-000000000006"),
                        8,
                        ACCOUNT_ID)));
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
            assertEquals(8, page.getMessages(0).getReply().getTargetConversationSequence());
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

            Envelope historyResponse = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    historyResponse.getMessageType());
            MessageHistoryPage page = MessageHistoryPage.parseFrom(historyResponse.getPayload());
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
    void rejectsReactionCommandWithoutNegotiatedCapability() throws Exception {
        AtomicReference<MessageReactionCommand> captured = new AtomicReference<>();
        EmbeddedChannel channel = reactionChannel(
                command -> {
                    captured.set(command);
                    return MessageReactionResult.Rejected.NOT_AUTHORIZED;
                },
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                ConversationLiveRouter.noop(),
                false);
        try {
            channel.writeInbound(reactionEnvelope("reaction-1", true));
            assertError(channel,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE, false);
            assertNull(captured.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void appliesAndPublishesNegotiatedReactionWithServerBoundIdentity() throws Exception {
        AtomicReference<MessageReactionCommand> captured = new AtomicReference<>();
        SingleGatewayConversationLiveRouter router =
                new SingleGatewayConversationLiveRouter(CLOCK);
        MessagingTelemetry telemetry = new MessagingTelemetry();
        EmbeddedChannel channel = new EmbeddedChannel(new V2MessagingHandler(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> new MessageHistoryResult.Page(List.of(), 0, 0, false),
                query -> new ConversationDirectoryPage(
                        List.of(), Optional.empty(), false),
                command -> {
                    captured.set(command);
                    return new MessageReactionResult.Applied(
                            command.conversationId(), command.messageId(),
                            command.actorAccountId(), command.reaction(), command.active(),
                            command.clientOperationId(), true, 7, ACCEPTED_AT, false);
                },
                Runnable::run,
                telemetry,
                router));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT_ID, DEVICE_ID, SESSION_ID));
        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS));
        try {
            channel.writeInbound(historyEnvelope(0, 100));
            channel.runPendingTasks();
            channel.readOutbound();

            channel.writeInbound(reactionEnvelope("reaction-1", true));
            channel.runPendingTasks();

            assertEquals(ACCOUNT_ID, captured.get().actorAccountId());
            assertEquals(DEVICE_ID, captured.get().actorDeviceId());
            assertEquals(MessageReactionKind.LOVE, captured.get().reaction());
            Envelope response = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_REACTION_APPLIED_VALUE,
                    response.getMessageType());
            MessageReactionApplied applied =
                    MessageReactionApplied.parseFrom(response.getPayload());
            assertEquals(7, applied.getConversationSequence());
            assertEquals(ACCOUNT_ID.toString(), applied.getActorAccountId());
            Envelope event = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_REACTION_CHANGED_VALUE,
                    event.getMessageType());
            MessageReactionChangedRecord changed =
                    MessageReactionChangedRecord.parseFrom(event.getPayload());
            assertEquals("reaction-1", changed.getClientOperationId());
            assertEquals(1, telemetry.snapshot().reactionChanged());
            assertEquals(1, telemetry.snapshot().livePublished());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void filtersReactionHistoryForLegacyV2ButKeepsAuthoritativeCursor() throws Exception {
        ConversationHistoryEntry.Reaction reaction = new ConversationHistoryEntry.Reaction(
                CONVERSATION_ID, 9, MESSAGE_ID, ACCOUNT_ID, MessageReactionKind.LIKE,
                true, "reaction-history", ACCEPTED_AT);
        MessageHistoryResult.Page stored = new MessageHistoryResult.Page(
                List.of(), List.of(reaction), 9, 9, false);
        EmbeddedChannel legacy = reactionChannel(
                command -> MessageReactionResult.Rejected.NOT_AUTHORIZED,
                query -> stored,
                ConversationLiveRouter.noop(),
                false);
        EmbeddedChannel capable = reactionChannel(
                command -> MessageReactionResult.Rejected.NOT_AUTHORIZED,
                query -> stored,
                ConversationLiveRouter.noop(),
                true);
        try {
            legacy.writeInbound(historyEnvelope(0, 10));
            legacy.runPendingTasks();
            MessageHistoryPage legacyPage = MessageHistoryPage.parseFrom(
                    ((Envelope) legacy.readOutbound()).getPayload());
            assertEquals(0, legacyPage.getEntriesCount());
            assertEquals(9, legacyPage.getNextSequence());

            capable.writeInbound(historyEnvelope(0, 10));
            capable.runPendingTasks();
            MessageHistoryPage capablePage = MessageHistoryPage.parseFrom(
                    ((Envelope) capable.readOutbound()).getPayload());
            assertEquals(1, capablePage.getEntriesCount());
            assertEquals("reaction-history",
                    capablePage.getEntries(0).getReaction().getClientOperationId());
        } finally {
            legacy.finishAndReleaseAll();
            capable.finishAndReleaseAll();
        }
    }

    @Test
    void gatesAppliesPublishesAndHistoryFiltersMessagePins() throws Exception {
        AtomicReference<MessagePinCommand> captured = new AtomicReference<>();
        ConversationHistoryEntry.Pin pinHistory = new ConversationHistoryEntry.Pin(
                CONVERSATION_ID, 6, MESSAGE_ID, ACCOUNT_ID, true, "pin-history", ACCEPTED_AT);
        MessageHistoryResult.Page stored = new MessageHistoryResult.Page(
                List.of(), List.of(pinHistory), 6, 6, false);
        SingleGatewayConversationLiveRouter router =
                new SingleGatewayConversationLiveRouter(CLOCK);
        EmbeddedChannel channel = new EmbeddedChannel(new V2MessagingHandler(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> stored,
                query -> new ConversationDirectoryPage(List.of(), Optional.empty(), false),
                command -> MessageReactionResult.Rejected.NOT_AUTHORIZED,
                command -> {
                    captured.set(command);
                    return new MessagePinResult.Applied(
                            command.conversationId(), command.messageId(),
                            command.actorAccountId(), command.pinned(),
                            command.clientOperationId(), true, 7, ACCEPTED_AT, false);
                }, Runnable::run, MessagingEventSink.noop(), router));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT_ID, DEVICE_ID, SESSION_ID));
        try {
            channel.writeInbound(pinEnvelope("pin-denied", true));
            assertError(channel,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE, false);
            assertNull(captured.get());

            channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                    ClientCapability.CLIENT_CAPABILITY_MESSAGE_PINS));
            channel.writeInbound(historyEnvelope(0, 10));
            channel.runPendingTasks();
            MessageHistoryPage page = MessageHistoryPage.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertEquals("pin-history", page.getEntries(0).getPin().getClientOperationId());

            channel.writeInbound(pinEnvelope("pin-add", true));
            channel.runPendingTasks();
            assertEquals(ACCOUNT_ID, captured.get().actorAccountId());
            assertEquals(DEVICE_ID, captured.get().actorDeviceId());
            Envelope response = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_PIN_APPLIED_VALUE,
                    response.getMessageType());
            MessagePinApplied applied = MessagePinApplied.parseFrom(response.getPayload());
            assertEquals(7, applied.getConversationSequence());
            Envelope event = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_PIN_CHANGED_VALUE,
                    event.getMessageType());
            MessagePinChangedRecord changed =
                    MessagePinChangedRecord.parseFrom(event.getPayload());
            assertEquals("pin-add", changed.getClientOperationId());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void gatesAppliesAndFiltersMessageEditsWithStableConflictCodes() throws Exception {
        AtomicReference<MessageEditCommand> captured = new AtomicReference<>();
        com.fallingnight.chat.application.messaging.MessageMention mention =
                new com.fallingnight.chat.application.messaging.MessageMention(
                        UUID.fromString("60000000-0000-4000-8000-000000000006"), 0, 4);
        ConversationHistoryEntry.Edit visible = new ConversationHistoryEntry.Edit(
                CONVERSATION_ID, 8, MESSAGE_ID, 1, 1, ByteString.copyFromUtf8("@李 hi")
                        .toByteArray(), false, ACCOUNT_ID, "edit-history", ACCEPTED_AT,
                List.of(mention));
        ConversationHistoryEntry.Edit erased = new ConversationHistoryEntry.Edit(
                CONVERSATION_ID, 9, MESSAGE_ID, 2, 1, new byte[0], true,
                ACCOUNT_ID, "edit-erased", ACCEPTED_AT);
        MessageHistoryResult.Page stored = new MessageHistoryResult.Page(
                List.of(), List.of(visible, erased), 9, 9, false);
        EmbeddedChannel channel = new EmbeddedChannel(new V2MessagingHandler(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> stored,
                query -> new ConversationDirectoryPage(List.of(), Optional.empty(), false),
                command -> MessageReactionResult.Rejected.NOT_AUTHORIZED,
                command -> MessagePinResult.Rejected.NOT_AUTHORIZED,
                command -> {
                    captured.set(command);
                    if (command.clientOperationId().equals("edit-stale")) {
                        return MessageEditResult.Rejected.STALE_REVISION;
                    }
                    return new MessageEditResult.Applied(
                            command.conversationId(), command.messageId(),
                            command.actorAccountId(), 1, command.contentType(), command.content(),
                            command.clientOperationId(), true, 8, ACCEPTED_AT, false);
                }, Runnable::run, MessagingEventSink.noop(), ConversationLiveRouter.noop()));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT_ID, DEVICE_ID, SESSION_ID));
        try {
            channel.writeInbound(editEnvelope("edit-denied", 0, "updated"));
            assertError(channel,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_UNSUPPORTED_MESSAGE_TYPE, false);
            assertNull(captured.get());

            channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                    ClientCapability.CLIENT_CAPABILITY_MESSAGE_EDITS));
            channel.writeInbound(historyEnvelope(0, 10));
            channel.runPendingTasks();
            Envelope editHistoryResponse = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE_VALUE,
                    editHistoryResponse.getMessageType());
            MessageHistoryPage page = MessageHistoryPage.parseFrom(
                    editHistoryResponse.getPayload());
            assertEquals(1, page.getEntriesCount());
            assertEquals(9, page.getNextSequence());
            assertEquals("edit-history", page.getEntries(0).getEdit().getClientOperationId());
            assertEquals(0, page.getEntries(0).getEdit().getMentionsCount());

            channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                    ClientCapability.CLIENT_CAPABILITY_MESSAGE_EDITS,
                    ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS));
            channel.writeInbound(historyEnvelope(0, 10));
            channel.runPendingTasks();
            MessageHistoryPage capablePage = MessageHistoryPage.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload());
            assertEquals(1, capablePage.getEntries(0).getEdit().getMentionsCount());

            channel.writeInbound(editEnvelope("edit-first", 0, "updated"));
            channel.runPendingTasks();
            assertEquals(ACCOUNT_ID, captured.get().actorAccountId());
            assertEquals(DEVICE_ID, captured.get().actorDeviceId());
            Envelope response = channel.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_EDIT_APPLIED_VALUE,
                    response.getMessageType());
            MessageEditApplied applied = MessageEditApplied.parseFrom(response.getPayload());
            assertEquals(1, applied.getContentRevision());
            assertEquals("updated", applied.getContent().toStringUtf8());

            channel.writeInbound(editEnvelope("edit-stale", 0, "stale"));
            channel.runPendingTasks();
            assertError(channel,
                    ProtocolErrorCode.PROTOCOL_ERROR_CODE_MESSAGE_REVISION_CONFLICT, false);
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

    private static Envelope mentionedSubmitEnvelope(String clientMessageId, UUID target) {
        SubmitMessage payload = SubmitMessage.newBuilder()
                .setConversationId(CONVERSATION_ID.toString())
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8("@李 hi"))
                .addMentions(com.fallingnight.chat.protocol.v2.MessageMention.newBuilder()
                        .setTargetAccountId(target.toString())
                        .setStartUtf8Byte(0).setLengthUtf8Bytes(4))
                .build();
        return commandEnvelope(MessageType.MESSAGE_TYPE_SUBMIT_MESSAGE,
                clientMessageId, payload.toByteString());
    }

    private static Envelope replyEnvelope(
            String clientMessageId, UUID targetMessageId, String content) {
        SubmitReplyMessage payload = SubmitReplyMessage.newBuilder()
                .setConversationId(CONVERSATION_ID.toString())
                .setTargetMessageId(targetMessageId.toString())
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8(content))
                .build();
        return commandEnvelope(
                MessageType.MESSAGE_TYPE_SUBMIT_REPLY_MESSAGE,
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

    private static EmbeddedChannel reactionChannel(
            com.fallingnight.chat.application.messaging.MessageReactionPort reactions,
            com.fallingnight.chat.application.messaging.MessageHistoryPort history,
            ConversationLiveRouter router,
            boolean capable) {
        EmbeddedChannel channel = new EmbeddedChannel(new V2MessagingHandler(
                submission -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                history,
                query -> new ConversationDirectoryPage(List.of(), Optional.empty(), false),
                reactions,
                Runnable::run,
                MessagingEventSink.noop(),
                router));
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT_ID, DEVICE_ID, SESSION_ID));
        if (capable) {
            channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                    ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS));
        }
        return channel;
    }

    private static Envelope reactionEnvelope(String operationId, boolean active) {
        SetMessageReaction payload = SetMessageReaction.newBuilder()
                .setConversationId(CONVERSATION_ID.toString())
                .setMessageId(MESSAGE_ID.toString())
                .setReaction(com.fallingnight.chat.protocol.v2.MessageReactionKind
                        .MESSAGE_REACTION_KIND_LOVE)
                .setActive(active)
                .setClientOperationId(operationId)
                .build();
        return commandEnvelope(
                MessageType.MESSAGE_TYPE_SET_MESSAGE_REACTION,
                "",
                payload.toByteString());
    }

    private static Envelope pinEnvelope(String operationId, boolean pinned) {
        SetMessagePin payload = SetMessagePin.newBuilder()
                .setConversationId(CONVERSATION_ID.toString())
                .setMessageId(MESSAGE_ID.toString()).setPinned(pinned)
                .setClientOperationId(operationId).build();
        return commandEnvelope(MessageType.MESSAGE_TYPE_SET_MESSAGE_PIN, "",
                payload.toByteString());
    }

    private static Envelope editEnvelope(
            String operationId, int expectedRevision, String content) {
        EditMessage payload = EditMessage.newBuilder()
                .setConversationId(CONVERSATION_ID.toString())
                .setMessageId(MESSAGE_ID.toString())
                .setExpectedRevision(expectedRevision)
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8(content))
                .setClientOperationId(operationId)
                .build();
        return commandEnvelope(MessageType.MESSAGE_TYPE_EDIT_MESSAGE, "",
                payload.toByteString());
    }

    private static Envelope forwardEnvelope(
            String clientMessageId, UUID sourceConversation, UUID sourceMessage,
            int expectedRevision, UUID targetConversation) {
        ForwardMessage payload = ForwardMessage.newBuilder()
                .setSourceConversationId(sourceConversation.toString())
                .setSourceMessageId(sourceMessage.toString())
                .setExpectedSourceContentRevision(expectedRevision)
                .setTargetConversationId(targetConversation.toString())
                .build();
        return commandEnvelope(MessageType.MESSAGE_TYPE_FORWARD_MESSAGE,
                clientMessageId, payload.toByteString());
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

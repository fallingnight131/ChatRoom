package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fallingnight.chat.application.conversation.ConversationParticipant;
import com.fallingnight.chat.application.conversation.ConversationParticipantPage;
import com.fallingnight.chat.application.conversation.ConversationParticipantResult;
import com.fallingnight.chat.application.conversation.ConversationRole;
import com.fallingnight.chat.application.identity.AuthenticationResult;
import com.fallingnight.chat.application.identity.DeviceDirectoryResult;
import com.fallingnight.chat.application.identity.DeviceManagementPort;
import com.fallingnight.chat.application.identity.DeviceManagementService;
import com.fallingnight.chat.application.identity.DeviceRevocationResult;
import com.fallingnight.chat.application.identity.IssuedSession;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessageEditResult;
import com.fallingnight.chat.application.messaging.MessagePinResult;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.application.messaging.MessageForwardCommand;
import com.fallingnight.chat.application.messaging.MessageForwardResult;
import com.fallingnight.chat.application.messaging.MessageSearchResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.application.security.SecretBytes;
import com.fallingnight.chat.protocol.v2.Authenticate;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.ClientHello;
import com.fallingnight.chat.protocol.v2.ClientPlatform;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.ListConversationParticipants;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ForwardMessage;
import com.fallingnight.chat.protocol.v2.MessageAccepted;
import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2ApplicationPipelineTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DEVICE = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID SESSION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID CONVERSATION = UUID.fromString("40000000-0000-4000-8000-000000000004");

    @Test
    void installsDeterministicPostUpgradeOrder() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            V2ApplicationPipeline.install(
                    channel.pipeline(),
                    command -> AuthenticationResult.Rejected.INSTANCE,
                    command -> AuthenticationResult.Rejected.INSTANCE,
                    command -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                    query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                    query -> new com.fallingnight.chat.application.conversation
                            .ConversationDirectoryPage(
                                    java.util.List.of(), java.util.Optional.empty(), false),
                    command -> com.fallingnight.chat.application.messaging
                            .MessageReactionResult.Rejected.NOT_AUTHORIZED,
                    new DeviceManagementService(new DeviceManagementPort() {
                        @Override public DeviceDirectoryResult listActive(
                                com.fallingnight.chat.application.identity
                                        .AuthenticatedDeviceActor actor) {
                            return DeviceDirectoryResult.Rejected.INSTANCE;
                        }
                        @Override public DeviceRevocationResult revokeOther(
                                com.fallingnight.chat.application.identity
                                        .AuthenticatedDeviceActor actor, UUID target) {
                            return DeviceRevocationResult.Rejected.INSTANCE;
                        }
                    }),
                    Runnable::run,
                    Runnable::run,
                    AuthenticationAdmissionControl.allowAll(),
                    AuthenticationEventSink.noop(),
                    MessagingEventSink.noop(),
                    DeviceManagementEventSink.noop(),
                    new DeviceConnectionRegistry(),
                    ConversationLiveRouter.noop(),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30));

            List<String> names = channel.pipeline().names();
            assertEquals(List.of(
                    "v2-frame-aggregator",
                    "v2-envelope-decoder",
                    "v2-frame-error-normalizer",
                    "v2-envelope-encoder",
                    "v2-frame-close",
                    "v2-phase-timeouts",
                    "v2-handshake",
                    "v2-authentication",
                    "v2-device-connections",
                    "v2-device-management",
                    "v2-conversation-participants",
                    "v2-messaging",
                    "v2-authenticated-heartbeat",
                    "v2-authenticated-idle-close"),
                    names.subList(0, 14));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void installsSearchOnlyForExplicitCandidatePolicyAndNegotiatesIt() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            V2ApplicationPipeline.install(
                    channel.pipeline(),
                    command -> AuthenticationResult.Rejected.INSTANCE,
                    command -> AuthenticationResult.Rejected.INSTANCE,
                    command -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                    query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                    query -> new com.fallingnight.chat.application.conversation
                            .ConversationDirectoryPage(List.of(), Optional.empty(), false),
                    query -> ConversationParticipantResult.Rejected.NOT_AUTHORIZED,
                    command -> com.fallingnight.chat.application.messaging
                            .MessageReactionResult.Rejected.NOT_AUTHORIZED,
                    command -> MessagePinResult.Rejected.NOT_AUTHORIZED,
                    command -> MessageEditResult.Rejected.NOT_AUTHORIZED,
                    command -> MessageForwardResult.Rejected.NOT_AUTHORIZED,
                    query -> MessageSearchResult.Rejected.NOT_AUTHORIZED,
                    rejectingDevices(),
                    Runnable::run,
                    Runnable::run,
                    AuthenticationAdmissionControl.allowAll(),
                    AuthenticationEventSink.noop(),
                    MessagingEventSink.noop(),
                    DeviceManagementEventSink.noop(),
                    new DeviceConnectionRegistry(),
                    ConversationLiveRouter.noop(),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30),
                    false,
                    true);

            List<String> names = channel.pipeline().names();
            assertEquals("v2-message-search", names.get(names.indexOf("v2-messaging") - 1));

            write(channel, envelope("hello-search", MessageType.MESSAGE_TYPE_CLIENT_HELLO,
                    ClientHello.newBuilder()
                            .setMinimumProtocolVersion(2)
                            .setMaximumProtocolVersion(2)
                            .setPlatform(ClientPlatform.CLIENT_PLATFORM_WEB)
                            .setAppVersion("0.1.0")
                            .setClientDeviceId("pipeline-search-test")
                            .addCapabilities(ClientCapability.CLIENT_CAPABILITY_MESSAGE_SEARCH)
                            .build().toByteString(), ""));
            com.fallingnight.chat.protocol.v2.ServerHello hello =
                    com.fallingnight.chat.protocol.v2.ServerHello.parseFrom(
                            read(channel).getPayload());
            assertEquals(List.of(ClientCapability.CLIENT_CAPABILITY_MESSAGE_SEARCH),
                    hello.getEnabledCapabilitiesList());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void routesNegotiatedAuthenticatedParticipantQueryThroughProductPipeline() throws Exception {
        SecretBytes resumeToken = SecretBytes.copyOf(new byte[32]);
        EmbeddedChannel channel = new EmbeddedChannel();
        AtomicReference<MessageForwardCommand> forwarded = new AtomicReference<>();
        UUID sourceMessage = UUID.fromString("50000000-0000-4000-8000-000000000005");
        UUID targetConversation = UUID.fromString("60000000-0000-4000-8000-000000000006");
        try {
            V2ApplicationPipeline.install(
                    channel.pipeline(),
                    command -> new AuthenticationResult.Established(new IssuedSession(
                            ACCOUNT, DEVICE, SESSION, resumeToken,
                            Instant.parse("2026-08-13T13:00:00Z"), "Alice"), false),
                    command -> AuthenticationResult.Rejected.INSTANCE,
                    command -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                    query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                    query -> new com.fallingnight.chat.application.conversation
                            .ConversationDirectoryPage(List.of(), Optional.empty(), false),
                    query -> new ConversationParticipantResult.Found(
                            new ConversationParticipantPage(
                                    query.conversationId(),
                                    List.of(new ConversationParticipant(
                                            ACCOUNT, "Alice", ConversationRole.OWNER)),
                                    Optional.of(ACCOUNT), false)),
                    command -> com.fallingnight.chat.application.messaging
                            .MessageReactionResult.Rejected.NOT_AUTHORIZED,
                    command -> MessagePinResult.Rejected.NOT_AUTHORIZED,
                    command -> MessageEditResult.Rejected.NOT_AUTHORIZED,
                    command -> {
                        forwarded.set(command);
                        return new MessageForwardResult.Accepted(new StoredMessage(
                                UUID.fromString("70000000-0000-4000-8000-000000000007"),
                                targetConversation, 1, ACCOUNT, DEVICE,
                                command.clientMessageId(), 1, ByteString.copyFromUtf8("copied")
                                        .toByteArray(), Instant.parse("2026-08-13T13:01:00Z"),
                                Optional.empty(), 0, Optional.empty(), List.of(), true), false);
                    },
                    rejectingDevices(),
                    Runnable::run,
                    Runnable::run,
                    AuthenticationAdmissionControl.allowAll(),
                    AuthenticationEventSink.noop(),
                    MessagingEventSink.noop(),
                    DeviceManagementEventSink.noop(),
                    new DeviceConnectionRegistry(),
                    ConversationLiveRouter.noop(),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30));

            write(channel, envelope("hello", MessageType.MESSAGE_TYPE_CLIENT_HELLO,
                    ClientHello.newBuilder()
                            .setMinimumProtocolVersion(2)
                            .setMaximumProtocolVersion(2)
                            .setPlatform(ClientPlatform.CLIENT_PLATFORM_WEB)
                            .setAppVersion("0.1.0")
                            .setClientDeviceId("pipeline-test")
                            .addCapabilities(ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS)
                            .build().toByteString(), ""));
            assertEquals(MessageType.MESSAGE_TYPE_SERVER_HELLO_VALUE,
                    read(channel).getMessageType());

            write(channel, envelope("auth", MessageType.MESSAGE_TYPE_AUTHENTICATE,
                    Authenticate.newBuilder().setUsername("alice")
                            .setPasswordUtf8(ByteString.copyFromUtf8("test-password"))
                            .build().toByteString(), ""));
            assertEquals(MessageType.MESSAGE_TYPE_SESSION_ESTABLISHED_VALUE,
                    read(channel).getMessageType());

            write(channel, envelope("participants",
                    MessageType.MESSAGE_TYPE_LIST_CONVERSATION_PARTICIPANTS,
                    ListConversationParticipants.newBuilder()
                            .setConversationId(CONVERSATION.toString()).setLimit(25)
                            .build().toByteString(), SESSION.toString()));
            Envelope response = read(channel);
            assertEquals(MessageType.MESSAGE_TYPE_CONVERSATION_PARTICIPANT_PAGE_VALUE,
                    response.getMessageType());
            com.fallingnight.chat.protocol.v2.ConversationParticipantPage page =
                    com.fallingnight.chat.protocol.v2.ConversationParticipantPage
                            .parseFrom(response.getPayload());
            assertEquals(CONVERSATION.toString(), page.getConversationId());
            assertEquals(ACCOUNT.toString(), page.getParticipants(0).getAccountId());
            assertEquals("Alice", page.getParticipants(0).getDisplayName());

            channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(java.util.Set.of(
                    ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS,
                    ClientCapability.CLIENT_CAPABILITY_MESSAGE_FORWARDING));
            write(channel, envelope("forward", MessageType.MESSAGE_TYPE_FORWARD_MESSAGE,
                    ForwardMessage.newBuilder()
                            .setSourceConversationId(CONVERSATION.toString())
                            .setSourceMessageId(sourceMessage.toString())
                            .setTargetConversationId(targetConversation.toString())
                            .build().toByteString(), SESSION.toString()).toBuilder()
                    .setClientMessageId("forward-pipeline-1").build());
            MessageAccepted accepted = MessageAccepted.parseFrom(read(channel).getPayload());
            assertEquals(targetConversation.toString(), accepted.getConversationId());
            assertEquals(ACCOUNT, forwarded.get().actorAccountId());
            assertEquals(DEVICE, forwarded.get().actorDeviceId());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static DeviceManagementService rejectingDevices() {
        return new DeviceManagementService(new DeviceManagementPort() {
            @Override public DeviceDirectoryResult listActive(
                    com.fallingnight.chat.application.identity.AuthenticatedDeviceActor actor) {
                return DeviceDirectoryResult.Rejected.INSTANCE;
            }

            @Override public DeviceRevocationResult revokeOther(
                    com.fallingnight.chat.application.identity.AuthenticatedDeviceActor actor,
                    UUID target) {
                return DeviceRevocationResult.Rejected.INSTANCE;
            }
        });
    }

    private static Envelope envelope(
            String requestId, MessageType type, ByteString payload, String sessionId) {
        return Envelope.newBuilder().setProtocolVersion(2)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND).setMessageType(type.getNumber())
                .setRequestId(requestId).setSessionId(sessionId)
                .setSentAtEpochMs(1_786_627_200_000L).setPayload(payload).build();
    }

    private static void write(EmbeddedChannel channel, Envelope envelope) {
        channel.writeInbound(new BinaryWebSocketFrame(
                Unpooled.wrappedBuffer(envelope.toByteArray())));
        channel.runPendingTasks();
    }

    private static Envelope read(EmbeddedChannel channel) throws Exception {
        BinaryWebSocketFrame frame = channel.readOutbound();
        try {
            return Envelope.parseFrom(frame.content().nioBuffer());
        } finally {
            frame.release();
        }
    }
}

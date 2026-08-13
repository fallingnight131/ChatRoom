package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.AuthenticationUseCase;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPort;
import com.fallingnight.chat.application.conversation.ConversationParticipantPort;
import com.fallingnight.chat.application.conversation.ConversationParticipantResult;
import com.fallingnight.chat.application.identity.SessionResumeUseCase;
import com.fallingnight.chat.application.identity.DeviceManagementService;
import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.MessageSubmissionPort;
import com.fallingnight.chat.application.messaging.MessageReactionPort;
import com.fallingnight.chat.application.messaging.MessagePinPort;
import com.fallingnight.chat.application.messaging.MessageEditPort;
import com.fallingnight.chat.application.messaging.MessageEditResult;
import io.netty.channel.ChannelPipeline;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Installs the ordered post-upgrade V2 frame, phase, authentication, and idle pipeline. */
public final class V2ApplicationPipeline {
    private V2ApplicationPipeline() {}

    public static void install(
            ChannelPipeline pipeline, AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume, MessageSubmissionPort submissions,
            MessageHistoryPort history, ConversationDirectoryPort directory,
            MessageReactionPort reactions, DeviceManagementService deviceManagement,
            Executor authenticationExecutor, Executor messagingExecutor,
            AuthenticationAdmissionControl admission, AuthenticationEventSink events,
            MessagingEventSink messagingEvents, DeviceManagementEventSink deviceEvents,
            DeviceConnectionRegistry deviceConnections, ConversationLiveRouter liveRouter,
            Duration handshakeTimeout, Duration authenticationTimeout) {
        install(pipeline, authentication, sessionResume, submissions, history, directory,
                reactions, command -> com.fallingnight.chat.application.messaging
                        .MessagePinResult.Rejected.NOT_AUTHORIZED,
                deviceManagement, authenticationExecutor, messagingExecutor, admission, events,
                messagingEvents, deviceEvents, deviceConnections, liveRouter, handshakeTimeout,
                authenticationTimeout);
    }

    public static void install(
            ChannelPipeline pipeline,
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            MessageReactionPort reactions,
            MessagePinPort pins,
            DeviceManagementService deviceManagement,
            Executor authenticationExecutor,
            Executor messagingExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            MessagingEventSink messagingEvents,
            DeviceManagementEventSink deviceEvents,
            DeviceConnectionRegistry deviceConnections,
            ConversationLiveRouter liveRouter,
            Duration handshakeTimeout,
            Duration authenticationTimeout) {
        install(pipeline, authentication, sessionResume, submissions, history, directory,
                reactions, pins, command -> MessageEditResult.Rejected.NOT_AUTHORIZED,
                deviceManagement, authenticationExecutor, messagingExecutor, admission, events,
                messagingEvents, deviceEvents, deviceConnections, liveRouter, handshakeTimeout,
                authenticationTimeout);
    }

    public static void install(
            ChannelPipeline pipeline,
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            MessageReactionPort reactions,
            MessagePinPort pins,
            MessageEditPort edits,
            DeviceManagementService deviceManagement,
            Executor authenticationExecutor,
            Executor messagingExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            MessagingEventSink messagingEvents,
            DeviceManagementEventSink deviceEvents,
            DeviceConnectionRegistry deviceConnections,
            ConversationLiveRouter liveRouter,
            Duration handshakeTimeout,
            Duration authenticationTimeout) {
        install(pipeline, authentication, sessionResume, submissions, history, directory,
                query -> ConversationParticipantResult.Rejected.NOT_AUTHORIZED,
                reactions, pins, edits, deviceManagement, authenticationExecutor,
                messagingExecutor, admission, events, messagingEvents, deviceEvents,
                deviceConnections, liveRouter, handshakeTimeout, authenticationTimeout);
    }

    public static void install(
            ChannelPipeline pipeline,
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            ConversationParticipantPort participants,
            MessageReactionPort reactions,
            MessagePinPort pins,
            MessageEditPort edits,
            DeviceManagementService deviceManagement,
            Executor authenticationExecutor,
            Executor messagingExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            MessagingEventSink messagingEvents,
            DeviceManagementEventSink deviceEvents,
            DeviceConnectionRegistry deviceConnections,
            ConversationLiveRouter liveRouter,
            Duration handshakeTimeout,
            Duration authenticationTimeout) {
        Objects.requireNonNull(pipeline, "pipeline");
        V2FramePipeline.install(pipeline);
        pipeline.addLast("v2-phase-timeouts", new V2ConnectionTimeoutHandler(
                handshakeTimeout, authenticationTimeout));
        pipeline.addLast("v2-handshake", new V2HandshakeHandler());
        pipeline.addLast("v2-authentication", new V2AuthenticationHandler(
                authentication,
                sessionResume,
                authenticationExecutor,
                admission,
                events,
                java.time.Clock.systemUTC()));
        pipeline.addLast("v2-device-connections",
                new V2DeviceConnectionTracker(deviceConnections));
        pipeline.addLast("v2-device-management", new V2DeviceManagementHandler(
                deviceManagement, messagingExecutor, deviceConnections, deviceEvents));
        pipeline.addLast("v2-conversation-participants", new V2ConversationParticipantHandler(
                participants, messagingExecutor, messagingEvents));
        pipeline.addLast("v2-messaging", new V2MessagingHandler(
                submissions, history, directory, reactions, pins, edits, messagingExecutor,
                messagingEvents, liveRouter));
        pipeline.addLast("v2-authenticated-heartbeat", new V2AuthenticatedHeartbeatHandler());
        pipeline.addLast("v2-authenticated-idle-close", new V2AuthenticatedIdleCloseHandler());
    }
}

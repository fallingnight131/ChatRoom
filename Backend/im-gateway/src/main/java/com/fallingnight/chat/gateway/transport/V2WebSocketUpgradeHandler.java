package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.contact.AccountBlockResult;
import com.fallingnight.chat.application.contact.AccountBlockUseCase;
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
import com.fallingnight.chat.application.messaging.MessagePinResult;
import com.fallingnight.chat.application.messaging.MessageEditPort;
import com.fallingnight.chat.application.messaging.MessageEditResult;
import com.fallingnight.chat.application.messaging.MessageForwardPort;
import com.fallingnight.chat.application.messaging.MessageForwardResult;
import com.fallingnight.chat.application.messaging.MessageSearchPort;
import com.fallingnight.chat.application.messaging.MessageSearchResult;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Installs the application protocol only after Netty confirms WebSocket upgrade. */
public final class V2WebSocketUpgradeHandler extends ChannelInboundHandlerAdapter {
    private final AuthenticationUseCase authentication;
    private final SessionResumeUseCase sessionResume;
    private final MessageSubmissionPort submissions;
    private final MessageHistoryPort history;
    private final ConversationDirectoryPort directory;
    private final ConversationParticipantPort participants;
    private final MessageReactionPort reactions;
    private final MessagePinPort pins;
    private final MessageEditPort edits;
    private final MessageForwardPort forwards;
    private final MessageSearchPort search;
    private final DeviceManagementService deviceManagement;
    private final Executor authenticationExecutor;
    private final Executor messagingExecutor;
    private final AuthenticationAdmissionControl admission;
    private final AuthenticationEventSink events;
    private final MessagingEventSink messagingEvents;
    private final DeviceManagementEventSink deviceEvents;
    private final DeviceConnectionRegistry deviceConnections;
    private final ConversationLiveRouter liveRouter;
    private final Duration handshakeTimeout;
    private final Duration authenticationTimeout;
    private final boolean messageForwardingEnabled;
    private final boolean messageSearchEnabled;
    private AccountBlockUseCase accountBlocks =
            (actor, intent) -> AccountBlockResult.Rejected.TARGET_UNAVAILABLE;
    private boolean accountBlockingEnabled;
    private ScheduledFuture<?> upgradeDeadline;

    public V2WebSocketUpgradeHandler(
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            MessageReactionPort reactions,
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
        this(authentication, sessionResume, submissions, history, directory, reactions,
                command -> MessagePinResult.Rejected.NOT_AUTHORIZED, deviceManagement,
                authenticationExecutor, messagingExecutor, admission, events, messagingEvents,
                deviceEvents, deviceConnections, liveRouter, handshakeTimeout,
                authenticationTimeout);
    }

    public V2WebSocketUpgradeHandler(
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
        this(authentication, sessionResume, submissions, history, directory, reactions, pins,
                command -> MessageEditResult.Rejected.NOT_AUTHORIZED, deviceManagement,
                authenticationExecutor, messagingExecutor, admission, events, messagingEvents,
                deviceEvents, deviceConnections, liveRouter, handshakeTimeout,
                authenticationTimeout);
    }

    public V2WebSocketUpgradeHandler(
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
        this(authentication, sessionResume, submissions, history, directory,
                query -> ConversationParticipantResult.Rejected.NOT_AUTHORIZED,
                reactions, pins, edits, deviceManagement, authenticationExecutor,
                messagingExecutor, admission, events, messagingEvents, deviceEvents,
                deviceConnections, liveRouter, handshakeTimeout, authenticationTimeout);
    }

    public V2WebSocketUpgradeHandler(
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
        this(authentication, sessionResume, submissions, history, directory, participants,
                reactions, pins, edits,
                command -> MessageForwardResult.Rejected.NOT_AUTHORIZED,
                deviceManagement, authenticationExecutor, messagingExecutor, admission, events,
                messagingEvents, deviceEvents, deviceConnections, liveRouter, handshakeTimeout,
                authenticationTimeout);
    }

    public V2WebSocketUpgradeHandler(
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            ConversationParticipantPort participants,
            MessageReactionPort reactions,
            MessagePinPort pins,
            MessageEditPort edits,
            MessageForwardPort forwards,
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
        this(authentication, sessionResume, submissions, history, directory, participants,
                reactions, pins, edits, forwards, deviceManagement, authenticationExecutor,
                messagingExecutor, admission, events, messagingEvents, deviceEvents,
                deviceConnections, liveRouter, handshakeTimeout, authenticationTimeout, false);
    }

    public V2WebSocketUpgradeHandler(
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            ConversationParticipantPort participants,
            MessageReactionPort reactions,
            MessagePinPort pins,
            MessageEditPort edits,
            MessageForwardPort forwards,
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
            Duration authenticationTimeout,
            boolean messageForwardingEnabled) {
        this(authentication, sessionResume, submissions, history, directory, participants,
                reactions, pins, edits, forwards,
                query -> MessageSearchResult.Rejected.NOT_AUTHORIZED,
                deviceManagement, authenticationExecutor, messagingExecutor, admission, events,
                messagingEvents, deviceEvents, deviceConnections, liveRouter, handshakeTimeout,
                authenticationTimeout, messageForwardingEnabled, false);
    }

    public V2WebSocketUpgradeHandler(
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            ConversationParticipantPort participants,
            MessageReactionPort reactions,
            MessagePinPort pins,
            MessageEditPort edits,
            MessageForwardPort forwards,
            MessageSearchPort search,
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
            Duration authenticationTimeout,
            boolean messageForwardingEnabled,
            boolean messageSearchEnabled) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.sessionResume = Objects.requireNonNull(sessionResume, "sessionResume");
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.history = Objects.requireNonNull(history, "history");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.reactions = Objects.requireNonNull(reactions, "reactions");
        this.pins = Objects.requireNonNull(pins, "pins");
        this.edits = Objects.requireNonNull(edits, "edits");
        this.forwards = Objects.requireNonNull(forwards, "forwards");
        this.search = Objects.requireNonNull(search, "search");
        this.deviceManagement = Objects.requireNonNull(deviceManagement, "deviceManagement");
        this.authenticationExecutor = Objects.requireNonNull(
                authenticationExecutor, "authenticationExecutor");
        this.messagingExecutor = Objects.requireNonNull(messagingExecutor, "messagingExecutor");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.events = Objects.requireNonNull(events, "events");
        this.messagingEvents = Objects.requireNonNull(messagingEvents, "messagingEvents");
        this.deviceEvents = Objects.requireNonNull(deviceEvents, "deviceEvents");
        this.deviceConnections = Objects.requireNonNull(deviceConnections, "deviceConnections");
        this.liveRouter = Objects.requireNonNull(liveRouter, "liveRouter");
        this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        this.authenticationTimeout = Objects.requireNonNull(
                authenticationTimeout, "authenticationTimeout");
        this.messageForwardingEnabled = messageForwardingEnabled;
        this.messageSearchEnabled = messageSearchEnabled;
    }

    public V2WebSocketUpgradeHandler(
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            ConversationDirectoryPort directory,
            ConversationParticipantPort participants,
            MessageReactionPort reactions,
            MessagePinPort pins,
            MessageEditPort edits,
            MessageForwardPort forwards,
            MessageSearchPort search,
            AccountBlockUseCase accountBlocks,
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
            Duration authenticationTimeout,
            boolean messageForwardingEnabled,
            boolean messageSearchEnabled,
            boolean accountBlockingEnabled) {
        this(authentication, sessionResume, submissions, history, directory, participants,
                reactions, pins, edits, forwards, search, deviceManagement,
                authenticationExecutor, messagingExecutor, admission, events, messagingEvents,
                deviceEvents, deviceConnections, liveRouter, handshakeTimeout,
                authenticationTimeout, messageForwardingEnabled, messageSearchEnabled);
        this.accountBlocks = Objects.requireNonNull(accountBlocks, "accountBlocks");
        this.accountBlockingEnabled = accountBlockingEnabled;
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        Runnable close = context::close;
        upgradeDeadline = context.executor().schedule(
                close, handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        context.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        cancelUpgradeDeadline();
        context.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            cancelUpgradeDeadline();
            V2ApplicationPipeline.install(
                    context.pipeline(),
                    authentication,
                    sessionResume,
                    submissions,
                    history,
                    directory,
                    participants,
                    reactions,
                    pins,
                    edits,
                    forwards,
                    search,
                    accountBlocks,
                    deviceManagement,
                    authenticationExecutor,
                    messagingExecutor,
                    admission,
                    events,
                    messagingEvents,
                    deviceEvents,
                    deviceConnections,
                    liveRouter,
                    handshakeTimeout,
                    authenticationTimeout,
                    messageForwardingEnabled,
                    messageSearchEnabled,
                    accountBlockingEnabled);
            context.pipeline().remove(this);
        }
        context.fireUserEventTriggered(event);
    }

    private void cancelUpgradeDeadline() {
        if (upgradeDeadline != null) {
            upgradeDeadline.cancel(false);
            upgradeDeadline = null;
        }
    }
}

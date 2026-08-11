package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.AuthenticationUseCase;
import com.fallingnight.chat.application.identity.SessionResumeUseCase;
import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.MessageSubmissionPort;
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
    private final Executor authenticationExecutor;
    private final Executor messagingExecutor;
    private final AuthenticationAdmissionControl admission;
    private final AuthenticationEventSink events;
    private final Duration handshakeTimeout;
    private final Duration authenticationTimeout;
    private ScheduledFuture<?> upgradeDeadline;

    public V2WebSocketUpgradeHandler(
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            Executor authenticationExecutor,
            Executor messagingExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            Duration handshakeTimeout,
            Duration authenticationTimeout) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.sessionResume = Objects.requireNonNull(sessionResume, "sessionResume");
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.history = Objects.requireNonNull(history, "history");
        this.authenticationExecutor = Objects.requireNonNull(
                authenticationExecutor, "authenticationExecutor");
        this.messagingExecutor = Objects.requireNonNull(messagingExecutor, "messagingExecutor");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.events = Objects.requireNonNull(events, "events");
        this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        this.authenticationTimeout = Objects.requireNonNull(
                authenticationTimeout, "authenticationTimeout");
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
                    authenticationExecutor,
                    messagingExecutor,
                    admission,
                    events,
                    handshakeTimeout,
                    authenticationTimeout);
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

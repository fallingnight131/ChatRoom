package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.AuthenticationUseCase;
import com.fallingnight.chat.application.identity.SessionResumeUseCase;
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
    private final Executor authenticationExecutor;
    private final AuthenticationAdmissionControl admission;
    private final AuthenticationEventSink events;
    private final Duration handshakeTimeout;
    private final Duration authenticationTimeout;
    private ScheduledFuture<?> upgradeDeadline;

    public V2WebSocketUpgradeHandler(
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            Executor authenticationExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            Duration handshakeTimeout,
            Duration authenticationTimeout) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.sessionResume = Objects.requireNonNull(sessionResume, "sessionResume");
        this.authenticationExecutor = Objects.requireNonNull(
                authenticationExecutor, "authenticationExecutor");
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
                    authenticationExecutor,
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

package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.AuthenticationUseCase;
import com.fallingnight.chat.application.identity.SessionResumeUseCase;
import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.MessageSubmissionPort;
import io.netty.channel.ChannelPipeline;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Installs the ordered post-upgrade V2 frame, phase, authentication, and idle pipeline. */
public final class V2ApplicationPipeline {
    private V2ApplicationPipeline() {}

    public static void install(
            ChannelPipeline pipeline,
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
        pipeline.addLast("v2-messaging", new V2MessagingHandler(
                submissions, history, messagingExecutor));
        pipeline.addLast("v2-authenticated-idle-close", new V2AuthenticatedIdleCloseHandler());
    }
}

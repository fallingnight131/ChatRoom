package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticationService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1LoginService;
import com.fallingnight.chat.gateway.compatibility.v1.V1AccountConnectionRegistry;
import com.fallingnight.chat.gateway.compatibility.v1.V1AuthenticationTimeoutHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1HeartbeatHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonLifecycleCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1JsonLoginCodec;
import com.fallingnight.chat.gateway.compatibility.v1.V1WebLoginHandler;
import com.fallingnight.chat.gateway.compatibility.v1.V1WebSocketUpgradeHandler;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionControl;
import com.fallingnight.chat.gateway.transport.AuthenticationEventSink;
import com.fallingnight.chat.identity.crypto.Argon2idCredentialHasher;
import com.fallingnight.chat.identity.crypto.CompatibleCredentialVerifier;
import com.fallingnight.chat.persistence.postgres.PostgresIdentityAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresLegacyV1AccountProjection;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/** Real V1 login composition kept detached from the product listener. */
public final class V1CompatibilityModule {
    private final LegacyV1LoginService login;
    private final Clock clock;
    private final V1AccountConnectionRegistry connections;

    private V1CompatibilityModule(
            LegacyV1LoginService login,
            Clock clock,
            V1AccountConnectionRegistry connections) {
        this.login = Objects.requireNonNull(login, "login");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public static V1CompatibilityModule create(DataSource dataSource, Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(clock, "clock");
        PostgresIdentityAdapter identity = new PostgresIdentityAdapter(dataSource);
        PostgresLegacyV1AccountProjection legacy =
                new PostgresLegacyV1AccountProjection(dataSource);
        LegacyV1AuthenticationService authentication =
                new LegacyV1AuthenticationService(
                        identity,
                        new CompatibleCredentialVerifier(),
                        identity,
                        new Argon2idCredentialHasher(),
                        identity,
                        legacy,
                        clock);
        return new V1CompatibilityModule(
                new LegacyV1LoginService(authentication, legacy),
                clock,
                new V1AccountConnectionRegistry());
    }

    public V1WebSocketUpgradeHandler newWebSocketUpgradeHandler(
            Executor authenticationExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            Duration upgradeTimeout,
            Duration authenticationTimeout,
            Duration authenticatedIdleTimeout) {
        return new V1WebSocketUpgradeHandler(
                pipeline -> installWebApplicationPipeline(
                        pipeline,
                        authenticationExecutor,
                        admission,
                        events,
                        authenticationTimeout,
                        authenticatedIdleTimeout),
                upgradeTimeout);
    }

    private void installWebApplicationPipeline(
            ChannelPipeline pipeline,
            Executor authenticationExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            Duration authenticationTimeout,
            Duration authenticatedIdleTimeout) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(authenticatedIdleTimeout, "authenticatedIdleTimeout");
        if (authenticatedIdleTimeout.isZero() || authenticatedIdleTimeout.isNegative()) {
            throw new IllegalArgumentException("authenticatedIdleTimeout must be positive");
        }
        V1JsonLifecycleCodec lifecycleCodec = new V1JsonLifecycleCodec(clock);
        pipeline.addLast("v1-authentication-timeout", new V1AuthenticationTimeoutHandler(
                authenticationTimeout));
        pipeline.addLast("v1-authenticated-idle-state", new IdleStateHandler(
                authenticatedIdleTimeout.toMillis(), 0, 0, TimeUnit.MILLISECONDS));
        pipeline.addLast("v1-login", new V1WebLoginHandler(
                login,
                new V1JsonLoginCodec(clock),
                lifecycleCodec,
                connections,
                authenticationExecutor,
                admission,
                events));
        pipeline.addLast("v1-heartbeat", new V1HeartbeatHandler(lifecycleCodec));
    }
}

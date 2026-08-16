package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.notification.WebPushSubscriptionMutationService;
import io.netty.channel.ChannelPipeline;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Optional subscription HTTP component installed before WebSocket endpoint handling. */
public final class WebPushSubscriptionHttpGatewayComponent {
    private static final WebPushSubscriptionHttpGatewayComponent DISABLED =
            new WebPushSubscriptionHttpGatewayComponent(null, null, null, null, null);

    private final WebPushHttpApiPolicy policy;
    private final WebPushHttpSessionPort sessions;
    private final WebPushSubscriptionMutationService mutations;
    private final Clock clock;
    private final WebPushHttpEventSink events;

    private WebPushSubscriptionHttpGatewayComponent(
            WebPushHttpApiPolicy policy,
            WebPushHttpSessionPort sessions,
            WebPushSubscriptionMutationService mutations,
            Clock clock,
            WebPushHttpEventSink events) {
        this.policy = policy;
        this.sessions = sessions;
        this.mutations = mutations;
        this.clock = clock;
        this.events = events;
    }

    public static WebPushSubscriptionHttpGatewayComponent disabled() {
        return DISABLED;
    }

    public static WebPushSubscriptionHttpGatewayComponent enabled(
            WebPushHttpApiPolicy policy,
            WebPushHttpSessionPort sessions,
            WebPushSubscriptionMutationService mutations,
            Clock clock,
            WebPushHttpEventSink events) {
        if (!Objects.requireNonNull(policy, "policy").enabled()) {
            throw new IllegalArgumentException("Web Push HTTP policy must be enabled");
        }
        return new WebPushSubscriptionHttpGatewayComponent(
                policy,
                Objects.requireNonNull(sessions, "sessions"),
                Objects.requireNonNull(mutations, "mutations"),
                Objects.requireNonNull(clock, "clock"),
                Objects.requireNonNull(events, "events"));
    }

    public boolean enabled() {
        return policy != null;
    }

    public void install(ChannelPipeline pipeline, Executor worker) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(worker, "worker");
        if (enabled()) {
            pipeline.addLast("web-push-subscription-http",
                    new WebPushSubscriptionHttpHandler(
                            policy,
                            sessions,
                            mutations,
                            new WebPushSubscriptionJsonCodec(),
                            clock,
                            worker,
                            events));
        }
    }
}

package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.notification.WebPushHttpCredentialIssueService;
import io.netty.channel.ChannelPipeline;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/** Optional runtime component that keeps capability policy and handler installation aligned. */
public final class WebPushHttpCredentialGatewayComponent {
    private static final WebPushHttpCredentialGatewayComponent DISABLED =
            new WebPushHttpCredentialGatewayComponent(null, null);

    private final WebPushHttpCredentialIssueService service;
    private final WebPushHttpCredentialEventSink events;

    private WebPushHttpCredentialGatewayComponent(
            WebPushHttpCredentialIssueService service, WebPushHttpCredentialEventSink events) {
        this.service = service;
        this.events = events;
    }

    public static WebPushHttpCredentialGatewayComponent disabled() {
        return DISABLED;
    }

    public static WebPushHttpCredentialGatewayComponent enabled(
            WebPushHttpCredentialIssueService service, WebPushHttpCredentialEventSink events) {
        return new WebPushHttpCredentialGatewayComponent(
                Objects.requireNonNull(service, "service"), Objects.requireNonNull(events, "events"));
    }

    public boolean enabled() {
        return service != null;
    }

    public Optional<WebPushHttpCredentialIssueService> service() {
        return Optional.ofNullable(service);
    }

    void install(ChannelPipeline pipeline, Executor executor) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(executor, "executor");
        if (enabled()) {
            pipeline.addLast("v2-web-push-http-credential",
                    new V2WebPushHttpCredentialHandler(service, executor, events));
        }
    }
}

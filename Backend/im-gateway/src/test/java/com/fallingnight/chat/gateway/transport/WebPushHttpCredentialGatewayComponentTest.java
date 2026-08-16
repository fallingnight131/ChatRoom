package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.notification.WebPushDeliveryPolicy;
import com.fallingnight.chat.application.notification.WebPushHttpCredentialIssueService;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class WebPushHttpCredentialGatewayComponentTest {
    @Test
    void installsNothingWhenDisabledAndExactlyOneHandlerWhenEnabled() {
        EmbeddedChannel disabledChannel = new EmbeddedChannel();
        try {
            var disabled = WebPushHttpCredentialGatewayComponent.disabled();
            assertFalse(disabled.enabled());
            assertTrue(disabled.service().isEmpty());
            disabled.install(disabledChannel.pipeline(), Runnable::run);
            assertNull(disabledChannel.pipeline().get("v2-web-push-http-credential"));
        } finally {
            disabledChannel.finishAndReleaseAll();
        }

        var service = new WebPushHttpCredentialIssueService(
                new WebPushDeliveryPolicy(true), (actor, at) -> java.util.Optional.empty(),
                Clock.systemUTC());
        EmbeddedChannel enabledChannel = new EmbeddedChannel();
        try {
            var enabled = WebPushHttpCredentialGatewayComponent.enabled(
                    service, WebPushHttpCredentialEventSink.noop());
            assertTrue(enabled.enabled());
            assertTrue(enabled.service().isPresent());
            enabled.install(enabledChannel.pipeline(), Runnable::run);
            assertNotNull(enabledChannel.pipeline().get("v2-web-push-http-credential"));
        } finally {
            enabledChannel.finishAndReleaseAll();
        }
    }
}

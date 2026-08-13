package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.identity.AuthenticationResult;
import com.fallingnight.chat.application.identity.DeviceManagementService;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class V2WebSocketUpgradeHandlerTest {
    @Test
    void installsApplicationPipelineOnlyAfterUpgrade() {
        V2WebSocketUpgradeHandler handler = handler(Duration.ofSeconds(1));
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            assertNull(channel.pipeline().get(V2HandshakeHandler.class));

            channel.pipeline().fireUserEventTriggered(
                    new WebSocketServerProtocolHandler.HandshakeComplete(
                            "/v2/windows",
                            EmptyHttpHeaders.INSTANCE,
                            WebSocketEndpointPolicyHandler.V2_SUBPROTOCOL));

            assertNull(channel.pipeline().get(V2WebSocketUpgradeHandler.class));
            assertTrue(channel.pipeline().get(V2HandshakeHandler.class) != null);
            assertTrue(channel.pipeline().get(V2DeviceConnectionTracker.class) != null);
            assertTrue(channel.pipeline().get(V2DeviceManagementHandler.class) != null);
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void closesConnectionWhenUpgradeDeadlineExpires() {
        EmbeddedChannel channel = new EmbeddedChannel(handler(Duration.ofMillis(10)));
        try {
            channel.advanceTimeBy(11, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static V2WebSocketUpgradeHandler handler(Duration handshakeTimeout) {
        return new V2WebSocketUpgradeHandler(
                command -> AuthenticationResult.Rejected.INSTANCE,
                command -> AuthenticationResult.Rejected.INSTANCE,
                command -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                query -> new com.fallingnight.chat.application.conversation
                        .ConversationDirectoryPage(
                                java.util.List.of(), java.util.Optional.empty(), false),
                new DeviceManagementService(new RejectingDeviceManagementPort()),
                Runnable::run,
                Runnable::run,
                AuthenticationAdmissionControl.allowAll(),
                AuthenticationEventSink.noop(),
                MessagingEventSink.noop(),
                DeviceManagementEventSink.noop(),
                new DeviceConnectionRegistry(),
                ConversationLiveRouter.noop(),
                handshakeTimeout,
                Duration.ofSeconds(1));
    }

    private static final class RejectingDeviceManagementPort implements
            com.fallingnight.chat.application.identity.DeviceManagementPort {
        @Override public com.fallingnight.chat.application.identity.DeviceDirectoryResult
                listActive(com.fallingnight.chat.application.identity.AuthenticatedDeviceActor actor) {
            return com.fallingnight.chat.application.identity.DeviceDirectoryResult.Rejected.INSTANCE;
        }
        @Override public com.fallingnight.chat.application.identity.DeviceRevocationResult revokeOther(
                com.fallingnight.chat.application.identity.AuthenticatedDeviceActor actor,
                java.util.UUID target) {
            return com.fallingnight.chat.application.identity.DeviceRevocationResult.Rejected.INSTANCE;
        }
    }
}

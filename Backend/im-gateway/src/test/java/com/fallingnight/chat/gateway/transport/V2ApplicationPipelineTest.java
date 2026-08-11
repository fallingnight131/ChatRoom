package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fallingnight.chat.application.identity.AuthenticationResult;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class V2ApplicationPipelineTest {
    @Test
    void installsDeterministicPostUpgradeOrder() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            V2ApplicationPipeline.install(
                    channel.pipeline(),
                    command -> AuthenticationResult.Rejected.INSTANCE,
                    command -> AuthenticationResult.Rejected.INSTANCE,
                    command -> MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                    query -> MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                    query -> new com.fallingnight.chat.application.conversation
                            .ConversationDirectoryPage(
                                    java.util.List.of(), java.util.Optional.empty(), false),
                    Runnable::run,
                    Runnable::run,
                    AuthenticationAdmissionControl.allowAll(),
                    AuthenticationEventSink.noop(),
                    MessagingEventSink.noop(),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30));

            List<String> names = channel.pipeline().names();
            assertEquals(List.of(
                    "v2-frame-aggregator",
                    "v2-envelope-decoder",
                    "v2-frame-error-normalizer",
                    "v2-envelope-encoder",
                    "v2-frame-close",
                    "v2-phase-timeouts",
                    "v2-handshake",
                    "v2-authentication",
                    "v2-messaging",
                    "v2-authenticated-idle-close"),
                    names.subList(0, 10));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}

package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fallingnight.chat.application.messaging.MessageForwardCommand;
import com.fallingnight.chat.application.messaging.MessageForwardResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryMessageForwardAdmissionPortTest {
    private static final UUID SOURCE_CONVERSATION = UUID.randomUUID();
    private static final UUID SOURCE_MESSAGE = UUID.randomUUID();
    private static final UUID TARGET_CONVERSATION = UUID.randomUUID();
    private static final UUID DEVICE = UUID.randomUUID();

    @Test
    void boundsAttemptsPerAuthenticatedAccountAndResetsAfterWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-14T00:00:00Z"));
        AtomicInteger delegated = new AtomicInteger();
        InMemoryMessageForwardAdmissionPort port = new InMemoryMessageForwardAdmissionPort(
                command -> {
                    delegated.incrementAndGet();
                    return MessageForwardResult.Rejected.NOT_AUTHORIZED;
                }, new MessageForwardAdmissionLimits(Duration.ofSeconds(60), 2, 16), clock);
        UUID account = UUID.randomUUID();

        assertEquals(MessageForwardResult.Rejected.NOT_AUTHORIZED,
                port.forward(command(account, "client-1")));
        assertEquals(MessageForwardResult.Rejected.NOT_AUTHORIZED,
                port.forward(command(account, "client-2")));
        assertEquals(MessageForwardResult.Rejected.RATE_LIMITED,
                port.forward(command(account, "client-3")));
        assertEquals(2, delegated.get());
        assertEquals(1, port.activeAccountCount());

        clock.advance(Duration.ofSeconds(60));
        assertEquals(MessageForwardResult.Rejected.NOT_AUTHORIZED,
                port.forward(command(account, "client-4")));
        assertEquals(3, delegated.get());
    }

    @Test
    void failsClosedAtBoundedAccountCardinalityAndValidatesConfiguration() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        InMemoryMessageForwardAdmissionPort port = new InMemoryMessageForwardAdmissionPort(
                command -> MessageForwardResult.Rejected.NOT_AUTHORIZED,
                new MessageForwardAdmissionLimits(Duration.ofSeconds(10), 1, 16), clock);
        for (int index = 0; index < 16; index++) {
            assertEquals(MessageForwardResult.Rejected.NOT_AUTHORIZED,
                    port.forward(command(UUID.randomUUID(), "client-" + index)));
        }
        assertEquals(MessageForwardResult.Rejected.RATE_LIMITED,
                port.forward(command(UUID.randomUUID(), "client-overflow")));
        assertEquals(16, port.activeAccountCount());
        assertThrows(IllegalArgumentException.class, () ->
                new MessageForwardAdmissionLimits(Duration.ZERO, 1, 16));
        assertThrows(IllegalArgumentException.class, () ->
                new MessageForwardAdmissionLimits(Duration.ofSeconds(1), 0, 16));
        assertThrows(IllegalArgumentException.class, () ->
                new MessageForwardAdmissionLimits(Duration.ofSeconds(1), 1, 15));
    }

    private static MessageForwardCommand command(UUID account, String clientMessageId) {
        return new MessageForwardCommand(
                SOURCE_CONVERSATION, SOURCE_MESSAGE, 0, TARGET_CONVERSATION,
                account, DEVICE, clientMessageId);
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        private MutableClock(Instant value) { this.value = value; }
        private void advance(Duration duration) { value = value.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}

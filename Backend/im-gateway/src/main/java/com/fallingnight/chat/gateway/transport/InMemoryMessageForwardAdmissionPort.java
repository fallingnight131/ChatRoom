package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.messaging.MessageForwardCommand;
import com.fallingnight.chat.application.messaging.MessageForwardPort;
import com.fallingnight.chat.application.messaging.MessageForwardResult;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Process-local, bounded account limiter around the durable forwarding port.
 * PostgreSQL still performs all source and destination authorization.
 */
public final class InMemoryMessageForwardAdmissionPort implements MessageForwardPort {
    private final MessageForwardPort delegate;
    private final MessageForwardAdmissionLimits limits;
    private final Clock clock;
    private final Map<UUID, Window> accounts = new HashMap<>();

    public InMemoryMessageForwardAdmissionPort(
            MessageForwardPort delegate, MessageForwardAdmissionLimits limits, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public MessageForwardResult forward(MessageForwardCommand command) {
        Objects.requireNonNull(command, "command");
        if (!acquire(command.actorAccountId())) {
            return MessageForwardResult.Rejected.RATE_LIMITED;
        }
        return Objects.requireNonNull(delegate.forward(command), "forward result");
    }

    public synchronized int activeAccountCount() {
        evictExpired(clock.instant());
        return accounts.size();
    }

    private synchronized boolean acquire(UUID accountId) {
        Instant now = clock.instant();
        evictExpired(now);
        Window current = accounts.get(accountId);
        if (current == null) {
            if (accounts.size() >= limits.maximumTrackedAccounts()) return false;
            accounts.put(accountId, new Window(now, 1));
            return true;
        }
        if (current.attempts >= limits.attemptsPerAccount()) return false;
        accounts.put(accountId, new Window(current.startedAt, current.attempts + 1));
        return true;
    }

    private void evictExpired(Instant now) {
        Iterator<Window> iterator = accounts.values().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next();
            if (!now.isBefore(window.startedAt.plus(limits.window()))) iterator.remove();
        }
    }

    private record Window(Instant startedAt, int attempts) {}
}

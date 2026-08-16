package com.fallingnight.chat.application.notification;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete bounded protected subscriptions for one current account; closes as one unit. */
public final class ProtectedWebPushSubscriptionBatch implements AutoCloseable {
    public static final int MAX_SUBSCRIPTIONS = 10;

    private final UUID accountId;
    private final List<ProtectedWebPushSubscription> subscriptions;
    private boolean closed;

    public ProtectedWebPushSubscriptionBatch(
            UUID accountId, List<ProtectedWebPushSubscription> subscriptions) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.subscriptions = List.copyOf(
                Objects.requireNonNull(subscriptions, "subscriptions"));
        if (this.subscriptions.size() > MAX_SUBSCRIPTIONS) {
            closeAll(this.subscriptions);
            throw new IllegalArgumentException("too many protected Web Push subscriptions");
        }
        for (ProtectedWebPushSubscription subscription : this.subscriptions) {
            Objects.requireNonNull(subscription, "subscription");
            if (!accountId.equals(subscription.accountId())) {
                closeAll(this.subscriptions);
                throw new IllegalArgumentException("protected subscription account differs");
            }
        }
    }

    public UUID accountId() { return accountId; }

    public synchronized List<ProtectedWebPushSubscription> subscriptions() {
        if (closed) throw new IllegalStateException("protected subscription batch is closed");
        return subscriptions;
    }

    public synchronized boolean isClosed() { return closed; }

    @Override
    public synchronized void close() {
        if (!closed) {
            closeAll(subscriptions);
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "ProtectedWebPushSubscriptionBatch[accountId=" + accountId
                + ", subscriptionCount=" + subscriptions.size()
                + ", protectedBytes=REDACTED]";
    }

    private static void closeAll(List<ProtectedWebPushSubscription> subscriptions) {
        for (ProtectedWebPushSubscription subscription : subscriptions) {
            if (subscription != null) subscription.close();
        }
    }
}

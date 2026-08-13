package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.messaging.MessageEditResult;
import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessagePinResult;
import com.fallingnight.chat.application.messaging.MessageReactionResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.application.routing.GatewayRouteRegistrationService;
import io.netty.channel.Channel;
import java.util.Objects;
import java.util.UUID;

/** Local product router decorated with post-response Redis route activation. */
public final class DistributedConversationLiveRouter implements ConversationLiveRouter {
    private final SingleGatewayConversationLiveRouter local;
    private final GatewayRouteRegistrationService registration;

    public DistributedConversationLiveRouter(SingleGatewayConversationLiveRouter local,
            GatewayRouteRegistrationService registration) {
        this.local = Objects.requireNonNull(local, "local");
        this.registration = Objects.requireNonNull(registration, "registration");
    }

    @Override public MessageHistoryResult readAndSubscribe(
            Channel channel, MessageHistoryQuery query, MessageHistoryPort history) {
        return local.readAndSubscribe(channel, query, history);
    }

    @Override public void activateSubscription(
            Channel channel, MessageHistoryQuery query, MessageHistoryPort history) {
        UUID conversationId = query.conversationId();
        long caughtUp = local.observedSequence(channel, conversationId);
        if (!local.subscribedConversations(channel).contains(conversationId)) return;
        try {
            if (registration.registerAfterCatchUp(conversationId, caughtUp,
                    (ignored, after) -> local.repairAfterRouteRegistration(
                            conversationId, after, history)).isEmpty()) {
                throw new IllegalStateException("distributed conversation route was rejected");
            }
        } catch (RuntimeException exception) {
            local.unsubscribeConversation(channel, conversationId);
            throw exception;
        }
    }

    @Override public LivePublishResult publish(StoredMessage message) {
        return local.publish(message);
    }
    @Override public LivePublishResult publishReaction(MessageReactionResult.Applied reaction) {
        return local.publishReaction(reaction);
    }
    @Override public LivePublishResult publishPin(MessagePinResult.Applied pin) {
        return local.publishPin(pin);
    }
    @Override public LivePublishResult publishEdit(MessageEditResult.Applied edit) {
        return local.publishEdit(edit);
    }

    @Override public void unsubscribe(Channel channel) {
        var conversations = local.subscribedConversations(channel);
        local.unsubscribe(channel);
        for (UUID conversation : conversations) {
            if (!local.hasSubscribers(conversation)) {
                try { registration.removeConversation(conversation); }
                catch (RuntimeException ignored) {
                    // Expiring reconstructable route cleanup is best effort on disconnect.
                }
            }
        }
    }

    public boolean renewActiveRoutes() {
        boolean renewed = true;
        for (var route : local.activeConversationSequences().entrySet()) {
            try {
                if (!registration.renewConversation(route.getKey(), route.getValue())) {
                    renewed = false;
                }
            } catch (RuntimeException exception) {
                renewed = false;
            }
        }
        return renewed;
    }
}

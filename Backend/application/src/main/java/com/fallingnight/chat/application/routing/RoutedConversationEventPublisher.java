package com.fallingnight.chat.application.routing;

import com.fallingnight.chat.application.messaging.ConversationEventOutboxClaim;
import com.fallingnight.chat.application.messaging.ConversationEventPublicationOutcome;
import com.fallingnight.chat.application.messaging.ConversationEventPublicationPort;
import java.time.Clock;
import java.util.Objects;

/** Resolves leased targets then appends one payload-free hint to every bounded stream. */
public final class RoutedConversationEventPublisher
        implements ConversationEventPublicationPort {
    public static final int MAX_TARGET_GATEWAYS = 64;
    public static final int MINIMUM_STREAM_LENGTH = 100;
    public static final int MAXIMUM_STREAM_LENGTH = 100_000;

    private final GatewayRouteLeasePort routes;
    private final GatewayLiveEventPublishPort events;
    private final int maximumTargetGateways;
    private final int maximumStreamLength;
    private final Clock clock;

    public RoutedConversationEventPublisher(GatewayRouteLeasePort routes,
            GatewayLiveEventPublishPort events, int maximumTargetGateways,
            int maximumStreamLength, Clock clock) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.events = Objects.requireNonNull(events, "events");
        if (maximumTargetGateways < 1 || maximumTargetGateways > MAX_TARGET_GATEWAYS) {
            throw new IllegalArgumentException("maximumTargetGateways must be in 1..64");
        }
        if (maximumStreamLength < MINIMUM_STREAM_LENGTH
                || maximumStreamLength > MAXIMUM_STREAM_LENGTH) {
            throw new IllegalArgumentException("maximumStreamLength outside reviewed range");
        }
        this.maximumTargetGateways = maximumTargetGateways;
        this.maximumStreamLength = maximumStreamLength;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ConversationEventPublicationOutcome publish(ConversationEventOutboxClaim claim) {
        Objects.requireNonNull(claim, "claim");
        ConversationGatewayRoutePage targets = routes.findConversationGateways(
                claim.conversationId(), clock.instant(), maximumTargetGateways);
        if (targets.gatewayIds().size() > maximumTargetGateways || !targets.complete()) {
            return ConversationEventPublicationOutcome.DEPENDENCY_REJECTED;
        }
        ConversationEventPublicationOutcome aggregate =
                ConversationEventPublicationOutcome.PUBLISHED;
        for (var target : targets.gatewayIds()) {
            var result = events.publish(new GatewayLiveEventHint(target, claim.eventId(),
                    claim.conversationId(), claim.conversationSequence()), maximumStreamLength);
            if (result == GatewayLiveEventPublishPort.PublishResult.DEPENDENCY_REJECTED) {
                aggregate = ConversationEventPublicationOutcome.DEPENDENCY_REJECTED;
            } else if (result
                    == GatewayLiveEventPublishPort.PublishResult.DEPENDENCY_UNAVAILABLE
                    && aggregate != ConversationEventPublicationOutcome.DEPENDENCY_REJECTED) {
                aggregate = ConversationEventPublicationOutcome.DEPENDENCY_UNAVAILABLE;
            }
        }
        return aggregate;
    }
}

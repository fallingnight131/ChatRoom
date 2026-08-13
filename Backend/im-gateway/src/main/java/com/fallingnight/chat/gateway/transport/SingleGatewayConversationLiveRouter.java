package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.application.messaging.MessageReactionResult;
import com.fallingnight.chat.application.messaging.MessagePinResult;
import com.fallingnight.chat.application.messaging.MessageEditResult;
import com.fallingnight.chat.application.routing.GatewayLiveEventHint;
import com.fallingnight.chat.application.routing.LocalConversationHintResult;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageRecord;
import com.fallingnight.chat.protocol.v2.MessageReactionChangedRecord;
import com.fallingnight.chat.protocol.v2.MessagePinChangedRecord;
import com.fallingnight.chat.protocol.v2.MessageEditedRecord;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.MessagingPayloadPolicy;
import com.google.protobuf.ByteString;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded per-channel local conversation router; Redis/multi-gateway routing belongs to M5. */
public final class SingleGatewayConversationLiveRouter implements ConversationLiveRouter {
    private static final int MAX_CONVERSATIONS_PER_CHANNEL = 100;
    @SuppressWarnings("rawtypes")
    private static final AttributeKey<java.util.Set> ACTIVE_CONVERSATIONS =
            AttributeKey.valueOf("v2.activeConversations");
    private static final AttributeKey<Boolean> CLEANUP_REGISTERED =
            AttributeKey.valueOf("v2.liveCleanupRegistered");
    @SuppressWarnings("rawtypes")
    private static final AttributeKey<java.util.Map> LIVE_MESSAGE_SEQUENCES =
            AttributeKey.valueOf("v2.liveMessageSequences");

    private final ConcurrentHashMap<UUID, Route> routes = new ConcurrentHashMap<>();
    private final Clock clock;

    public SingleGatewayConversationLiveRouter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public MessageHistoryResult readAndSubscribe(
            Channel channel, MessageHistoryQuery query, MessageHistoryPort history) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(history, "history");
        java.util.Set<UUID> subscriptions = subscriptions(channel);
        if (!subscriptions.contains(query.conversationId())
                && subscriptions.size() >= MAX_CONVERSATIONS_PER_CHANNEL) {
            throw new IllegalStateException("live conversation subscription limit reached");
        }
        Route route = routes.computeIfAbsent(query.conversationId(), ignored -> new Route());
        synchronized (route) {
            try {
                MessageHistoryResult result = history.readAfter(query);
                if (result instanceof MessageHistoryResult.Page page
                        && !page.hasMore()
                        && channel.isActive()
                        && channel.attr(V2ConnectionAttributes.AUTHENTICATED).get() != null) {
                    route.channels.add(channel);
                    subscriptions.add(query.conversationId());
                    liveMessageSequences(channel).merge(
                            query.conversationId(), page.nextSequence(), Math::max);
                    registerCleanup(channel);
                } else if (route.channels.isEmpty()) {
                    routes.remove(query.conversationId(), route);
                }
                return result;
            } catch (RuntimeException exception) {
                if (route.channels.isEmpty()) routes.remove(query.conversationId(), route);
                throw exception;
            }
        }
    }

    @Override
    public LivePublishResult publish(StoredMessage message) {
        Objects.requireNonNull(message, "message");
        Route route = routes.get(message.conversationId());
        if (route == null) return LivePublishResult.NONE;
        MessageRecord record = record(message);
        int published = 0;
        int slowClosed = 0;
        long maximumBytesBeforeWritable = 0;
        synchronized (route) {
            for (Channel channel : java.util.List.copyOf(route.channels)) {
                AuthenticatedConnection identity = channel.attr(V2ConnectionAttributes.AUTHENTICATED).get();
                if (!channel.isActive() || identity == null) {
                    route.channels.remove(channel);
                    continue;
                }
                if (!channel.isWritable()) {
                    maximumBytesBeforeWritable = Math.max(
                            maximumBytesBeforeWritable, closeSlowConsumer(channel, route));
                    slowClosed += 1;
                    continue;
                }
                java.util.Set<ClientCapability> capabilities =
                        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
                boolean mentionsEnabled = capabilities != null && capabilities.contains(
                        ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS);
                boolean forwardingEnabled = capabilities != null && capabilities.contains(
                        ClientCapability.CLIENT_CAPABILITY_MESSAGE_FORWARDING);
                MessageRecord visibleRecord = record.toBuilder()
                        .setForwarded(forwardingEnabled && record.getForwarded())
                        .build();
                if (!mentionsEnabled) visibleRecord = visibleRecord.toBuilder()
                        .clearMentions().build();
                Envelope event = Envelope.newBuilder()
                        .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                        .setKind(MessageKind.MESSAGE_KIND_EVENT)
                        .setMessageType(MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED_VALUE)
                        .setSessionId(identity.sessionId().toString())
                        .setSentAtEpochMs(clock.millis())
                        .setPayload(visibleRecord.toByteString())
                        .build();
                EnvelopePolicy.requireValid(event);
                channel.writeAndFlush(event);
                liveMessageSequences(channel).merge(message.conversationId(),
                        message.conversationSequence(), Math::max);
                published += 1;
            }
            if (route.channels.isEmpty()) routes.remove(message.conversationId(), route);
        }
        return new LivePublishResult(published, slowClosed, maximumBytesBeforeWritable);
    }

    /** Reauthorizes and loads exact server truth for one payload-free Redis hint. */
    public LocalConversationHintResult repairMessageHint(
            GatewayLiveEventHint hint, MessageHistoryPort history) {
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(history, "history");
        Route route = routes.get(hint.conversationId());
        if (route == null) return LocalConversationHintResult.NOT_SUBSCRIBED;
        int applied = 0;
        int duplicates = 0;
        synchronized (route) {
            for (Channel channel : java.util.List.copyOf(route.channels)) {
                AuthenticatedConnection identity =
                        channel.attr(V2ConnectionAttributes.AUTHENTICATED).get();
                if (!channel.isActive() || identity == null) {
                    route.channels.remove(channel); continue;
                }
                long known = liveMessageSequences(channel)
                        .getOrDefault(hint.conversationId(), 0L);
                if (known >= hint.conversationSequence()) {
                    duplicates++; continue;
                }
                MessageHistoryResult result = history.readAfter(new MessageHistoryQuery(
                        hint.conversationId(), identity.accountId(),
                        hint.conversationSequence() - 1, 1));
                if (result == MessageHistoryResult.Rejected.NOT_AUTHORIZED) {
                    route.channels.remove(channel);
                    subscriptions(channel).remove(hint.conversationId());
                    liveMessageSequences(channel).remove(hint.conversationId());
                    continue;
                }
                MessageHistoryResult.Page page = (MessageHistoryResult.Page) result;
                StoredMessage message = page.messages().stream()
                        .filter(value -> value.conversationSequence()
                                == hint.conversationSequence())
                        .findFirst().orElseThrow(() -> new IllegalStateException(
                                "Redis hint has no authoritative message"));
                if (!message.messageId().equals(hint.eventId())) {
                    throw new IllegalStateException("Redis hint event identity conflicts");
                }
                if (publishMessageToChannel(channel, route, message)) applied++;
            }
            if (route.channels.isEmpty()) routes.remove(hint.conversationId(), route);
        }
        if (applied > 0) return LocalConversationHintResult.APPLIED;
        if (duplicates > 0) return LocalConversationHintResult.DUPLICATE;
        return LocalConversationHintResult.NOT_SUBSCRIBED;
    }

    @Override
    public LivePublishResult publishReaction(MessageReactionResult.Applied reaction) {
        Objects.requireNonNull(reaction, "reaction");
        if (!reaction.changed() || reaction.duplicate()) return LivePublishResult.NONE;
        Route route = routes.get(reaction.conversationId());
        if (route == null) return LivePublishResult.NONE;
        MessageReactionChangedRecord record = MessageReactionChangedRecord.newBuilder()
                .setConversationId(reaction.conversationId().toString())
                .setConversationSequence(reaction.conversationSequence())
                .setMessageId(reaction.messageId().toString())
                .setReaction(V2MessagingHandler.protocolReaction(reaction.reaction()))
                .setActive(reaction.active())
                .setActorAccountId(reaction.actorAccountId().toString())
                .setClientOperationId(reaction.clientOperationId())
                .setOccurredAtEpochMs(reaction.occurredAt().toEpochMilli())
                .build();
        MessagingPayloadPolicy.requireValid(record);
        int published = 0;
        int slowClosed = 0;
        long maximumBytesBeforeWritable = 0;
        synchronized (route) {
            for (Channel channel : java.util.List.copyOf(route.channels)) {
                AuthenticatedConnection identity =
                        channel.attr(V2ConnectionAttributes.AUTHENTICATED).get();
                if (!channel.isActive() || identity == null) {
                    route.channels.remove(channel);
                    continue;
                }
                java.util.Set<ClientCapability> capabilities =
                        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
                if (capabilities == null || !capabilities.contains(
                        ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS)) {
                    continue;
                }
                if (!channel.isWritable()) {
                    maximumBytesBeforeWritable = Math.max(
                            maximumBytesBeforeWritable, closeSlowConsumer(channel, route));
                    slowClosed += 1;
                    continue;
                }
                Envelope event = Envelope.newBuilder()
                        .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                        .setKind(MessageKind.MESSAGE_KIND_EVENT)
                        .setMessageType(
                                MessageType.MESSAGE_TYPE_MESSAGE_REACTION_CHANGED_VALUE)
                        .setSessionId(identity.sessionId().toString())
                        .setSentAtEpochMs(clock.millis())
                        .setPayload(record.toByteString())
                        .build();
                EnvelopePolicy.requireValid(event);
                channel.writeAndFlush(event);
                published += 1;
            }
            if (route.channels.isEmpty()) routes.remove(reaction.conversationId(), route);
        }
        return new LivePublishResult(published, slowClosed, maximumBytesBeforeWritable);
    }

    @Override
    public LivePublishResult publishPin(MessagePinResult.Applied pin) {
        Objects.requireNonNull(pin, "pin");
        if (!pin.changed() || pin.duplicate()) return LivePublishResult.NONE;
        Route route = routes.get(pin.conversationId());
        if (route == null) return LivePublishResult.NONE;
        MessagePinChangedRecord record = MessagePinChangedRecord.newBuilder()
                .setConversationId(pin.conversationId().toString())
                .setConversationSequence(pin.conversationSequence())
                .setMessageId(pin.messageId().toString()).setPinned(pin.pinned())
                .setActorAccountId(pin.actorAccountId().toString())
                .setClientOperationId(pin.clientOperationId())
                .setOccurredAtEpochMs(pin.occurredAt().toEpochMilli()).build();
        MessagingPayloadPolicy.requireValid(record);
        int published = 0;
        int slowClosed = 0;
        long maximumBytesBeforeWritable = 0;
        synchronized (route) {
            for (Channel channel : java.util.List.copyOf(route.channels)) {
                AuthenticatedConnection identity =
                        channel.attr(V2ConnectionAttributes.AUTHENTICATED).get();
                if (!channel.isActive() || identity == null) {
                    route.channels.remove(channel);
                    continue;
                }
                java.util.Set<ClientCapability> capabilities =
                        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
                if (capabilities == null || !capabilities.contains(
                        ClientCapability.CLIENT_CAPABILITY_MESSAGE_PINS)) continue;
                if (!channel.isWritable()) {
                    maximumBytesBeforeWritable = Math.max(
                            maximumBytesBeforeWritable, closeSlowConsumer(channel, route));
                    slowClosed += 1; continue;
                }
                Envelope event = Envelope.newBuilder()
                        .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                        .setKind(MessageKind.MESSAGE_KIND_EVENT)
                        .setMessageType(MessageType.MESSAGE_TYPE_MESSAGE_PIN_CHANGED_VALUE)
                        .setSessionId(identity.sessionId().toString())
                        .setSentAtEpochMs(clock.millis()).setPayload(record.toByteString()).build();
                EnvelopePolicy.requireValid(event);
                channel.writeAndFlush(event); published += 1;
            }
            if (route.channels.isEmpty()) routes.remove(pin.conversationId(), route);
        }
        return new LivePublishResult(published, slowClosed, maximumBytesBeforeWritable);
    }

    @Override
    public LivePublishResult publishEdit(MessageEditResult.Applied edit) {
        Objects.requireNonNull(edit, "edit");
        if (!edit.changed() || edit.duplicate()) return LivePublishResult.NONE;
        Route route = routes.get(edit.conversationId());
        if (route == null) return LivePublishResult.NONE;
        MessageEditedRecord record = MessageEditedRecord.newBuilder()
                .setConversationId(edit.conversationId().toString())
                .setConversationSequence(edit.conversationSequence())
                .setMessageId(edit.messageId().toString())
                .setContentRevision(edit.contentRevision())
                .setContentType(edit.contentType())
                .setContent(ByteString.copyFrom(edit.content()))
                .setActorAccountId(edit.actorAccountId().toString())
                .setClientOperationId(edit.clientOperationId())
                .setOccurredAtEpochMs(edit.occurredAt().toEpochMilli())
                .addAllMentions(edit.mentions().stream()
                        .map(V2MessagingHandler::protocolMention).toList()).build();
        MessagingPayloadPolicy.requireValid(record);
        int published = 0;
        int slowClosed = 0;
        long maximumBytesBeforeWritable = 0;
        synchronized (route) {
            for (Channel channel : java.util.List.copyOf(route.channels)) {
                AuthenticatedConnection identity =
                        channel.attr(V2ConnectionAttributes.AUTHENTICATED).get();
                if (!channel.isActive() || identity == null) {
                    route.channels.remove(channel);
                    continue;
                }
                java.util.Set<ClientCapability> capabilities =
                        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
                if (capabilities == null || !capabilities.contains(
                        ClientCapability.CLIENT_CAPABILITY_MESSAGE_EDITS)) continue;
                boolean mentionsEnabled = capabilities.contains(
                        ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS);
                if (!channel.isWritable()) {
                    maximumBytesBeforeWritable = Math.max(
                            maximumBytesBeforeWritable, closeSlowConsumer(channel, route));
                    slowClosed += 1; continue;
                }
                Envelope event = Envelope.newBuilder()
                        .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                        .setKind(MessageKind.MESSAGE_KIND_EVENT)
                        .setMessageType(MessageType.MESSAGE_TYPE_MESSAGE_EDITED_VALUE)
                        .setSessionId(identity.sessionId().toString())
                        .setSentAtEpochMs(clock.millis())
                        .setPayload(mentionsEnabled
                                ? record.toByteString()
                                : record.toBuilder().clearMentions().build().toByteString())
                        .build();
                EnvelopePolicy.requireValid(event);
                channel.writeAndFlush(event); published += 1;
            }
            if (route.channels.isEmpty()) routes.remove(edit.conversationId(), route);
        }
        return new LivePublishResult(published, slowClosed, maximumBytesBeforeWritable);
    }

    @Override
    public void unsubscribe(Channel channel) {
        java.util.Set<UUID> conversationIds = subscriptions(channel);
        for (UUID conversationId : java.util.Set.copyOf(conversationIds)) {
            Route route = routes.get(conversationId);
            if (route == null) continue;
            synchronized (route) {
                route.channels.remove(channel);
                if (route.channels.isEmpty()) routes.remove(conversationId, route);
            }
        }
        conversationIds.clear();
        liveMessageSequences(channel).clear();
    }

    void unsubscribeConversation(Channel channel, UUID conversationId) {
        Route route = routes.get(conversationId);
        if (route != null) {
            synchronized (route) {
                route.channels.remove(channel);
                if (route.channels.isEmpty()) routes.remove(conversationId, route);
            }
        }
        subscriptions(channel).remove(conversationId);
        liveMessageSequences(channel).remove(conversationId);
    }

    java.util.Set<UUID> subscribedConversations(Channel channel) {
        return java.util.Set.copyOf(subscriptions(channel));
    }

    boolean hasSubscribers(UUID conversationId) {
        Route route = routes.get(conversationId);
        if (route == null) return false;
        synchronized (route) { return !route.channels.isEmpty(); }
    }

    java.util.Map<UUID, Long> activeConversationSequences() {
        java.util.Map<UUID, Long> values = new java.util.HashMap<>();
        routes.forEach((conversationId, route) -> {
            synchronized (route) {
                long sequence = route.channels.stream()
                        .filter(Channel::isActive)
                        .mapToLong(channel -> observedSequence(channel, conversationId))
                        .max().orElse(-1);
                if (sequence >= 0) values.put(conversationId, sequence);
            }
        });
        return java.util.Map.copyOf(values);
    }

    long observedSequence(Channel channel, UUID conversationId) {
        return liveMessageSequences(channel).getOrDefault(conversationId, 0L);
    }

    /** Bounded authoritative second repair after an external route becomes visible. */
    long repairAfterRouteRegistration(UUID conversationId, long afterSequence,
            MessageHistoryPort history) {
        Route route = routes.get(conversationId);
        if (route == null) return afterSequence;
        long repairedThrough = afterSequence;
        synchronized (route) {
            for (Channel channel : java.util.List.copyOf(route.channels)) {
                AuthenticatedConnection identity =
                        channel.attr(V2ConnectionAttributes.AUTHENTICATED).get();
                if (!channel.isActive() || identity == null) {
                    unsubscribeConversation(channel, conversationId); continue;
                }
                long cursor = Math.max(afterSequence,
                        observedSequence(channel, conversationId));
                for (int pageCount = 0; pageCount < 10; pageCount++) {
                    MessageHistoryResult result = history.readAfter(new MessageHistoryQuery(
                            conversationId, identity.accountId(), cursor, 100));
                    if (result == MessageHistoryResult.Rejected.NOT_AUTHORIZED) {
                        unsubscribeConversation(channel, conversationId); break;
                    }
                    MessageHistoryResult.Page page = (MessageHistoryResult.Page) result;
                    if (page.nextSequence() < cursor
                            || (page.hasMore() && page.nextSequence() == cursor)) {
                        throw new IllegalStateException("route repair history did not progress");
                    }
                    long previous = cursor;
                    for (StoredMessage message : page.messages()) {
                        if (!message.conversationId().equals(conversationId)
                                || message.conversationSequence() <= previous
                                || message.conversationSequence() > page.nextSequence()) {
                            throw new IllegalStateException("route repair history is unordered");
                        }
                        publishMessageToChannel(channel, route, message);
                        previous = message.conversationSequence();
                    }
                    cursor = page.nextSequence();
                    liveMessageSequences(channel).merge(conversationId, cursor, Math::max);
                    repairedThrough = Math.max(repairedThrough, cursor);
                    if (!page.hasMore()) break;
                    if (pageCount == 9) {
                        throw new IllegalStateException("route repair exceeded bounded pages");
                    }
                }
            }
            if (route.channels.isEmpty()) routes.remove(conversationId, route);
        }
        return repairedThrough;
    }

    int activeConversationCount() {
        return routes.size();
    }

    private static long closeSlowConsumer(Channel channel, Route route) {
        long bytesBeforeWritable = Math.max(0, channel.bytesBeforeWritable());
        channel.close();
        route.channels.remove(channel);
        return bytesBeforeWritable;
    }

    private boolean publishMessageToChannel(
            Channel channel, Route route, StoredMessage message) {
        AuthenticatedConnection identity =
                channel.attr(V2ConnectionAttributes.AUTHENTICATED).get();
        if (!channel.isActive() || identity == null) {
            route.channels.remove(channel); return false;
        }
        if (!channel.isWritable()) {
            closeSlowConsumer(channel, route); return false;
        }
        java.util.Set<ClientCapability> capabilities =
                channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
        boolean mentionsEnabled = capabilities != null && capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS);
        boolean forwardingEnabled = capabilities != null && capabilities.contains(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_FORWARDING);
        MessageRecord visibleRecord = record(message).toBuilder()
                .setForwarded(forwardingEnabled && message.forwarded()).build();
        if (!mentionsEnabled) visibleRecord = visibleRecord.toBuilder().clearMentions().build();
        Envelope event = Envelope.newBuilder()
                .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_EVENT)
                .setMessageType(MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED_VALUE)
                .setSessionId(identity.sessionId().toString())
                .setSentAtEpochMs(clock.millis())
                .setPayload(visibleRecord.toByteString()).build();
        EnvelopePolicy.requireValid(event);
        channel.writeAndFlush(event);
        liveMessageSequences(channel).merge(
                message.conversationId(), message.conversationSequence(), Math::max);
        return true;
    }

    private void registerCleanup(Channel channel) {
        if (channel.attr(CLEANUP_REGISTERED).setIfAbsent(Boolean.TRUE) == null) {
            channel.closeFuture().addListener(ignored -> unsubscribe(channel));
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<UUID> subscriptions(Channel channel) {
        java.util.Set<UUID> subscriptions =
                (java.util.Set<UUID>) channel.attr(ACTIVE_CONVERSATIONS).get();
        if (subscriptions != null) return subscriptions;
        java.util.Set<UUID> created = ConcurrentHashMap.newKeySet();
        java.util.Set<UUID> existing =
                (java.util.Set<UUID>) channel.attr(ACTIVE_CONVERSATIONS).setIfAbsent(created);
        return existing == null ? created : existing;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<UUID, Long> liveMessageSequences(Channel channel) {
        java.util.Map<UUID, Long> sequences =
                (java.util.Map<UUID, Long>) channel.attr(LIVE_MESSAGE_SEQUENCES).get();
        if (sequences != null) return sequences;
        java.util.Map<UUID, Long> created = new ConcurrentHashMap<>();
        java.util.Map<UUID, Long> existing =
                (java.util.Map<UUID, Long>) channel.attr(LIVE_MESSAGE_SEQUENCES)
                        .setIfAbsent(created);
        return existing == null ? created : existing;
    }

    private static MessageRecord record(StoredMessage message) {
        MessageRecord.Builder builder = MessageRecord.newBuilder()
                .setConversationId(message.conversationId().toString())
                .setMessageId(message.messageId().toString())
                .setConversationSequence(message.conversationSequence())
                .setSenderAccountId(message.senderAccountId().toString())
                .setSenderDeviceId(message.senderDeviceId().toString())
                .setClientMessageId(message.clientMessageId())
                .setContentType(message.messageType())
                .setContent(ByteString.copyFrom(message.payload()))
                .setAcceptedAtEpochMs(message.acceptedAt().toEpochMilli())
                .setContentRevision(message.contentRevision())
                .setEditedAtEpochMs(message.editedAt().map(java.time.Instant::toEpochMilli)
                        .orElse(0L))
                .setForwarded(message.forwarded());
        message.reply().ifPresent(reply -> builder.setReply(
                com.fallingnight.chat.protocol.v2.MessageReplyReference.newBuilder()
                        .setTargetMessageId(reply.targetMessageId().toString())
                        .setTargetConversationSequence(reply.targetConversationSequence())
                        .setTargetSenderAccountId(reply.targetSenderAccountId().toString())));
        message.mentions().forEach(mention ->
                builder.addMentions(V2MessagingHandler.protocolMention(mention)));
        MessageRecord record = builder.build();
        MessagingPayloadPolicy.requireValid(record);
        return record;
    }

    private static final class Route {
        private final HashSet<Channel> channels = new HashSet<>();
    }
}

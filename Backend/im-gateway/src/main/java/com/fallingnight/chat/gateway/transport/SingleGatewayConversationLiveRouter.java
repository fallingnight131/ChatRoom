package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.application.messaging.MessageReactionResult;
import com.fallingnight.chat.application.messaging.MessagePinResult;
import com.fallingnight.chat.application.messaging.MessageEditResult;
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

/** Bounded-by-active-channels local router; Redis/multi-gateway routing belongs to M5. */
public final class SingleGatewayConversationLiveRouter implements ConversationLiveRouter {
    private static final AttributeKey<UUID> ACTIVE_CONVERSATION =
            AttributeKey.valueOf("v2.activeConversation");
    private static final AttributeKey<Boolean> CLEANUP_REGISTERED =
            AttributeKey.valueOf("v2.liveCleanupRegistered");

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
        unsubscribe(channel);
        Route route = routes.computeIfAbsent(query.conversationId(), ignored -> new Route());
        synchronized (route) {
            try {
                MessageHistoryResult result = history.readAfter(query);
                if (result instanceof MessageHistoryResult.Page page
                        && !page.hasMore()
                        && channel.isActive()
                        && channel.attr(V2ConnectionAttributes.AUTHENTICATED).get() != null) {
                    route.channels.add(channel);
                    channel.attr(ACTIVE_CONVERSATION).set(query.conversationId());
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
        synchronized (route) {
            for (Channel channel : java.util.List.copyOf(route.channels)) {
                AuthenticatedConnection identity = channel.attr(V2ConnectionAttributes.AUTHENTICATED).get();
                if (!channel.isActive() || identity == null) {
                    route.channels.remove(channel);
                    continue;
                }
                if (!channel.isWritable()) {
                    channel.close();
                    route.channels.remove(channel);
                    slowClosed += 1;
                    continue;
                }
                java.util.Set<ClientCapability> capabilities =
                        channel.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).get();
                boolean mentionsEnabled = capabilities != null && capabilities.contains(
                        ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS);
                Envelope event = Envelope.newBuilder()
                        .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                        .setKind(MessageKind.MESSAGE_KIND_EVENT)
                        .setMessageType(MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED_VALUE)
                        .setSessionId(identity.sessionId().toString())
                        .setSentAtEpochMs(clock.millis())
                        .setPayload(mentionsEnabled
                                ? record.toByteString()
                                : record.toBuilder().clearMentions().build().toByteString())
                        .build();
                EnvelopePolicy.requireValid(event);
                channel.writeAndFlush(event);
                published += 1;
            }
            if (route.channels.isEmpty()) routes.remove(message.conversationId(), route);
        }
        return new LivePublishResult(published, slowClosed);
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
                    channel.close();
                    route.channels.remove(channel);
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
        return new LivePublishResult(published, slowClosed);
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
                    channel.close(); route.channels.remove(channel); slowClosed += 1; continue;
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
        return new LivePublishResult(published, slowClosed);
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
                    channel.close(); route.channels.remove(channel); slowClosed += 1; continue;
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
        return new LivePublishResult(published, slowClosed);
    }

    @Override
    public void unsubscribe(Channel channel) {
        UUID conversationId = channel.attr(ACTIVE_CONVERSATION).getAndSet(null);
        if (conversationId == null) return;
        Route route = routes.get(conversationId);
        if (route == null) return;
        synchronized (route) {
            route.channels.remove(channel);
            if (route.channels.isEmpty()) routes.remove(conversationId, route);
        }
    }

    int activeConversationCount() {
        return routes.size();
    }

    private void registerCleanup(Channel channel) {
        if (channel.attr(CLEANUP_REGISTERED).setIfAbsent(Boolean.TRUE) == null) {
            channel.closeFuture().addListener(ignored -> unsubscribe(channel));
        }
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

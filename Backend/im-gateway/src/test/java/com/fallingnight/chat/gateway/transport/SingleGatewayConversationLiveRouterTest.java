package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import com.fallingnight.chat.application.messaging.MessageEditResult;
import com.fallingnight.chat.application.routing.GatewayLiveEventHint;
import com.fallingnight.chat.application.routing.LocalConversationHintResult;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageRecord;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.MessageEditedRecord;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SingleGatewayConversationLiveRouterTest {
    private static final UUID CONVERSATION = UUID.fromString(
            "50000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_CONVERSATION = UUID.fromString(
            "50000000-0000-4000-8000-000000000002");
    private static final UUID ACCOUNT = UUID.fromString(
            "20000000-0000-4000-8000-000000000001");
    private static final UUID DEVICE = UUID.fromString(
            "30000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString(
            "40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.ofEpochMilli(1_800_000_000_000L);

    @Test
    void publishesSessionBoundEventsOnlyAfterAuthorizedCaughtUpHistory() throws Exception {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel channel = authenticatedChannel();
        try {
            MessageHistoryQuery query = new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100);
            router.readAndSubscribe(channel, query, ignored -> new MessageHistoryResult.Page(
                    List.of(), 0, 0, false));
            assertEquals(1, router.activeConversationCount());
            assertEquals(1, router.publish(message(CONVERSATION, 1)).published());

            Envelope event = channel.readOutbound();
            assertEquals(MessageKind.MESSAGE_KIND_EVENT, event.getKind());
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED_VALUE, event.getMessageType());
            assertEquals(SESSION.toString(), event.getSessionId());
            assertEquals("", event.getRequestId());
            assertEquals("", event.getClientMessageId());
            MessageRecord record = MessageRecord.parseFrom(event.getPayload());
            assertEquals(CONVERSATION.toString(), record.getConversationId());
            assertEquals(1, record.getConversationSequence());

            router.readAndSubscribe(
                    channel,
                    new MessageHistoryQuery(OTHER_CONVERSATION, ACCOUNT, 0, 100),
                    ignored -> MessageHistoryResult.Rejected.NOT_AUTHORIZED);
            assertEquals(1, router.activeConversationCount());
            assertEquals(1, router.publish(message(CONVERSATION, 2)).published());
            assertEquals(2, MessageRecord.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload())
                    .getConversationSequence());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void retainsMultipleAuthorizedCaughtUpConversationSubscriptions() throws Exception {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel channel = authenticatedChannel();
        try {
            for (UUID conversation : List.of(CONVERSATION, OTHER_CONVERSATION)) {
                router.readAndSubscribe(channel,
                        new MessageHistoryQuery(conversation, ACCOUNT, 0, 100),
                        ignored -> new MessageHistoryResult.Page(List.of(), 0, 0, false));
            }
            assertEquals(2, router.activeConversationCount());
            assertEquals(1, router.publish(message(CONVERSATION, 1)).published());
            assertEquals(1, router.publish(message(OTHER_CONVERSATION, 1)).published());
            assertEquals(CONVERSATION.toString(), MessageRecord.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload()).getConversationId());
            assertEquals(OTHER_CONVERSATION.toString(), MessageRecord.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload()).getConversationId());
            channel.close().syncUninterruptibly();
            assertEquals(0, router.activeConversationCount());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsTheOneHundredFirstSubscriptionWithoutRemovingExistingRoutes() {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel channel = authenticatedChannel();
        try {
            for (int index = 1; index <= 100; ++index) {
                UUID conversation = new UUID(0x5000000000004000L, index);
                router.readAndSubscribe(channel,
                        new MessageHistoryQuery(conversation, ACCOUNT, 0, 100),
                        ignored -> new MessageHistoryResult.Page(List.of(), 0, 0, false));
            }
            assertEquals(100, router.activeConversationCount());
            UUID overflow = new UUID(0x5000000000004000L, 101);
            assertThrows(IllegalStateException.class, () -> router.readAndSubscribe(channel,
                    new MessageHistoryQuery(overflow, ACCOUNT, 0, 100),
                    ignored -> new MessageHistoryResult.Page(List.of(), 0, 0, false)));
            assertEquals(100, router.activeConversationCount());
            assertEquals(1, router.publish(message(
                    new UUID(0x5000000000004000L, 1), 1)).published());
            channel.close().syncUninterruptibly();
            assertEquals(0, router.activeConversationCount());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void waitsForTheFinalHistoryPageBeforeSubscribingAndCleansClosedChannels() {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel channel = authenticatedChannel();
        MessageHistoryQuery query = new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100);
        router.readAndSubscribe(channel, query, ignored -> new MessageHistoryResult.Page(
                List.of(), 10, 20, true));
        assertEquals(0, router.publish(message(CONVERSATION, 21)).published());
        router.readAndSubscribe(channel, query, ignored -> new MessageHistoryResult.Page(
                List.of(), 20, 20, false));
        assertEquals(1, router.activeConversationCount());
        channel.close().syncUninterruptibly();
        assertEquals(0, router.activeConversationCount());
        channel.finishAndReleaseAll();
    }

    @Test
    void removesEmptyRouteWhenHistoryAuthorizationFailsUnexpectedly() {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel channel = authenticatedChannel();
        try {
            MessageHistoryQuery query = new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100);
            assertThrows(IllegalStateException.class, () -> router.readAndSubscribe(
                    channel, query, ignored -> { throw new IllegalStateException("database failed"); }));
            assertEquals(0, router.activeConversationCount());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void doesNotRetainAChannelThatClosesDuringHistory() {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel channel = authenticatedChannel();
        MessageHistoryQuery query = new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100);
        router.readAndSubscribe(channel, query, ignored -> {
            channel.close().syncUninterruptibly();
            return new MessageHistoryResult.Page(List.of(), 0, 0, false);
        });
        assertEquals(0, router.activeConversationCount());
        channel.finishAndReleaseAll();
    }

    @Test
    void closesOnlyTheUnwritableSubscriberAndAllowsHistoryRecovery() throws Exception {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel slow = authenticatedChannel();
        EmbeddedChannel healthy = authenticatedChannel();
        MessageHistoryQuery initial = new MessageHistoryQuery(
                CONVERSATION, ACCOUNT, 0, 100);
        router.readAndSubscribe(slow, initial, ignored -> new MessageHistoryResult.Page(
                List.of(), 0, 0, false));
        router.readAndSubscribe(healthy, initial, ignored -> new MessageHistoryResult.Page(
                List.of(), 0, 0, false));
        slow.unsafe().outboundBuffer().setUserDefinedWritability(1, false);

        EmbeddedChannel recovered = null;
        try {
            ConversationLiveRouter.LivePublishResult result =
                    router.publish(message(CONVERSATION, 1));
            assertEquals(1, result.published());
            assertEquals(1, result.slowClosed());
            assertFalse(slow.isActive());
            assertEquals(1, MessageRecord.parseFrom(
                    ((Envelope) healthy.readOutbound()).getPayload())
                    .getConversationSequence());
            assertEquals(1, router.activeConversationCount());

            recovered = authenticatedChannel();
            MessageHistoryResult recovery = router.readAndSubscribe(
                    recovered,
                    new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100),
                    ignored -> new MessageHistoryResult.Page(
                            List.of(message(CONVERSATION, 1)), 1, 1, false));
            MessageHistoryResult.Page page = (MessageHistoryResult.Page) recovery;
            assertEquals(1, page.messages().size());
            assertEquals(1, page.messages().getFirst().conversationSequence());

            assertEquals(2, router.publish(message(CONVERSATION, 2)).published());
            assertEquals(2, MessageRecord.parseFrom(
                    ((Envelope) healthy.readOutbound()).getPayload())
                    .getConversationSequence());
            assertEquals(2, MessageRecord.parseFrom(
                    ((Envelope) recovered.readOutbound()).getPayload())
                    .getConversationSequence());
        } finally {
            slow.finishAndReleaseAll();
            healthy.finishAndReleaseAll();
            if (recovered != null) recovered.finishAndReleaseAll();
        }
    }

    @Test
    void publishesChangedEditsOnlyToCapableSubscribers() throws Exception {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel capable = authenticatedChannel();
        EmbeddedChannel mentionCapable = authenticatedChannel();
        EmbeddedChannel legacy = authenticatedChannel();
        capable.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_EDITS));
        mentionCapable.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_EDITS,
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_MENTIONS));
        MessageHistoryQuery query = new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100);
        router.readAndSubscribe(capable, query, ignored -> new MessageHistoryResult.Page(
                List.of(), 0, 0, false));
        router.readAndSubscribe(legacy, query, ignored -> new MessageHistoryResult.Page(
                List.of(), 0, 0, false));
        router.readAndSubscribe(mentionCapable, query, ignored -> new MessageHistoryResult.Page(
                List.of(), 0, 0, false));
        var mention = new com.fallingnight.chat.application.messaging.MessageMention(
                UUID.randomUUID(), 0, 4);
        MessageEditResult.Applied edit = new MessageEditResult.Applied(
                CONVERSATION, UUID.randomUUID(), ACCOUNT, 1, 1,
                "@李 hi".getBytes(java.nio.charset.StandardCharsets.UTF_8), "edit-live",
                true, 1, NOW, false, List.of(mention));
        try {
            assertEquals(2, router.publishEdit(edit).published());
            Envelope event = capable.readOutbound();
            assertEquals(MessageType.MESSAGE_TYPE_MESSAGE_EDITED_VALUE,
                    event.getMessageType());
            assertEquals(0, MessageEditedRecord.parseFrom(event.getPayload())
                    .getMentionsCount());
            Envelope mentionEvent = mentionCapable.readOutbound();
            assertEquals(1, MessageEditedRecord.parseFrom(mentionEvent.getPayload())
                    .getMentionsCount());
            assertNull(legacy.readOutbound());
            assertEquals(0, router.publishEdit(new MessageEditResult.Applied(
                    edit.conversationId(), edit.messageId(), edit.actorAccountId(), 1, 1,
                    edit.content(), "edit-no-op", false, 0, NOW, false)).published());
        } finally {
            capable.finishAndReleaseAll();
            mentionCapable.finishAndReleaseAll();
            legacy.finishAndReleaseAll();
        }
    }

    @Test
    void filtersForwardMarkerFromLegacyLiveSubscribers() throws Exception {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel capable = authenticatedChannel();
        EmbeddedChannel legacy = authenticatedChannel();
        capable.attr(V2ConnectionAttributes.ENABLED_CAPABILITIES).set(Set.of(
                ClientCapability.CLIENT_CAPABILITY_MESSAGE_FORWARDING));
        MessageHistoryQuery query = new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100);
        router.readAndSubscribe(capable, query, ignored -> new MessageHistoryResult.Page(
                List.of(), 0, 0, false));
        router.readAndSubscribe(legacy, query, ignored -> new MessageHistoryResult.Page(
                List.of(), 0, 0, false));
        StoredMessage forwarded = new StoredMessage(
                UUID.randomUUID(), CONVERSATION, 1, ACCOUNT, DEVICE, "forward-live", 1,
                "copied".getBytes(java.nio.charset.StandardCharsets.UTF_8), NOW,
                java.util.Optional.empty(), 0, java.util.Optional.empty(), List.of(), true);
        try {
            assertEquals(2, router.publish(forwarded).published());
            MessageRecord capableRecord = MessageRecord.parseFrom(
                    ((Envelope) capable.readOutbound()).getPayload());
            MessageRecord legacyRecord = MessageRecord.parseFrom(
                    ((Envelope) legacy.readOutbound()).getPayload());
            assertEquals(true, capableRecord.getForwarded());
            assertEquals(false, legacyRecord.getForwarded());
            assertEquals("copied", legacyRecord.getContent().toStringUtf8());
            assertEquals(capableRecord.getConversationSequence(),
                    legacyRecord.getConversationSequence());
        } finally {
            capable.finishAndReleaseAll();
            legacy.finishAndReleaseAll();
        }
    }

    @Test
    void reauthorizesRedisHintsLoadsServerTruthAndDeduplicatesPerConnection() throws Exception {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel channel = authenticatedChannel();
        MessageHistoryQuery initial = new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100);
        router.readAndSubscribe(channel, initial, ignored ->
                new MessageHistoryResult.Page(List.of(), 0, 0, false));
        StoredMessage authoritative = message(CONVERSATION, 1);
        GatewayLiveEventHint hint = new GatewayLiveEventHint(UUID.randomUUID(),
                authoritative.messageId(), CONVERSATION, 1);
        try {
            assertEquals(LocalConversationHintResult.APPLIED,
                    router.repairMessageHint(hint, query -> {
                        assertEquals(ACCOUNT, query.accountId());
                        assertEquals(0, query.afterSequence());
                        return new MessageHistoryResult.Page(
                                List.of(authoritative), 1, 1, false);
                    }));
            assertEquals(1, MessageRecord.parseFrom(
                    ((Envelope) channel.readOutbound()).getPayload())
                    .getConversationSequence());
            assertEquals(LocalConversationHintResult.DUPLICATE,
                    router.repairMessageHint(hint,
                            query -> { throw new AssertionError("duplicate must not query SQL"); }));
            assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void removesSubscriptionWhenRedisHintReauthorizationIsDenied() {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel channel = authenticatedChannel();
        router.readAndSubscribe(channel,
                new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100), ignored ->
                        new MessageHistoryResult.Page(List.of(), 0, 0, false));
        StoredMessage value = message(CONVERSATION, 1);
        try {
            assertEquals(LocalConversationHintResult.NOT_SUBSCRIBED,
                    router.repairMessageHint(new GatewayLiveEventHint(UUID.randomUUID(),
                            value.messageId(), CONVERSATION, 1), query ->
                            MessageHistoryResult.Rejected.NOT_AUTHORIZED));
            assertEquals(0, router.activeConversationCount());
            assertEquals(0, router.publish(value).published());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void retriesRedisHintWhenAuthoritativeIdentityDoesNotMatch() {
        SingleGatewayConversationLiveRouter router = new SingleGatewayConversationLiveRouter(
                Clock.fixed(NOW, ZoneOffset.UTC));
        EmbeddedChannel channel = authenticatedChannel();
        router.readAndSubscribe(channel,
                new MessageHistoryQuery(CONVERSATION, ACCOUNT, 0, 100), ignored ->
                        new MessageHistoryResult.Page(List.of(), 0, 0, false));
        try {
            assertThrows(IllegalStateException.class, () -> router.repairMessageHint(
                    new GatewayLiveEventHint(UUID.randomUUID(), UUID.randomUUID(),
                            CONVERSATION, 1), query -> new MessageHistoryResult.Page(
                            List.of(message(CONVERSATION, 1)), 1, 1, false)));
            assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    private static EmbeddedChannel authenticatedChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(V2ConnectionAttributes.AUTHENTICATED).set(
                new AuthenticatedConnection(ACCOUNT, DEVICE, SESSION));
        return channel;
    }

    private static StoredMessage message(UUID conversationId, long sequence) {
        return new StoredMessage(
                UUID.fromString("60000000-0000-4000-8000-" + String.format("%012d", sequence)),
                conversationId,
                sequence,
                ACCOUNT,
                DEVICE,
                "client-" + sequence,
                1,
                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                NOW.plusMillis(sequence));
    }
}

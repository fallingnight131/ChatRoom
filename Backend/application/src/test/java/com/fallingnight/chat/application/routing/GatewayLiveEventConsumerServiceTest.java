package com.fallingnight.chat.application.routing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class GatewayLiveEventConsumerServiceTest {
    @Test void advancesOnlyAcrossSuccessfullyClassifiedOrderedHints() {
        UUID gateway = UUID.randomUUID(); UUID conversation = UUID.randomUUID();
        List<GatewayLiveEventStreamEntry> entries = List.of(
                entry(gateway, conversation, "1-0", 1), entry(gateway, conversation, "2-0", 2),
                entry(gateway, conversation, "3-0", 3), entry(gateway, conversation, "4-0", 4));
        AtomicInteger calls = new AtomicInteger();
        var service = new GatewayLiveEventConsumerService(
                (id, after, limit) -> new GatewayLiveEventBatch(after, entries), hint ->
                switch (calls.getAndIncrement()) {
                    case 0 -> LocalConversationHintResult.APPLIED;
                    case 1 -> LocalConversationHintResult.DUPLICATE;
                    case 2 -> LocalConversationHintResult.NOT_SUBSCRIBED;
                    default -> throw new IllegalStateException("postgres unavailable");
                }, gateway, 10);
        assertEquals(new GatewayLiveEventConsumerReport(4, 1, 1, 1, 1, "3-0"),
                service.runOnce("0-0"));
    }

    @Test void rejectsWrongTargetAndOversizedConfiguration() {
        UUID gateway = UUID.randomUUID();
        var service = new GatewayLiveEventConsumerService((id, after, limit) ->
                new GatewayLiveEventBatch(after, List.of(entry(UUID.randomUUID(),
                        UUID.randomUUID(), "1-0", 1))),
                hint -> LocalConversationHintResult.APPLIED, gateway, 1);
        assertThrows(IllegalStateException.class, () -> service.runOnce("0-0"));
        assertThrows(IllegalArgumentException.class, () -> new GatewayLiveEventConsumerService(
                (id, after, limit) -> new GatewayLiveEventBatch(after, List.of()),
                hint -> LocalConversationHintResult.APPLIED, gateway, 1001));
        UUID conversation = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new GatewayLiveEventBatch("2-0",
                List.of(entry(gateway, conversation, "1-0", 1))));
        assertThrows(IllegalArgumentException.class, () -> new GatewayLiveEventBatch("0-0",
                List.of(entry(gateway, conversation, "2-0", 2),
                        entry(gateway, conversation, "1-0", 1))));
    }

    private static GatewayLiveEventStreamEntry entry(
            UUID gateway, UUID conversation, String stream, long sequence) {
        return new GatewayLiveEventStreamEntry(stream, new GatewayLiveEventHint(
                gateway, UUID.randomUUID(), conversation, sequence));
    }
}

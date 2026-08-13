package com.fallingnight.chat.application.routing;

import java.util.Objects;
import java.util.UUID;

/** One bounded ordered hint-read and local-authoritative-repair pass. */
public final class GatewayLiveEventConsumerService {
    public static final int MAX_BATCH_SIZE = 1_000;
    private final GatewayLiveEventConsumePort events;
    private final LocalConversationHintRepairPort repair;
    private final UUID gatewayId;
    private final int batchSize;

    public GatewayLiveEventConsumerService(GatewayLiveEventConsumePort events,
            LocalConversationHintRepairPort repair, UUID gatewayId, int batchSize) {
        this.events = Objects.requireNonNull(events, "events");
        this.repair = Objects.requireNonNull(repair, "repair");
        this.gatewayId = Objects.requireNonNull(gatewayId, "gatewayId");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be in 1..1000");
        }
        this.batchSize = batchSize;
    }

    public GatewayLiveEventConsumerReport runOnce(String afterStreamId) {
        GatewayLiveEventBatch batch = events.readAfter(gatewayId,
                Objects.requireNonNull(afterStreamId, "afterStreamId"), batchSize);
        if (!batch.requestedAfterStreamId().equals(afterStreamId)
                || batch.entries().size() > batchSize
                || batch.entries().stream().anyMatch(entry ->
                    !entry.hint().targetGatewayId().equals(gatewayId))) {
            throw new IllegalStateException("gateway event port returned invalid batch");
        }
        int applied = 0, duplicates = 0, notSubscribed = 0, failed = 0, read = 0;
        String cursor = afterStreamId;
        for (GatewayLiveEventStreamEntry entry : batch.entries()) {
            read++;
            LocalConversationHintResult result;
            try {
                result = Objects.requireNonNull(repair.repair(entry.hint()), "repair result");
            } catch (RuntimeException exception) {
                failed = 1;
                break;
            }
            switch (result) {
                case APPLIED -> applied++;
                case DUPLICATE -> duplicates++;
                case NOT_SUBSCRIBED -> notSubscribed++;
            }
            cursor = entry.streamId();
        }
        return new GatewayLiveEventConsumerReport(
                read, applied, duplicates, notSubscribed, failed, cursor);
    }
}

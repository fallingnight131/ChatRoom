package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.routing.GatewayLiveEventHint;
import com.fallingnight.chat.application.routing.LocalConversationHintRepairPort;
import com.fallingnight.chat.application.routing.LocalConversationHintResult;
import java.util.Objects;

/** Netty-local subscription plus PostgreSQL-authoritative message hint repair. */
public final class LocalConversationMessageHintRepairAdapter
        implements LocalConversationHintRepairPort {
    private final SingleGatewayConversationLiveRouter router;
    private final MessageHistoryPort history;

    public LocalConversationMessageHintRepairAdapter(
            SingleGatewayConversationLiveRouter router, MessageHistoryPort history) {
        this.router = Objects.requireNonNull(router, "router");
        this.history = Objects.requireNonNull(history, "history");
    }

    @Override public LocalConversationHintResult repair(GatewayLiveEventHint hint) {
        return router.repairMessageHint(hint, history);
    }
}

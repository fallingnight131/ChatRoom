package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.StoredMessage;
import io.netty.channel.Channel;

/** Single-gateway active-conversation subscription and durable event publication boundary. */
public interface ConversationLiveRouter {
    MessageHistoryResult readAndSubscribe(
            Channel channel, MessageHistoryQuery query, MessageHistoryPort history);

    LivePublishResult publish(StoredMessage message);

    void unsubscribe(Channel channel);

    static ConversationLiveRouter noop() {
        return new ConversationLiveRouter() {
            @Override
            public MessageHistoryResult readAndSubscribe(
                    Channel channel, MessageHistoryQuery query, MessageHistoryPort history) {
                return history.readAfter(query);
            }

            @Override
            public LivePublishResult publish(StoredMessage message) {
                return LivePublishResult.NONE;
            }

            @Override
            public void unsubscribe(Channel channel) {}
        };
    }

    record LivePublishResult(int published, int slowClosed) {
        static final LivePublishResult NONE = new LivePublishResult(0, 0);

        public LivePublishResult {
            if (published < 0 || slowClosed < 0) {
                throw new IllegalArgumentException("live publication counts must not be negative");
            }
        }
    }
}

package com.fallingnight.chat.application.compatibility.v1;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface LegacyV1RoomHistoryResult {
    record Page(long legacyRoomId, boolean sequenceMode,
            List<LegacyV1RoomHistoryMessage> messages,
            List<LegacyV1RoomHistoryDeletion> events,
            long nextSequence, long lastSequence, boolean hasMore)
            implements LegacyV1RoomHistoryResult {
        public Page {
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || nextSequence < 0 || lastSequence < 0 || nextSequence > lastSequence) {
                throw new IllegalArgumentException("room history page identity");
            }
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            validateUniqueIds(messages, events);
            if (sequenceMode) validateSequencePage(messages, events, nextSequence,
                    lastSequence, hasMore);
            else if (!events.isEmpty() || hasMore || nextSequence != lastSequence) {
                throw new IllegalArgumentException("latest room history continuation metadata");
            } else validateLatestMessages(messages, lastSequence);
        }

        private static void validateUniqueIds(List<LegacyV1RoomHistoryMessage> messages,
                List<LegacyV1RoomHistoryDeletion> events) {
            Set<Long> messageIds = new HashSet<>(), eventIds = new HashSet<>();
            if (messages.stream().anyMatch(message -> !messageIds.add(message.legacyMessageId()))
                    || events.stream().anyMatch(event -> !eventIds.add(event.legacyEventId()))) {
                throw new IllegalArgumentException("duplicate room history identity");
            }
        }

        private static void validateSequencePage(List<LegacyV1RoomHistoryMessage> messages,
                List<LegacyV1RoomHistoryDeletion> events, long next, long last, boolean hasMore) {
            requireAscending(messages.stream().map(LegacyV1RoomHistoryMessage::syncSequence).toList());
            requireAscending(events.stream().map(LegacyV1RoomHistoryDeletion::sequence).toList());
            List<Long> order = new ArrayList<>(messages.size() + events.size());
            messages.forEach(message -> order.add(message.syncSequence()));
            events.forEach(event -> order.add(event.sequence()));
            order.sort(Comparator.naturalOrder());
            long previous = -1;
            for (long sequence : order) {
                if (sequence <= previous || sequence > last || sequence > next) {
                    throw new IllegalArgumentException("unordered room history page");
                }
                previous = sequence;
            }
            if (hasMore && order.isEmpty()) {
                throw new IllegalArgumentException("empty continued room history page");
            }
            if (hasMore && order.getLast() != next || !hasMore && next != last) {
                throw new IllegalArgumentException("invalid room history continuation cursor");
            }
        }

        private static void validateLatestMessages(
                List<LegacyV1RoomHistoryMessage> messages, long last) {
            long previous = -1;
            for (LegacyV1RoomHistoryMessage message : messages) {
                if (message.sequence() <= previous || message.sequence() > last) {
                    throw new IllegalArgumentException("unordered latest room history page");
                }
                previous = message.sequence();
            }
        }

        private static void requireAscending(List<Long> sequences) {
            long previous = -1;
            for (long sequence : sequences) {
                if (sequence <= previous) {
                    throw new IllegalArgumentException("unordered room history projection");
                }
                previous = sequence;
            }
        }
    }

    enum Rejected implements LegacyV1RoomHistoryResult {
        ROOM_ACCESS_DENIED,
        INVALID_SEQUENCE_CURSOR,
        INVALID_REQUEST
    }
}

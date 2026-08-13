package com.fallingnight.chat.application.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One stable ascending page of active participants. */
public record ConversationParticipantPage(
        UUID conversationId,
        List<ConversationParticipant> participants,
        Optional<UUID> nextAccountId,
        boolean hasMore) {
    public ConversationParticipantPage {
        Objects.requireNonNull(conversationId, "conversationId");
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        nextAccountId = Objects.requireNonNull(nextAccountId, "nextAccountId");
        if (participants.size() > ConversationParticipantQuery.MAX_LIMIT
                || (hasMore && participants.isEmpty())) {
            throw new IllegalArgumentException("participant page bounds are invalid");
        }
        UUID previous = null;
        for (ConversationParticipant participant : participants) {
            if (previous != null && previous.compareTo(participant.accountId()) >= 0) {
                throw new IllegalArgumentException("participants must be strictly ordered");
            }
            previous = participant.accountId();
        }
        Optional<UUID> expected = previous == null ? Optional.empty() : Optional.of(previous);
        if (!nextAccountId.equals(expected)) {
            throw new IllegalArgumentException("next cursor must identify the last participant");
        }
    }
}

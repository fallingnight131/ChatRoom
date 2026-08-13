package com.fallingnight.chat.application.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ConversationEventRelayServiceTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void publishesDefersAndClassifiesUnexpectedFailureWithBoundedBackoff() {
        StubOutbox outbox = new StubOutbox(List.of(claim(1, 1), claim(2, 2), claim(3, 20)));
        var service = service(outbox, claim -> switch ((int) claim.conversationSequence()) {
            case 1 -> ConversationEventPublicationOutcome.PUBLISHED;
            case 2 -> ConversationEventPublicationOutcome.DEPENDENCY_REJECTED;
            default -> throw new IllegalStateException("provider detail must not escape");
        });

        assertEquals(new ConversationEventRelayReport(3, 1, 2, 0, 1), service.runOnce());
        assertEquals(List.of(1L), outbox.published.stream()
                .map(ConversationEventOutboxClaim::conversationSequence).toList());
        assertEquals("DEPENDENCY_REJECTED", outbox.deferred.get(0).failureCode());
        assertEquals(NOW.plusMillis(200), outbox.deferred.get(0).retryAt());
        assertEquals("PUBLISHER_FAILURE", outbox.deferred.get(1).failureCode());
        assertEquals(NOW.plusSeconds(5), outbox.deferred.get(1).retryAt());
    }

    @Test
    void countsFencedCompletionAsOwnershipLossAndRejectsMalformedPortBatch() {
        StubOutbox lost = new StubOutbox(List.of(claim(1, 1)));
        lost.publishAccepted = false;
        assertEquals(new ConversationEventRelayReport(1, 0, 0, 1, 0),
                service(lost, claim -> ConversationEventPublicationOutcome.PUBLISHED).runOnce());

        ConversationEventOutboxClaim repeated = claim(1, 1);
        StubOutbox duplicate = new StubOutbox(List.of(repeated, repeated));
        assertThrows(IllegalStateException.class,
                () -> service(duplicate,
                        claim -> ConversationEventPublicationOutcome.PUBLISHED).runOnce());
    }

    @Test
    void validatesReviewedOperationalBounds() {
        StubOutbox outbox = new StubOutbox(List.of());
        assertThrows(IllegalArgumentException.class, () -> new ConversationEventRelayService(
                outbox, claim -> ConversationEventPublicationOutcome.PUBLISHED, OWNER,
                Duration.ofMillis(999), 10, Duration.ofMillis(100),
                Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC)));
        assertThrows(IllegalArgumentException.class, () -> new ConversationEventRelayService(
                outbox, claim -> ConversationEventPublicationOutcome.PUBLISHED, OWNER,
                Duration.ofSeconds(5), 101, Duration.ofMillis(100),
                Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static ConversationEventRelayService service(
            StubOutbox outbox, ConversationEventPublicationPort publisher) {
        return new ConversationEventRelayService(outbox, publisher, OWNER,
                Duration.ofSeconds(5), 10, Duration.ofMillis(100),
                Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ConversationEventOutboxClaim claim(long sequence, int attempt) {
        return new ConversationEventOutboxClaim(UUID.randomUUID(), UUID.randomUUID(), sequence,
                UUID.randomUUID(), OWNER, NOW, NOW.plusSeconds(5), attempt);
    }

    private static final class StubOutbox implements ConversationEventOutboxPort {
        private final List<ConversationEventOutboxClaim> claims;
        private final List<ConversationEventOutboxClaim> published = new ArrayList<>();
        private final List<Deferred> deferred = new ArrayList<>();
        private boolean publishAccepted = true;

        private StubOutbox(List<ConversationEventOutboxClaim> claims) {
            this.claims = claims;
        }

        @Override
        public List<ConversationEventOutboxClaim> claim(
                UUID owner, Instant claimedAt, Duration lease, int limit) {
            assertEquals(OWNER, owner);
            assertEquals(NOW, claimedAt);
            assertEquals(Duration.ofSeconds(5), lease);
            assertEquals(10, limit);
            return claims;
        }

        @Override
        public boolean markPublished(ConversationEventOutboxClaim claim, Instant publishedAt) {
            assertEquals(NOW, publishedAt);
            published.add(claim);
            return publishAccepted;
        }

        @Override
        public boolean defer(ConversationEventOutboxClaim claim, Instant failedAt,
                Instant retryAt, String failureCode) {
            assertEquals(NOW, failedAt);
            deferred.add(new Deferred(claim, retryAt, failureCode));
            return true;
        }
    }

    private record Deferred(
            ConversationEventOutboxClaim claim, Instant retryAt, String failureCode) { }
}

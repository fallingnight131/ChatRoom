package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacyV1AccountIdentityTest {
    @Test
    void requiresPositiveLegacyIdAndCanonicalAccountId() {
        UUID accountId = UUID.randomUUID();
        assertEquals(accountId, new LegacyV1AccountIdentity(7, accountId).accountId());
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1AccountIdentity(0, accountId));
        assertThrows(NullPointerException.class,
                () -> new LegacyV1AccountIdentity(7, null));
    }
}

package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryAuthenticationAdmissionControlTest {
    @Test
    void limitsNormalizedAccountAcrossPeersAndClearsItAfterSuccess() {
        MutableClock clock = new MutableClock(1_000);
        InMemoryAuthenticationAdmissionControl control = control(clock, 100, 100, 1, 16);

        assertTrue(control.acquire("192.0.2.1", " Alice ").allowed());
        AuthenticationAdmissionDecision denied = control.acquire("192.0.2.2", "alice");
        assertFalse(denied.allowed());
        assertEquals(AuthenticationLimitDimension.ACCOUNT, denied.dimension());
        assertEquals(60_000, denied.retryAfterMs());

        control.recordSuccess("ALICE");
        assertTrue(control.acquire("192.0.2.2", "alice").allowed());
    }

    @Test
    void limitsDirectPeerAndGatewayBeforeExpensiveWork() {
        MutableClock clock = new MutableClock(1_000);
        InMemoryAuthenticationAdmissionControl peerControl = control(clock, 100, 2, 100, 16);
        assertTrue(peerControl.acquire("2001:db8::1", "a").allowed());
        assertTrue(peerControl.acquire("2001:0db8:0:0:0:0:0:1", "b").allowed());
        AuthenticationAdmissionDecision peerDenied = peerControl.acquire("2001:db8::1", "c");
        assertEquals(AuthenticationLimitDimension.DIRECT_PEER, peerDenied.dimension());

        InMemoryAuthenticationAdmissionControl gatewayControl = control(
                clock, 2, 100, 100, 16);
        assertTrue(gatewayControl.acquire("192.0.2.1", "a").allowed());
        assertTrue(gatewayControl.acquire("192.0.2.2", "b").allowed());
        AuthenticationAdmissionDecision gatewayDenied = gatewayControl.acquire(
                "192.0.2.3", "c");
        assertEquals(AuthenticationLimitDimension.GATEWAY, gatewayDenied.dimension());
    }

    @Test
    void boundsKeyMemoryFailsClosedAndRecoversAfterWindow() {
        MutableClock clock = new MutableClock(1_000);
        InMemoryAuthenticationAdmissionControl control = control(clock, 100, 100, 100, 16);
        for (int index = 1; index <= 16; index++) {
            assertTrue(control.acquire("192.0.2." + index, "user-" + index).allowed());
        }
        AuthenticationAdmissionDecision capacity = control.acquire(
                "198.51.100.1", "overflow");
        assertEquals(AuthenticationLimitDimension.DIRECT_PEER_CAPACITY, capacity.dimension());

        AuthenticationAdmissionSnapshot snapshot = control.snapshot();
        assertEquals(16, snapshot.allowedAttempts());
        assertEquals(1, snapshot.deniedAttempts());
        assertEquals(1, snapshot.capacityDenials());
        assertEquals(16, snapshot.activeDirectPeerKeys());
        assertEquals(16, snapshot.activeAccountKeys());

        clock.advance(Duration.ofSeconds(60));
        assertTrue(control.acquire("198.51.100.1", "overflow").allowed());
    }

    private static InMemoryAuthenticationAdmissionControl control(
            Clock clock,
            int gatewayAttempts,
            int peerAttempts,
            int accountAttempts,
            int maxKeys) {
        return new InMemoryAuthenticationAdmissionControl(
                new AuthenticationAdmissionLimits(
                        Duration.ofSeconds(60),
                        gatewayAttempts,
                        peerAttempts,
                        accountAttempts,
                        maxKeys),
                clock);
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        private void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}

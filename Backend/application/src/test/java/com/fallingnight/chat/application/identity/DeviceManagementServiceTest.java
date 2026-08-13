package com.fallingnight.chat.application.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DeviceManagementServiceTest {
    @Test void rejectsCurrentDeviceBeforePersistenceAndDelegatesOtherTargets() {
        UUID account = UUID.randomUUID(), current = UUID.randomUUID();
        UUID session = UUID.randomUUID(), other = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        DeviceManagementPort port = new DeviceManagementPort() {
            @Override public DeviceDirectoryResult listActive(AuthenticatedDeviceActor actor) {
                throw new UnsupportedOperationException();
            }
            @Override public DeviceRevocationResult revokeOther(
                    AuthenticatedDeviceActor actor, UUID targetDeviceId) {
                calls.incrementAndGet(); return DeviceRevocationResult.Rejected.INSTANCE;
            }
        };
        var service = new DeviceManagementService(port);
        var actor = new AuthenticatedDeviceActor(account, current, session);
        assertEquals(DeviceRevocationResult.Rejected.INSTANCE,
                service.revokeOther(actor, current));
        assertEquals(0, calls.get());
        assertEquals(DeviceRevocationResult.Rejected.INSTANCE,
                service.revokeOther(actor, other));
        assertEquals(1, calls.get());
    }
}

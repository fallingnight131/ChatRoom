package com.fallingnight.chat.application.identity;

import java.util.Objects;
import java.util.UUID;

/** Transport-independent policy; persistence rechecks all durable authority. */
public final class DeviceManagementService {
    private final DeviceManagementPort port;
    public DeviceManagementService(DeviceManagementPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }
    public DeviceDirectoryResult listActive(AuthenticatedDeviceActor actor) {
        return port.listActive(Objects.requireNonNull(actor, "actor"));
    }
    public DeviceRevocationResult revokeOther(
            AuthenticatedDeviceActor actor, UUID targetDeviceId) {
        Objects.requireNonNull(actor, "actor"); Objects.requireNonNull(targetDeviceId, "target");
        if (actor.deviceId().equals(targetDeviceId))
            return DeviceRevocationResult.Rejected.INSTANCE;
        return port.revokeOther(actor, targetDeviceId);
    }
}

package com.fallingnight.chat.application.identity;

import java.util.UUID;

public interface DeviceManagementPort {
    DeviceDirectoryResult listActive(AuthenticatedDeviceActor actor);
    DeviceRevocationResult revokeOther(AuthenticatedDeviceActor actor, UUID targetDeviceId);
}

package com.fallingnight.chat.protocol.v2;

import java.util.HashSet;
import java.util.UUID;

/** Structural and bounded validation for the authenticated device-management wire contract. */
public final class DeviceManagementPayloadPolicy {
    public static final int MAX_DEVICES = 100;

    private DeviceManagementPayloadPolicy() { }

    public static void requireValid(ListDevices command) {
        if (!command.equals(ListDevices.getDefaultInstance())) {
            throw new IllegalArgumentException("list devices must be empty");
        }
    }

    public static void requireValid(RevokeDevice command) {
        requireUuid(command.getTargetDeviceId(), "targetDeviceId");
    }

    public static void requireValid(DeviceSummary device) {
        requireUuid(device.getDeviceId(), "deviceId");
        if (device.getPlatform() != ClientPlatform.CLIENT_PLATFORM_WEB
                && device.getPlatform() != ClientPlatform.CLIENT_PLATFORM_WINDOWS) {
            throw new IllegalArgumentException("unsupported device platform");
        }
        if (device.getCreatedAtEpochMs() <= 0
                || device.getLastSeenAtEpochMs() < device.getCreatedAtEpochMs()) {
            throw new IllegalArgumentException("invalid device timestamps");
        }
    }

    public static void requireValid(DeviceDirectory directory) {
        if (directory.getDevicesCount() < 1 || directory.getDevicesCount() > MAX_DEVICES) {
            throw new IllegalArgumentException("invalid device count");
        }
        var ids = new HashSet<String>();
        int current = 0;
        for (DeviceSummary device : directory.getDevicesList()) {
            requireValid(device);
            if (!ids.add(device.getDeviceId())) {
                throw new IllegalArgumentException("duplicate device");
            }
            if (device.getCurrent()) {
                current++;
            }
        }
        if (current != 1) {
            throw new IllegalArgumentException("directory requires one current device");
        }
    }

    public static void requireValid(DeviceRevoked response) {
        requireUuid(response.getTargetDeviceId(), "targetDeviceId");
        if (response.getRevokedAtEpochMs() <= 0) {
            throw new IllegalArgumentException("invalid revokedAt");
        }
    }

    private static void requireUuid(String value, String name) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException(name + " is not canonical");
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " is not a UUID", exception);
        }
    }
}

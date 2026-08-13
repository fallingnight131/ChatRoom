package com.fallingnight.chat.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class DeviceManagementProtocolTest {
    private static final String DEVICE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String REVOKE_GOLDEN =
            "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031";

    @Test
    void revokeHasStableWireBytesAndPermanentRegistryKinds() throws Exception {
        RevokeDevice revoke = RevokeDevice.newBuilder().setTargetDeviceId(DEVICE_ID).build();
        DeviceManagementPayloadPolicy.requireValid(revoke);
        assertEquals(REVOKE_GOLDEN, HexFormat.of().formatHex(revoke.toByteArray()));
        assertEquals(revoke, RevokeDevice.parseFrom(HexFormat.of().parseHex(REVOKE_GOLDEN)));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_LIST_DEVICES));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_DEVICE_DIRECTORY));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_REVOKE_DEVICE));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_DEVICE_REVOKED));
    }

    @Test
    void validatesBoundedDirectoryAndResponse() {
        DeviceSummary current = DeviceSummary.newBuilder().setDeviceId(DEVICE_ID)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_WEB)
                .setCreatedAtEpochMs(1).setLastSeenAtEpochMs(2).setCurrent(true).build();
        DeviceManagementPayloadPolicy.requireValid(DeviceDirectory.newBuilder()
                .addDevices(current).build());
        DeviceManagementPayloadPolicy.requireValid(DeviceRevoked.newBuilder()
                .setTargetDeviceId(DEVICE_ID).setRevokedAtEpochMs(3)
                .setRevokedSessions(2).setChanged(true).build());
    }

    @Test
    void rejectsMalformedTargetsAndAmbiguousDirectories() {
        assertThrows(IllegalArgumentException.class,
                () -> DeviceManagementPayloadPolicy.requireValid(RevokeDevice.newBuilder()
                        .setTargetDeviceId("not-a-device").build()));
        DeviceSummary current = DeviceSummary.newBuilder().setDeviceId(DEVICE_ID)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_WEB)
                .setCreatedAtEpochMs(1).setLastSeenAtEpochMs(2).setCurrent(true).build();
        assertThrows(IllegalArgumentException.class,
                () -> DeviceManagementPayloadPolicy.requireValid(DeviceDirectory.newBuilder()
                        .addDevices(current).addDevices(current).build()));
    }
}

package com.fallingnight.chat.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class AccountBlockingProtocolTest {
    private static final String REQUEST_GOLDEN =
            "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
                    + "10011a2430303030303030302d303030302d303030302d303030302d303030303030303030303032";

    @Test
    void keepsPermanentTypesCapabilityAndRequestWireBytes() throws Exception {
        SetAccountBlock request = SetAccountBlock.newBuilder()
                .setTargetAccountId("00000000-0000-0000-0000-000000000001")
                .setBlocked(true)
                .setClientOperationId("00000000-0000-0000-0000-000000000002")
                .build();
        assertEquals(128, MessageType.MESSAGE_TYPE_SET_ACCOUNT_BLOCK_VALUE);
        assertEquals(129, MessageType.MESSAGE_TYPE_ACCOUNT_BLOCK_APPLIED_VALUE);
        assertEquals(7, ClientCapability.CLIENT_CAPABILITY_ACCOUNT_BLOCKING_VALUE);
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_SET_ACCOUNT_BLOCK));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_ACCOUNT_BLOCK_APPLIED));
        assertEquals(REQUEST_GOLDEN, HexFormat.of().formatHex(request.toByteArray()));
        assertEquals(request,
                SetAccountBlock.parseFrom(HexFormat.of().parseHex(REQUEST_GOLDEN)));
    }

    @Test
    void responseCarriesOnlyCorrelatedStableAccountState() {
        AccountBlockApplied applied = AccountBlockApplied.newBuilder()
                .setActorAccountId("00000000-0000-0000-0000-000000000003")
                .setTargetAccountId("00000000-0000-0000-0000-000000000001")
                .setBlocked(true)
                .setChanged(false)
                .setClientOperationId("00000000-0000-0000-0000-000000000002")
                .build();
        assertEquals("00000000-0000-0000-0000-000000000003", applied.getActorAccountId());
        assertEquals("00000000-0000-0000-0000-000000000001", applied.getTargetAccountId());
        assertEquals("00000000-0000-0000-0000-000000000002",
                applied.getClientOperationId());
    }
}

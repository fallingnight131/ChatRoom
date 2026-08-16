package com.fallingnight.chat.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class AccountBlockingProtocolTest {
    private static final String TARGET_ONE = "00000000-0000-0000-0000-000000000001";
    private static final String TARGET_TWO = "00000000-0000-0000-0000-000000000002";
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
        ContactPayloadPolicy.requireValid(request);
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
        ContactPayloadPolicy.requireValid(applied);
        assertEquals("00000000-0000-0000-0000-000000000003", applied.getActorAccountId());
        assertEquals("00000000-0000-0000-0000-000000000001", applied.getTargetAccountId());
        assertEquals("00000000-0000-0000-0000-000000000002",
                applied.getClientOperationId());
        assertThrows(IllegalArgumentException.class, () -> ContactPayloadPolicy.requireValid(
                SetAccountBlock.newBuilder().setTargetAccountId("not-a-uuid")
                        .setClientOperationId(
                                "00000000-0000-0000-0000-000000000002").build()));
        assertThrows(IllegalArgumentException.class, () -> ContactPayloadPolicy.requireValid(
                applied.toBuilder().setTargetAccountId(applied.getActorAccountId()).build()));
    }

    @Test
    void listContractIsServerBoundBoundedAndPermanentlyRegistered() throws Exception {
        ListAccountBlocks request = ListAccountBlocks.newBuilder()
                .setAfterTargetAccountId(TARGET_ONE).setLimit(25).build();
        ContactPayloadPolicy.requireValid(request);
        assertEquals(134, MessageType.MESSAGE_TYPE_LIST_ACCOUNT_BLOCKS_VALUE);
        assertEquals(135, MessageType.MESSAGE_TYPE_ACCOUNT_BLOCK_DIRECTORY_PAGE_VALUE);
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_LIST_ACCOUNT_BLOCKS));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE, MessageTypeRegistry.requiredKind(
                MessageType.MESSAGE_TYPE_ACCOUNT_BLOCK_DIRECTORY_PAGE));
        String golden =
                "0a2430303030303030302d303030302d303030302d303030302d3030303030303030303030311019";
        assertEquals(golden, HexFormat.of().formatHex(request.toByteArray()));
        assertEquals(request, ListAccountBlocks.parseFrom(HexFormat.of().parseHex(golden)));
    }

    @Test
    void validatesOrderedBoundedDirectoryAndContinuation() {
        AccountBlockSummary first = AccountBlockSummary.newBuilder()
                .setTargetAccountId(TARGET_ONE).setTargetDisplayName("甲")
                .setBlockedAtEpochMs(1).build();
        AccountBlockSummary second = AccountBlockSummary.newBuilder()
                .setTargetAccountId(TARGET_TWO).setTargetDisplayName("乙")
                .setBlockedAtEpochMs(2).build();
        ContactPayloadPolicy.requireValid(AccountBlockDirectoryPage.newBuilder()
                .addBlocks(first).addBlocks(second)
                .setNextAfterTargetAccountId(TARGET_TWO).setHasMore(true).build());
        ContactPayloadPolicy.requireValid(AccountBlockDirectoryPage.getDefaultInstance());
        assertThrows(IllegalArgumentException.class,
                () -> ContactPayloadPolicy.requireValid(ListAccountBlocks.newBuilder().build()));
        assertThrows(IllegalArgumentException.class,
                () -> ContactPayloadPolicy.requireValid(ListAccountBlocks.newBuilder()
                        .setLimit(101).build()));
        assertThrows(IllegalArgumentException.class,
                () -> ContactPayloadPolicy.requireValid(AccountBlockDirectoryPage.newBuilder()
                        .addBlocks(second).addBlocks(first).build()));
        assertThrows(IllegalArgumentException.class,
                () -> ContactPayloadPolicy.requireValid(AccountBlockDirectoryPage.newBuilder()
                        .addBlocks(first).setHasMore(true)
                        .setNextAfterTargetAccountId(TARGET_TWO).build()));
        String oversized = "界".repeat(134);
        assertThrows(IllegalArgumentException.class,
                () -> ContactPayloadPolicy.requireValid(first.toBuilder()
                        .setTargetDisplayName(oversized).build()));
        assertThrows(IllegalArgumentException.class,
                () -> ContactPayloadPolicy.requireValid(first.toBuilder()
                        .setTargetDisplayName("   ").build()));
        assertThrows(IllegalArgumentException.class,
                () -> ContactPayloadPolicy.requireValid(first.toBuilder()
                        .setTargetDisplayName("\ud800").build()));
    }
}

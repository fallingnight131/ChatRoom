package com.fallingnight.chat.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class AttachmentProtocolTest {
    private static final String CONVERSATION_ID =
            "00000000-0000-0000-0000-000000000001";
    private static final String ATTACHMENT_ID =
            "00000000-0000-0000-0000-000000000002";
    private static final String REGISTER_GOLDEN =
            "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
                    + "12086174746163682d311a05612e747874220a746578742f706c61696e28023220"
                    + "0101010101010101010101010101010101010101010101010101010101010101";

    @Test
    void registrationHasStableWireBytesAndPermanentRegistryKinds() throws Exception {
        RegisterAttachment registration = registration();
        AttachmentPayloadPolicy.requireValid(registration);
        assertEquals(REGISTER_GOLDEN,
                HexFormat.of().formatHex(registration.toByteArray()));
        assertEquals(registration, RegisterAttachment.parseFrom(
                HexFormat.of().parseHex(REGISTER_GOLDEN)));

        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_REGISTER_ATTACHMENT));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_ATTACHMENT_REGISTERED));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(
                        MessageType.MESSAGE_TYPE_AUTHORIZE_ATTACHMENT_UPLOAD));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(
                        MessageType.MESSAGE_TYPE_ATTACHMENT_UPLOAD_AUTHORIZED));
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(
                        MessageType.MESSAGE_TYPE_COMPLETE_ATTACHMENT_UPLOAD));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_ATTACHMENT_READY));
    }

    @Test
    void validatesEveryCommandAndResponseWithoutPersistingGrantMaterial() {
        AttachmentPayloadPolicy.requireValid(AttachmentRegistered.newBuilder()
                .setAttachmentId(ATTACHMENT_ID)
                .setConversationId(CONVERSATION_ID)
                .setClientAttachmentId("attach-1")
                .build());
        AttachmentPayloadPolicy.requireValid(AuthorizeAttachmentUpload.newBuilder()
                .setAttachmentId(ATTACHMENT_ID).build());
        AttachmentPayloadPolicy.requireValid(AttachmentUploadAuthorized.newBuilder()
                .setAttachmentId(ATTACHMENT_ID)
                .setUploadUri("https://objects.example.test/key?signature=secret")
                .addRequiredHeaders(RequiredUploadHeader.newBuilder()
                        .setName("if-none-match").setValue("*"))
                .setExpiresAtEpochMs(1_700_000_060_000L)
                .build());
        AttachmentPayloadPolicy.requireValid(CompleteAttachmentUpload.newBuilder()
                .setAttachmentId(ATTACHMENT_ID).build());
        AttachmentPayloadPolicy.requireValid(AttachmentReady.newBuilder()
                .setAttachmentId(ATTACHMENT_ID)
                .setConversationId(CONVERSATION_ID)
                .setReadyAtEpochMs(1_700_000_000_000L)
                .build());
    }

    @Test
    void rejectsTraversalOversizeHashAndUnsafeGrantHeaders() {
        assertThrows(IllegalArgumentException.class,
                () -> AttachmentPayloadPolicy.requireValid(registration().toBuilder()
                        .setFileName("../secret")
                        .setByteSize(AttachmentPayloadPolicy.MAX_BYTE_SIZE + 1)
                        .setContentSha256(ByteString.copyFrom(new byte[31]))
                        .build()));
        assertThrows(IllegalArgumentException.class,
                () -> AttachmentPayloadPolicy.requireValid(
                        AttachmentUploadAuthorized.newBuilder()
                                .setAttachmentId(ATTACHMENT_ID)
                                .setUploadUri("http://objects.example.test/key")
                                .addRequiredHeaders(RequiredUploadHeader.newBuilder()
                                        .setName("Host").setValue("objects.example.test"))
                                .setExpiresAtEpochMs(1)
                                .build()));
    }

    private static RegisterAttachment registration() {
        return RegisterAttachment.newBuilder()
                .setConversationId(CONVERSATION_ID)
                .setClientAttachmentId("attach-1")
                .setFileName("a.txt")
                .setMediaType("text/plain")
                .setByteSize(2)
                .setContentSha256(ByteString.copyFrom(new byte[] {
                    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1
                }))
                .build();
    }
}

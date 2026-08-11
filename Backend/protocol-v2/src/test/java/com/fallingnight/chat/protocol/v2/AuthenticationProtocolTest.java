package com.fallingnight.chat.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.ByteString;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class AuthenticationProtocolTest {
    private static final String AUTHENTICATE_GOLDEN =
            "0a05616c696365120d746573742d70617373776f7264";

    @Test
    void authenticateHasStableWireBytesAndCommandKind() throws Exception {
        Authenticate command = Authenticate.newBuilder()
                .setUsername("alice")
                .setPasswordUtf8(ByteString.copyFromUtf8("test-password"))
                .build();
        AuthenticationPayloadPolicy.requireValid(command);
        assertEquals(AUTHENTICATE_GOLDEN, HexFormat.of().formatHex(command.toByteArray()));
        assertEquals(command,
                Authenticate.parseFrom(HexFormat.of().parseHex(AUTHENTICATE_GOLDEN)));
        assertEquals(
                MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_AUTHENTICATE));
    }

    @Test
    void boundsLoginAndResumeSecrets() {
        Authenticate emptyLogin = Authenticate.getDefaultInstance();
        assertEquals(2, AuthenticationPayloadPolicy.violations(emptyLogin).size());
        Authenticate oversizedPassword = Authenticate.newBuilder()
                .setUsername("alice")
                .setPasswordUtf8(ByteString.copyFrom(
                        new byte[AuthenticationPayloadPolicy.MAX_PASSWORD_BYTES + 1]))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> AuthenticationPayloadPolicy.requireValid(oversizedPassword));
        Authenticate invalidUtf8 = Authenticate.newBuilder()
                .setUsername("alice")
                .setPasswordUtf8(ByteString.copyFrom(new byte[] {(byte) 0x80}))
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> AuthenticationPayloadPolicy.requireValid(invalidUtf8));

        ResumeSession invalidResume = ResumeSession.newBuilder()
                .setSessionId("聊".repeat(43))
                .setResumeToken(ByteString.copyFrom(new byte[31]))
                .build();
        assertEquals(2, AuthenticationPayloadPolicy.violations(invalidResume).size());
        ResumeSession validResume = ResumeSession.newBuilder()
                .setSessionId("session-1")
                .setResumeToken(ByteString.copyFrom(
                        new byte[AuthenticationPayloadPolicy.RESUME_TOKEN_BYTES]))
                .build();
        AuthenticationPayloadPolicy.requireValid(validResume);
    }

    @Test
    void fixesAuthenticationRegistryKinds() {
        assertEquals(MessageKind.MESSAGE_KIND_COMMAND,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_RESUME_SESSION));
        assertEquals(MessageKind.MESSAGE_KIND_RESPONSE,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_SESSION_ESTABLISHED));
        assertEquals(MessageKind.MESSAGE_KIND_ERROR,
                MessageTypeRegistry.requiredKind(MessageType.MESSAGE_TYPE_AUTHENTICATION_REJECTED));
    }
}

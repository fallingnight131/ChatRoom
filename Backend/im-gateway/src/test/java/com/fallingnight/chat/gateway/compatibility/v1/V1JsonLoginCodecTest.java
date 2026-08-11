package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import com.fallingnight.chat.application.identity.AccountCredential;
import com.fallingnight.chat.application.identity.AuthenticationResult;
import com.fallingnight.chat.application.identity.AuthenticationService;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.identity.ClientPlatform;
import com.fallingnight.chat.application.identity.CredentialVerification;
import com.fallingnight.chat.application.identity.StoredCredential;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class V1JsonLoginCodecTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final V1JsonLoginCodec codec = new V1JsonLoginCodec(
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void decodesExistingEnvelopeAndTransfersPasswordToOwnedCommand() {
        byte[] wire = ("""
                {"type":"LOGIN_REQ","id":"old-client-request","timestamp":1,
                 "data":{"username":"alice","password":"secret-密码"}}
                """).getBytes(StandardCharsets.UTF_8);
        try (DecodedV1Login decoded = codec.decode(wire)) {
            assertEquals("alice", decoded.username());
            var command = decoded.toCommand(
                    new ClientDescriptor("v1-web-device", ClientPlatform.WEB, "v1"));
            AuthenticationService authentication = new AuthenticationService(
                    username -> Optional.of(account()),
                    (password, credential) -> {
                        assertEquals("secret-密码", new String(password, StandardCharsets.UTF_8));
                        return CredentialVerification.VERIFIED;
                    },
                    (account, client, now) -> Optional.empty(),
                    password -> new StoredCredential.Argon2id("unused"),
                    (accountId, expected, replacement) -> false,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            assertEquals(AuthenticationResult.Rejected.INSTANCE,
                    authentication.authenticate(command));
            assertTrue(command.isClosed());
            assertFalse(decoded.isClosed());
        }
    }

    @Test
    void rejectsMalformedAmbiguousAndOversizedLoginEnvelopes() {
        for (String invalid : new String[] {
                "",
                "[]",
                "{\"type\":\"CHAT_MSG\",\"data\":{}}",
                "{\"type\":\"LOGIN_REQ\",\"data\":[]}",
                "{\"type\":\"LOGIN_REQ\",\"data\":{\"username\":\"alice\"}}",
                "{\"type\":\"LOGIN_REQ\",\"type\":\"LOGIN_REQ\","
                        + "\"data\":{\"username\":\"alice\",\"password\":\"x\"}}",
                "{\"type\":\"LOGIN_REQ\",\"data\":{\"username\":\"alice\","
                        + "\"password\":\"x\"}} trailing",
                login("a".repeat(V1JsonLoginCodec.MAX_USERNAME_CHARS + 1), "x"),
                login("alice", "x".repeat(V1JsonLoginCodec.MAX_PASSWORD_CHARS + 1)),
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> codec.decode(invalid.getBytes(StandardCharsets.UTF_8)), invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(new byte[V1JsonLoginCodec.MAX_LOGIN_WIRE_BYTES + 1]));
    }

    @Test
    void encodesCompatibleSuccessWithoutV2SessionSecrets() throws Exception {
        LegacyV1AuthenticatedIdentity identity = identity();
        byte[] encoded = codec.encodeEstablished(identity);
        Map<String, Object> fields = responseData(encoded);

        assertEquals(Boolean.TRUE, fields.get("success"));
        assertEquals(17L, fields.get("userId"));
        assertEquals("alice", fields.get("username"));
        assertEquals("Alice", fields.get("displayName"));
        String json = new String(encoded, StandardCharsets.UTF_8);
        assertFalse(json.contains(identity.accountId().toString()));
        assertFalse(json.contains(identity.deviceId().toString()));
        assertFalse(json.contains(identity.sessionId().toString()));
        assertFalse(json.contains("resume"));
    }

    @Test
    void encodesOneGenericRejectionShape() throws Exception {
        Map<String, Object> fields = responseData(codec.encodeRejected());
        assertEquals(Boolean.FALSE, fields.get("success"));
        assertEquals("用户ID或密码错误", fields.get("error"));
        assertEquals(2, fields.size());
    }

    private static String login(String username, String password) {
        return "{\"type\":\"LOGIN_REQ\",\"data\":{\"username\":\""
                + username + "\",\"password\":\"" + password + "\"}}";
    }

    private static AccountCredential account() {
        return new AccountCredential(
                ACCOUNT_ID, "Alice", new StoredCredential.Argon2id("hash"), true);
    }

    private static LegacyV1AuthenticatedIdentity identity() {
        return new LegacyV1AuthenticatedIdentity(
                17,
                ACCOUNT_ID,
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                NOW.plusSeconds(3600),
                "alice",
                "Alice",
                false);
    }

    private static Map<String, Object> responseData(byte[] encoded) throws Exception {
        JsonFactory factory = new JsonFactory();
        java.util.HashMap<String, Object> data = new java.util.HashMap<>();
        try (JsonParser parser = factory.createParser(encoded)) {
            assertEquals(JsonToken.START_OBJECT, parser.nextToken());
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    assertEquals("LOGIN_RSP", parser.getText());
                } else if ("timestamp".equals(field)) {
                    assertEquals(NOW.toEpochMilli(), parser.getLongValue());
                } else if ("data".equals(field)) {
                    assertEquals(JsonToken.START_OBJECT, value);
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        String dataField = parser.currentName();
                        JsonToken dataValue = parser.nextToken();
                        data.put(dataField, switch (dataValue) {
                            case VALUE_TRUE -> Boolean.TRUE;
                            case VALUE_FALSE -> Boolean.FALSE;
                            case VALUE_NUMBER_INT -> parser.getLongValue();
                            case VALUE_STRING -> parser.getText();
                            default -> throw new AssertionError("unexpected response value");
                        });
                    }
                } else {
                    parser.skipChildren();
                }
            }
            assertEquals(null, parser.nextToken());
        }
        return Map.copyOf(data);
    }
}

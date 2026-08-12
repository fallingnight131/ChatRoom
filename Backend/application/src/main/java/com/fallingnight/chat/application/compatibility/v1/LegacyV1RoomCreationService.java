package com.fallingnight.chat.application.compatibility.v1;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Validates, hashes, and delegates one idempotent server-bound V1 room creation. */
public final class LegacyV1RoomCreationService implements LegacyV1RoomCreationUseCase {
    public static final int MAX_ROOM_NAME_CODE_POINTS = 100;
    public static final int MAX_CLIENT_REQUEST_ID_UTF8_BYTES = 128;
    public static final int MIN_PASSWORD_CODE_POINTS = 4;
    public static final int MAX_PASSWORD_CODE_POINTS = 1024;
    private final LegacyV1RoomPasswordHashPort passwords;
    private final LegacyV1RoomCreationPort rooms;
    public LegacyV1RoomCreationService(
            LegacyV1RoomPasswordHashPort passwords, LegacyV1RoomCreationPort rooms) {
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    @Override public LegacyV1RoomCreationResult create(LegacyV1RoomCreationCommand command) {
        Objects.requireNonNull(command, "command");
        try (command) {
            String name = command.roomName().strip();
            if (!validText(name, MAX_ROOM_NAME_CODE_POINTS)
                    || !validRequestId(command.clientRequestId())) {
                return LegacyV1RoomCreationResult.Rejected.INVALID_INPUT;
            }
            Optional<String> encoded = Optional.empty();
            if (command.hasPassword()) {
                if (!command.withPasswordCopy(LegacyV1RoomCreationService::validPassword)) {
                    return LegacyV1RoomCreationResult.Rejected.INVALID_INPUT;
                }
                encoded = Optional.of(command.withPasswordCopy(passwords::hash));
                if (encoded.orElseThrow().isBlank()) {
                    throw new IllegalStateException("room password hash is blank");
                }
            }
            LegacyV1RoomCreationResult result = Objects.requireNonNull(rooms.create(
                    new LegacyV1RoomCreationIntent(command.actorAccountId(),
                            command.clientRequestId(), name, encoded)), "room creation result");
            if (result instanceof LegacyV1RoomCreationResult.Created created
                    && (!created.creatorAccountId().equals(command.actorAccountId())
                    || !created.roomName().equals(name))) {
                throw new IllegalStateException("V1 room creation identity changed");
            }
            return result;
        }
    }

    private static boolean validText(String value, int maxCodePoints) {
        return !value.isEmpty() && value.codePointCount(0, value.length()) <= maxCodePoints
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean validRequestId(String value) {
        return !value.isBlank() && value.getBytes(StandardCharsets.UTF_8).length
                <= MAX_CLIENT_REQUEST_ID_UTF8_BYTES
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean validPassword(byte[] value) {
        CharBuffer decoded = CharBuffer.allocate(value.length);
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            if (decoder.decode(ByteBuffer.wrap(value), decoded, true).isError()
                    || decoder.flush(decoded).isError()) return false;
            decoded.flip();
            int codePoints = Character.codePointCount(decoded, 0, decoded.remaining());
            return codePoints >= MIN_PASSWORD_CODE_POINTS
                    && codePoints <= MAX_PASSWORD_CODE_POINTS;
        } finally { Arrays.fill(decoded.array(), '\0'); }
    }
}

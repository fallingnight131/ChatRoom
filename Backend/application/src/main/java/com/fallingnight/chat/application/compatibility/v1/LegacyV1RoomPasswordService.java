package com.fallingnight.chat.application.compatibility.v1;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Validates, hashes, and authorizes V1 room admission-password state. */
public final class LegacyV1RoomPasswordService
        implements LegacyV1RoomPasswordStatusUseCase, LegacyV1RoomPasswordUpdateUseCase {
    public static final int MIN_PASSWORD_CODE_POINTS = 4;
    public static final int MAX_PASSWORD_CODE_POINTS = 1024;
    private final LegacyV1RoomPasswordHashPort passwords;
    private final LegacyV1RoomPasswordPort rooms;

    public LegacyV1RoomPasswordService(
            LegacyV1RoomPasswordHashPort passwords, LegacyV1RoomPasswordPort rooms) {
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    @Override public LegacyV1RoomPasswordStatusResult status(
            UUID actorAccountId, long legacyRoomId) {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        if (!validRoomId(legacyRoomId))
            return LegacyV1RoomPasswordStatusResult.Rejected.INVALID_INPUT;
        LegacyV1RoomPasswordStatusResult result = Objects.requireNonNull(
                rooms.status(actorAccountId, legacyRoomId), "password status result");
        if (result instanceof LegacyV1RoomPasswordStatusResult.Authorized authorized
                && authorized.legacyRoomId() != legacyRoomId)
            throw new IllegalStateException("V1 room password status identity changed");
        return result;
    }

    @Override public LegacyV1RoomPasswordUpdateResult update(
            LegacyV1RoomPasswordCommand command) {
        Objects.requireNonNull(command, "command");
        try (command) {
            if (!validRoomId(command.legacyRoomId()))
                return LegacyV1RoomPasswordUpdateResult.Rejected.INVALID_INPUT;
            Optional<LegacyV1RoomPasswordEncoding> encoded;
            if (command.clearsPassword()) encoded = Optional.empty();
            else {
                if (!command.withPasswordCopy(LegacyV1RoomPasswordService::validPassword))
                    return LegacyV1RoomPasswordUpdateResult.Rejected.INVALID_INPUT;
                encoded = Optional.of(command.withPasswordCopy(passwords::hash));
            }
            LegacyV1RoomPasswordUpdateResult result = Objects.requireNonNull(rooms.update(
                    new LegacyV1RoomPasswordIntent(command.actorAccountId(),
                            command.legacyRoomId(), encoded)), "password update result");
            if (result instanceof LegacyV1RoomPasswordUpdateResult.Updated updated
                    && (updated.legacyRoomId() != command.legacyRoomId()
                        || updated.hasPassword() != encoded.isPresent()))
                throw new IllegalStateException("V1 room password update identity changed");
            return result;
        }
    }

    private static boolean validRoomId(long value) {
        return value > 0 && value <= Integer.MAX_VALUE;
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

package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.CredentialVerification;
import com.fallingnight.chat.application.identity.CredentialVerifierPort;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** Authorizes a V1 room password before delegating an atomic membership join. */
public final class LegacyV1RoomJoinService implements LegacyV1RoomJoinUseCase {
    public static final int MAX_PASSWORD_CODE_POINTS = 1024;
    private final CredentialVerifierPort passwords;
    private final LegacyV1RoomJoinPort rooms;

    public LegacyV1RoomJoinService(
            CredentialVerifierPort passwords, LegacyV1RoomJoinPort rooms) {
        this.passwords = Objects.requireNonNull(passwords, "passwords");
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    @Override public LegacyV1RoomJoinResult join(LegacyV1RoomJoinCommand command) {
        Objects.requireNonNull(command, "command");
        try (command) {
            if (command.legacyRoomId() <= 0 || command.legacyRoomId() > Integer.MAX_VALUE) {
                return LegacyV1RoomJoinResult.Rejected.INVALID_INPUT;
            }
            if (command.hasPassword()
                    && !command.withPasswordCopy(LegacyV1RoomJoinService::validPassword)) {
                return LegacyV1RoomJoinResult.Rejected.INVALID_INPUT;
            }
            LegacyV1RoomJoinAccess access = Objects.requireNonNull(rooms.inspect(
                    command.actorAccountId(), command.legacyRoomId()), "room join access");
            if (access instanceof LegacyV1RoomJoinAccess.AlreadyMember existing) {
                verifyIdentity(command, existing.membership());
                return existing.membership();
            }
            if (access == LegacyV1RoomJoinAccess.Rejected.NOT_FOUND) {
                return LegacyV1RoomJoinResult.Rejected.NOT_FOUND;
            }
            if (access == LegacyV1RoomJoinAccess.Rejected.JOIN_DENIED) {
                return LegacyV1RoomJoinResult.Rejected.JOIN_DENIED;
            }
            var candidate = (LegacyV1RoomJoinAccess.Candidate) access;
            verifyIdentity(command, candidate);
            if (candidate.joinCredential().isPresent()) {
                if (!command.hasPassword()) {
                    return LegacyV1RoomJoinResult.Rejected.PASSWORD_REQUIRED;
                }
                CredentialVerification verification = Objects.requireNonNull(
                        command.withPasswordCopy(password -> passwords.verifyOrDummy(
                                password, candidate.joinCredential())),
                        "room password verification");
                if (verification == CredentialVerification.REJECTED) {
                    return LegacyV1RoomJoinResult.Rejected.INVALID_PASSWORD;
                }
            }
            LegacyV1RoomJoinResult result = Objects.requireNonNull(rooms.join(
                    new LegacyV1RoomJoinIntent(command.actorAccountId(),
                            candidate.conversationId(), candidate.legacyRoomId(),
                            candidate.joinCredential())), "room join result");
            if (result instanceof LegacyV1RoomJoinResult.Joined joined) {
                verifyIdentity(command, joined);
                if (!joined.conversationId().equals(candidate.conversationId())
                        || !joined.roomName().equals(candidate.roomName())) {
                    throw new IllegalStateException("V1 room join target changed");
                }
            }
            return result;
        }
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
            return decoded.hasRemaining()
                    && Character.codePointCount(decoded, 0, decoded.remaining())
                    <= MAX_PASSWORD_CODE_POINTS;
        } finally { Arrays.fill(decoded.array(), '\0'); }
    }

    private static void verifyIdentity(
            LegacyV1RoomJoinCommand command, LegacyV1RoomJoinResult.Joined joined) {
        if (!joined.actorAccountId().equals(command.actorAccountId())
                || joined.legacyRoomId() != command.legacyRoomId()) {
            throw new IllegalStateException("V1 room join identity changed");
        }
    }

    private static void verifyIdentity(
            LegacyV1RoomJoinCommand command, LegacyV1RoomJoinAccess.Candidate candidate) {
        if (!candidate.actorAccountId().equals(command.actorAccountId())
                || candidate.legacyRoomId() != command.legacyRoomId()) {
            throw new IllegalStateException("V1 room join identity changed");
        }
    }
}

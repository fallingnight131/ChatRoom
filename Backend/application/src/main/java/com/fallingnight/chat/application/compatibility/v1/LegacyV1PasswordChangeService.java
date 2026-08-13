package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Verifies and replaces an authenticated V1 credential without retaining plaintext. */
public final class LegacyV1PasswordChangeService implements LegacyV1PasswordChangeUseCase {
    public static final int MIN_NEW_PASSWORD_CODE_POINTS = 4;
    public static final int MAX_PASSWORD_CODE_POINTS = 1024;
    private final CredentialVerifierPort verifier;
    private final CredentialHashPort hasher;
    private final LegacyV1PasswordChangePort credentials;

    public LegacyV1PasswordChangeService(CredentialVerifierPort verifier,
            CredentialHashPort hasher, LegacyV1PasswordChangePort credentials) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override public LegacyV1PasswordChangeResult change(LegacyV1PasswordChangeCommand command) {
        Objects.requireNonNull(command, "command");
        try (command) {
            Boolean valid = command.withPasswordCopies((oldValue, newValue) ->
                    validPassword(oldValue, 1) && validPassword(
                            newValue, MIN_NEW_PASSWORD_CODE_POINTS));
            if (!valid) return LegacyV1PasswordChangeResult.Rejected.INVALID_INPUT;
            LegacyV1PasswordChangeAccess access = Objects.requireNonNull(credentials.inspect(
                    command.actorAccountId(), command.currentSessionId()), "password access");
            if (access == LegacyV1PasswordChangeAccess.Rejected.SESSION_INVALID)
                return LegacyV1PasswordChangeResult.Rejected.SESSION_INVALID;
            var candidate = (LegacyV1PasswordChangeAccess.Candidate) access;
            Verification verification = command.withPasswordCopies((oldValue, newValue) ->
                    new Verification(verifies(oldValue, candidate.credential()),
                            verifies(newValue, candidate.credential())));
            if (!verification.oldMatches()) {
                return verification.newMatches()
                        ? new LegacyV1PasswordChangeResult.Changed(
                                false, 0, candidate.passwordChangedAt())
                        : LegacyV1PasswordChangeResult.Rejected.CURRENT_PASSWORD_INCORRECT;
            }
            StoredCredential.Argon2id replacement = command.withPasswordCopies(
                    (oldValue, newValue) -> hasher.hash(newValue));
            LegacyV1PasswordChangePersistenceResult persisted = Objects.requireNonNull(
                    credentials.replace(new LegacyV1PasswordChangeIntent(
                            command.actorAccountId(), command.currentSessionId(),
                            candidate.credential(), replacement)), "password replace result");
            if (persisted instanceof LegacyV1PasswordChangePersistenceResult.Updated updated)
                return new LegacyV1PasswordChangeResult.Changed(true,
                        updated.otherSessionsRevoked(), updated.changedAt());
            return switch ((LegacyV1PasswordChangePersistenceResult.Rejected) persisted) {
                case SESSION_INVALID -> LegacyV1PasswordChangeResult.Rejected.SESSION_INVALID;
                case CONCURRENT_CHANGE -> LegacyV1PasswordChangeResult.Rejected.CONCURRENT_CHANGE;
            };
        }
    }

    private boolean verifies(byte[] password, StoredCredential credential) {
        return verifier.verifyOrDummy(password, Optional.of(credential))
                != CredentialVerification.REJECTED;
    }
    private static boolean validPassword(byte[] value, int minimumCodePoints) {
        CharBuffer decoded = CharBuffer.allocate(value.length);
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            if (decoder.decode(ByteBuffer.wrap(value), decoded, true).isError()
                    || decoder.flush(decoded).isError()) return false;
            decoded.flip(); int count = Character.codePointCount(decoded, 0, decoded.remaining());
            return count >= minimumCodePoints && count <= MAX_PASSWORD_CODE_POINTS;
        } finally { Arrays.fill(decoded.array(), '\0'); }
    }
    private record Verification(boolean oldMatches, boolean newMatches) { }
}

package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validates, hashes, and converges V1 registration on the username natural key. */
public final class LegacyV1RegistrationService implements LegacyV1RegistrationUseCase {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{6,20}");
    public static final int MAX_DISPLAY_NAME_CODE_POINTS = 64;
    public static final int MIN_PASSWORD_CODE_POINTS = 4;
    public static final int MAX_PASSWORD_CODE_POINTS = 1024;
    private final CredentialHashPort hasher;
    private final CredentialVerifierPort verifier;
    private final LegacyV1RegistrationPort accounts;

    public LegacyV1RegistrationService(CredentialHashPort hasher,
            CredentialVerifierPort verifier, LegacyV1RegistrationPort accounts) {
        this.hasher = Objects.requireNonNull(hasher); this.verifier = Objects.requireNonNull(verifier);
        this.accounts = Objects.requireNonNull(accounts);
    }

    @Override public LegacyV1RegistrationResult register(LegacyV1RegistrationCommand command) {
        Objects.requireNonNull(command, "command");
        try (command) {
            String displayName = command.displayName().strip();
            if (!USERNAME.matcher(command.username()).matches() || displayName.isEmpty()
                    || displayName.codePointCount(0, displayName.length())
                        > MAX_DISPLAY_NAME_CODE_POINTS
                    || !command.withPasswordCopy(LegacyV1RegistrationService::validPassword))
                return LegacyV1RegistrationResult.Rejected.INVALID_INPUT;
            StoredCredential.Argon2id encoded = command.withPasswordCopy(hasher::hash);
            LegacyV1RegistrationPersistenceResult persisted = Objects.requireNonNull(
                    accounts.register(new LegacyV1RegistrationIntent(
                            command.username(), displayName, encoded)), "registration result");
            if (persisted instanceof LegacyV1RegistrationPersistenceResult.Created created)
                return new LegacyV1RegistrationResult.Registered(created.legacyUserId(),
                        command.username(), displayName, false, created.createdAt());
            if (persisted instanceof LegacyV1RegistrationPersistenceResult.Existing existing) {
                boolean same = existing.legacyUserId().isPresent()
                        && existing.username().equals(command.username())
                        && existing.displayName().equals(displayName)
                        && command.withPasswordCopy(value -> verifier.verifyOrDummy(
                                value, Optional.of(existing.credential())))
                            != CredentialVerification.REJECTED;
                return same ? new LegacyV1RegistrationResult.Registered(
                        existing.legacyUserId().getAsLong(), command.username(), displayName,
                        true, existing.createdAt())
                        : LegacyV1RegistrationResult.Rejected.USERNAME_TAKEN;
            }
            return LegacyV1RegistrationResult.Rejected.REGISTRATION_UNAVAILABLE;
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
            decoded.flip(); int count = Character.codePointCount(decoded, 0, decoded.remaining());
            return count >= MIN_PASSWORD_CODE_POINTS && count <= MAX_PASSWORD_CODE_POINTS;
        } finally { Arrays.fill(decoded.array(), '\0'); }
    }
}

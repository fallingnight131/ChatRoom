package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.identity.AccountCredential;
import com.fallingnight.chat.application.identity.AccountCredentialPort;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.identity.CredentialUpgradePort;
import com.fallingnight.chat.application.identity.IssuedSession;
import com.fallingnight.chat.application.identity.SessionIssuePort;
import com.fallingnight.chat.application.identity.SessionResumePort;
import com.fallingnight.chat.application.identity.StoredCredential;
import com.fallingnight.chat.application.security.SecretBytes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** PostgreSQL account lookup and transactional device/session issuance adapter. */
public final class PostgresIdentityAdapter
        implements AccountCredentialPort, SessionIssuePort, SessionResumePort, CredentialUpgradePort {
    public static final int TOKEN_BYTES = 32;
    public static final Duration DEFAULT_SESSION_LIFETIME = Duration.ofDays(30);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DataSource dataSource;
    private final Duration sessionLifetime;
    private final Supplier<UUID> uuidSupplier;
    private final Supplier<byte[]> tokenSupplier;

    public PostgresIdentityAdapter(DataSource dataSource) {
        this(
                dataSource,
                DEFAULT_SESSION_LIFETIME,
                UUID::randomUUID,
                PostgresIdentityAdapter::secureRandomToken);
    }

    PostgresIdentityAdapter(
            DataSource dataSource,
            Duration sessionLifetime,
            Supplier<UUID> uuidSupplier,
            Supplier<byte[]> tokenSupplier) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.sessionLifetime = requirePositive(sessionLifetime);
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
    }

    @Override
    public Optional<AccountCredential> findByPresentedUsername(String username) {
        Objects.requireNonNull(username, "username");
        String sql = "SELECT id, display_name, password_hash, password_scheme, "
                + "legacy_password_salt, disabled_at IS NULL "
                + "FROM chat.account WHERE username_key = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                String scheme = result.getString(4);
                StoredCredential credential = switch (scheme) {
                    case "ARGON2ID" -> new StoredCredential.Argon2id(result.getString(3));
                    case "V1_SHA256" -> new StoredCredential.LegacySha256(
                            result.getString(3), result.getString(5));
                    default -> throw new IdentityPersistenceException(
                            "unsupported stored credential scheme");
                };
                return Optional.of(new AccountCredential(
                        result.getObject(1, UUID.class),
                        result.getString(2),
                        credential,
                        result.getBoolean(6)));
            }
        } catch (SQLException exception) {
            throw new IdentityPersistenceException("account credential lookup failed", exception);
        }
    }

    @Override
    public Optional<IssuedSession> issue(
            AccountCredential account, ClientDescriptor client, Instant now) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(now, "now");
        byte[] token = requireToken(tokenSupplier.get());
        byte[] tokenHash = sha256(token);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            IssuedSession issued = null;
            try {
                if (!lockEnabledAccount(connection, account.accountId())) {
                    connection.rollback();
                    return Optional.empty();
                }
                Optional<UUID> deviceId = upsertActiveDevice(
                        connection, account.accountId(), client, now);
                if (deviceId.isEmpty()) {
                    connection.rollback();
                    return Optional.empty();
                }
                UUID sessionId = Objects.requireNonNull(uuidSupplier.get(), "sessionId");
                Instant expiresAt = now.plus(sessionLifetime);
                issued = new IssuedSession(
                        account.accountId(),
                        deviceId.orElseThrow(),
                        sessionId,
                        SecretBytes.copyOf(token),
                        expiresAt,
                        account.displayName());
                insertSession(
                        connection,
                        sessionId,
                        account.accountId(),
                        deviceId.orElseThrow(),
                        tokenHash,
                        now,
                        expiresAt);
                connection.commit();
                return Optional.of(issued);
            } catch (SQLException | RuntimeException exception) {
                if (issued != null) {
                    issued.close();
                }
                rollbackAfterFailure(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IdentityPersistenceException("device/session issuance failed", exception);
        } finally {
            Arrays.fill(token, (byte) 0);
            Arrays.fill(tokenHash, (byte) 0);
        }
    }

    @Override
    public Optional<IssuedSession> resumeAndRotate(
            UUID sessionId,
            SecretBytes presentedToken,
            ClientDescriptor client,
            Instant now) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(presentedToken, "presentedToken");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(now, "now");
        byte[] presentedHash = presentedToken.withCopy(PostgresIdentityAdapter::sha256);
        byte[] replacementToken = requireToken(tokenSupplier.get());
        byte[] replacementHash = sha256(replacementToken);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            IssuedSession issued = null;
            try {
                Optional<ResumableSession> resumable = lockResumableSession(
                        connection, sessionId, presentedHash, client.clientDeviceId(), now);
                if (resumable.isEmpty()) {
                    connection.rollback();
                    return Optional.empty();
                }
                ResumableSession current = resumable.orElseThrow();
                Instant expiresAt = now.plus(sessionLifetime);
                issued = new IssuedSession(
                        current.accountId(),
                        current.deviceId(),
                        sessionId,
                        SecretBytes.copyOf(replacementToken),
                        expiresAt,
                        current.displayName());
                rotateSessionProof(
                        connection, sessionId, replacementHash, expiresAt);
                touchResumedDevice(connection, current.deviceId(), client, now);
                connection.commit();
                return Optional.of(issued);
            } catch (SQLException | RuntimeException exception) {
                if (issued != null) {
                    issued.close();
                }
                rollbackAfterFailure(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IdentityPersistenceException("session resume/rotation failed", exception);
        } finally {
            Arrays.fill(presentedHash, (byte) 0);
            Arrays.fill(replacementToken, (byte) 0);
            Arrays.fill(replacementHash, (byte) 0);
        }
    }

    @Override
    public boolean replace(
            UUID accountId,
            StoredCredential expected,
            StoredCredential.Argon2id replacement) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(replacement, "replacement");
        String expectedScheme;
        String expectedHash;
        String expectedSalt;
        if (expected instanceof StoredCredential.Argon2id argon2id) {
            expectedScheme = "ARGON2ID";
            expectedHash = argon2id.encodedHash();
            expectedSalt = null;
        } else if (expected instanceof StoredCredential.LegacySha256 legacy) {
            expectedScheme = "V1_SHA256";
            expectedHash = legacy.hexDigest();
            expectedSalt = legacy.salt();
        } else {
            throw new IllegalArgumentException("unsupported expected credential");
        }
        String sql = "UPDATE chat.account SET password_hash = ?, "
                + "password_scheme = 'ARGON2ID', legacy_password_salt = NULL "
                + "WHERE id = ? AND password_scheme = ? AND password_hash = ? "
                + "AND legacy_password_salt IS NOT DISTINCT FROM ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replacement.encodedHash());
            statement.setObject(2, accountId);
            statement.setString(3, expectedScheme);
            statement.setString(4, expectedHash);
            statement.setString(5, expectedSalt);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IdentityPersistenceException("credential upgrade failed", exception);
        }
    }

    private static boolean lockEnabledAccount(Connection connection, UUID accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM chat.account WHERE id = ? AND disabled_at IS NULL FOR UPDATE")) {
            statement.setObject(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Optional<ResumableSession> lockResumableSession(
            Connection connection,
            UUID sessionId,
            byte[] presentedHash,
            String clientDeviceId,
            Instant now) throws SQLException {
        String sql = "SELECT ds.account_id, ds.device_id, a.display_name "
                + "FROM chat.device_session ds "
                + "JOIN chat.account a ON a.id = ds.account_id "
                + "JOIN chat.device d ON d.id = ds.device_id AND d.account_id = ds.account_id "
                + "WHERE ds.id = ? AND ds.token_sha256 = ? "
                + "AND ds.revoked_at IS NULL AND ds.expires_at > ? "
                + "AND a.disabled_at IS NULL AND d.revoked_at IS NULL "
                + "AND d.client_device_id = ? FOR UPDATE OF ds, a, d";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sessionId);
            statement.setBytes(2, presentedHash);
            statement.setObject(3, utc(now));
            statement.setString(4, clientDeviceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ResumableSession(
                        result.getObject(1, UUID.class),
                        result.getObject(2, UUID.class),
                        result.getString(3)));
            }
        }
    }

    private static void rotateSessionProof(
            Connection connection,
            UUID sessionId,
            byte[] replacementHash,
            Instant expiresAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat.device_session SET token_sha256 = ?, expires_at = ? WHERE id = ?")) {
            statement.setBytes(1, replacementHash);
            statement.setObject(2, utc(expiresAt));
            statement.setObject(3, sessionId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("locked session disappeared during token rotation");
            }
        }
    }

    private static void touchResumedDevice(
            Connection connection,
            UUID deviceId,
            ClientDescriptor client,
            Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat.device SET platform = ?, last_seen_at = ? WHERE id = ?")) {
            statement.setString(1, client.platform().name());
            statement.setObject(2, utc(now));
            statement.setObject(3, deviceId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("locked device disappeared during session rotation");
            }
        }
    }

    private Optional<UUID> upsertActiveDevice(
            Connection connection,
            UUID accountId,
            ClientDescriptor client,
            Instant now) throws SQLException {
        String sql = "INSERT INTO chat.device(id, account_id, client_device_id, platform, "
                + "created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (account_id, client_device_id) DO UPDATE SET "
                + "platform = EXCLUDED.platform, last_seen_at = EXCLUDED.last_seen_at "
                + "WHERE chat.device.revoked_at IS NULL RETURNING id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, Objects.requireNonNull(uuidSupplier.get(), "deviceId"));
            statement.setObject(2, accountId);
            statement.setString(3, client.clientDeviceId());
            statement.setString(4, client.platform().name());
            statement.setObject(5, utc(now));
            statement.setObject(6, utc(now));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(result.getObject(1, UUID.class));
            }
        }
    }

    private static void insertSession(
            Connection connection,
            UUID sessionId,
            UUID accountId,
            UUID deviceId,
            byte[] tokenHash,
            Instant now,
            Instant expiresAt) throws SQLException {
        String sql = "INSERT INTO chat.device_session(id, account_id, device_id, "
                + "token_sha256, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sessionId);
            statement.setObject(2, accountId);
            statement.setObject(3, deviceId);
            statement.setBytes(4, tokenHash);
            statement.setObject(5, utc(now));
            statement.setObject(6, utc(expiresAt));
            statement.executeUpdate();
        }
    }

    private static void rollbackAfterFailure(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "sessionLifetime");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("sessionLifetime must be positive");
        }
        return value;
    }

    private static byte[] requireToken(byte[] value) {
        Objects.requireNonNull(value, "resumeToken");
        if (value.length != TOKEN_BYTES) {
            Arrays.fill(value, (byte) 0);
            throw new IllegalArgumentException("resumeToken must contain exactly 32 bytes");
        }
        return value;
    }

    private static byte[] secureRandomToken() {
        byte[] token = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(token);
        return token;
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ResumableSession(UUID accountId, UUID deviceId, String displayName) {
    }
}

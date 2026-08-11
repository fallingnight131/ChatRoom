package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.identity.AccountCredential;
import com.fallingnight.chat.application.identity.AccountCredentialPort;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.identity.IssuedSession;
import com.fallingnight.chat.application.identity.SessionIssuePort;
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
        implements AccountCredentialPort, SessionIssuePort {
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
        String sql = "SELECT id, display_name, password_hash, disabled_at IS NULL "
                + "FROM chat.account WHERE username_key = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AccountCredential(
                        result.getObject(1, UUID.class),
                        result.getString(2),
                        result.getString(3),
                        result.getBoolean(4)));
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
}

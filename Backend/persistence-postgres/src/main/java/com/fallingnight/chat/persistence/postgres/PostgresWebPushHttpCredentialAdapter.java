package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.identity.AuthenticatedDeviceActor;
import com.fallingnight.chat.application.notification.IssuedWebPushHttpCredential;
import com.fallingnight.chat.application.notification.WebPushHttpCredentialAuthenticationPort;
import com.fallingnight.chat.application.notification.WebPushHttpCredentialAuthenticationResult;
import com.fallingnight.chat.application.notification.WebPushHttpCredentialIssuePort;
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
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

/** PostgreSQL authority for one current short-lived Web Push HTTP credential per session. */
public final class PostgresWebPushHttpCredentialAdapter
        implements WebPushHttpCredentialIssuePort, WebPushHttpCredentialAuthenticationPort {
    public static final int RANDOM_TOKEN_BYTES = 32;
    public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(10);
    public static final Duration MAXIMUM_LIFETIME = Duration.ofHours(1);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DataSource dataSource;
    private final Duration lifetime;
    private final Supplier<byte[]> tokenSupplier;

    public PostgresWebPushHttpCredentialAdapter(DataSource dataSource) {
        this(dataSource, DEFAULT_LIFETIME, PostgresWebPushHttpCredentialAdapter::randomToken);
    }

    PostgresWebPushHttpCredentialAdapter(
            DataSource dataSource, Duration lifetime, Supplier<byte[]> tokenSupplier) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.lifetime = requireLifetime(lifetime);
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
    }

    @Override
    public Optional<IssuedWebPushHttpCredential> issue(
            AuthenticatedDeviceActor actor, Instant observedAt) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(observedAt, "observedAt");
        byte[] bearerRandom = requireRandomToken(tokenSupplier.get());
        byte[] csrfRandom = null;
        byte[] bearer = null;
        byte[] csrf = null;
        byte[] bearerHash = null;
        byte[] csrfHash = null;
        IssuedWebPushHttpCredential issued = null;
        try {
            csrfRandom = requireRandomToken(tokenSupplier.get());
            bearer = encodeToken(bearerRandom);
            csrf = encodeToken(csrfRandom);
            bearerHash = sha256(bearer);
            csrfHash = sha256(csrf);
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    Optional<Instant> sessionExpiry = lockCurrentSession(
                            connection, actor, observedAt);
                    if (sessionExpiry.isEmpty()) {
                        connection.rollback();
                        return Optional.empty();
                    }
                    Instant expiresAt = minimum(
                            observedAt.plus(lifetime), sessionExpiry.orElseThrow());
                    if (!expiresAt.isAfter(observedAt)) {
                        connection.rollback();
                        return Optional.empty();
                    }
                    issued = IssuedWebPushHttpCredential.copyOf(
                            actor.sessionId(), bearer, csrf, expiresAt);
                    replace(connection, actor.sessionId(), bearerHash, csrfHash,
                            observedAt, expiresAt);
                    connection.commit();
                    return Optional.of(issued);
                } catch (SQLException | RuntimeException exception) {
                    if (issued != null) issued.close();
                    rollback(connection, exception);
                    throw exception;
                }
            } catch (SQLException exception) {
                throw new NotificationPersistenceException(
                        "Web Push HTTP credential issuance failed", exception);
            }
        } finally {
            clear(bearerRandom);
            clear(csrfRandom);
            clear(bearer);
            clear(csrf);
            clear(bearerHash);
            clear(csrfHash);
        }
    }

    @Override
    public WebPushHttpCredentialAuthenticationResult authenticate(
            byte[] bearerToken, byte[] csrfToken, Instant observedAt) {
        Objects.requireNonNull(bearerToken, "bearerToken");
        Objects.requireNonNull(csrfToken, "csrfToken");
        Objects.requireNonNull(observedAt, "observedAt");
        if (!validTokenShape(bearerToken)) {
            return WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_SESSION;
        }
        if (!validTokenShape(csrfToken)) {
            return WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_CSRF;
        }
        byte[] bearerHash = sha256(bearerToken);
        byte[] csrfHash = sha256(csrfToken);
        byte[] expectedCsrfHash = null;
        try {
            String sql = """
                    SELECT session.account_id, session.device_id, session.id,
                           credential.csrf_sha256
                    FROM chat.web_push_http_credential credential
                    JOIN chat.device_session session ON session.id = credential.session_id
                    JOIN chat.account account ON account.id = session.account_id
                    JOIN chat.device device ON device.id = session.device_id
                      AND device.account_id = session.account_id
                    WHERE credential.bearer_sha256 = ?
                      AND credential.expires_at > ?
                      AND session.revoked_at IS NULL AND session.expires_at > ?
                      AND account.disabled_at IS NULL AND device.revoked_at IS NULL
                    """;
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, bearerHash);
                statement.setObject(2, utc(observedAt));
                statement.setObject(3, utc(observedAt));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_SESSION;
                    }
                    AuthenticatedDeviceActor actor = new AuthenticatedDeviceActor(
                            result.getObject(1, UUID.class),
                            result.getObject(2, UUID.class),
                            result.getObject(3, UUID.class));
                    expectedCsrfHash = result.getBytes(4);
                    if (result.next()) {
                        throw new SQLException("credential verifier matched multiple sessions");
                    }
                    if (!MessageDigest.isEqual(expectedCsrfHash, csrfHash)) {
                        return WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_CSRF;
                    }
                    return new WebPushHttpCredentialAuthenticationResult.Authenticated(actor);
                }
            } catch (SQLException exception) {
                throw new NotificationPersistenceException(
                        "Web Push HTTP credential authentication failed", exception);
            }
        } finally {
            clear(bearerHash);
            clear(csrfHash);
            clear(expectedCsrfHash);
        }
    }

    private static Optional<Instant> lockCurrentSession(
            Connection connection, AuthenticatedDeviceActor actor, Instant observedAt)
            throws SQLException {
        String sql = """
                SELECT session.expires_at
                FROM chat.device_session session
                JOIN chat.account account ON account.id = session.account_id
                JOIN chat.device device ON device.id = session.device_id
                  AND device.account_id = session.account_id
                WHERE session.id = ? AND session.account_id = ? AND session.device_id = ?
                  AND session.revoked_at IS NULL AND session.expires_at > ?
                  AND account.disabled_at IS NULL AND device.revoked_at IS NULL
                FOR UPDATE OF session, account, device
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, actor.sessionId());
            statement.setObject(2, actor.accountId());
            statement.setObject(3, actor.deviceId());
            statement.setObject(4, utc(observedAt));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                Instant expiry = result.getObject(1, OffsetDateTime.class).toInstant();
                if (result.next()) throw new SQLException("session identity was not unique");
                return Optional.of(expiry);
            }
        }
    }

    private static void replace(Connection connection, UUID sessionId,
            byte[] bearerHash, byte[] csrfHash, Instant issuedAt, Instant expiresAt)
            throws SQLException {
        String sql = """
                INSERT INTO chat.web_push_http_credential(
                    session_id, bearer_sha256, csrf_sha256, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (session_id) DO UPDATE SET
                    bearer_sha256 = EXCLUDED.bearer_sha256,
                    csrf_sha256 = EXCLUDED.csrf_sha256,
                    issued_at = EXCLUDED.issued_at,
                    expires_at = EXCLUDED.expires_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, sessionId);
            statement.setBytes(2, bearerHash);
            statement.setBytes(3, csrfHash);
            statement.setObject(4, utc(issuedAt));
            statement.setObject(5, utc(expiresAt));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("credential replacement changed no row");
            }
        }
    }

    private static byte[] encodeToken(byte[] random) {
        return Base64.getUrlEncoder().withoutPadding().encode(random);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static byte[] requireRandomToken(byte[] token) {
        Objects.requireNonNull(token, "randomToken");
        if (token.length != RANDOM_TOKEN_BYTES) {
            clear(token);
            throw new IllegalArgumentException("random token must contain exactly 32 bytes");
        }
        return token;
    }

    private static boolean validTokenShape(byte[] token) {
        if (token.length < IssuedWebPushHttpCredential.MIN_TOKEN_BYTES
                || token.length > IssuedWebPushHttpCredential.MAX_TOKEN_BYTES) return false;
        for (byte value : token) {
            int character = Byte.toUnsignedInt(value);
            if (!(character >= 'A' && character <= 'Z')
                    && !(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && character != '-' && character != '_') return false;
        }
        return true;
    }

    private static Duration requireLifetime(Duration lifetime) {
        Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException("lifetime must be within 1 nanosecond..1 hour");
        }
        return lifetime;
    }

    private static byte[] randomToken() {
        byte[] token = new byte[RANDOM_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(token);
        return token;
    }

    private static Instant minimum(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void clear(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }
}

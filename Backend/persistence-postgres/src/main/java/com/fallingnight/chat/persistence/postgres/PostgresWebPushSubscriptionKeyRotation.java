package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.notification.ProtectedWebPushSubscription;
import com.fallingnight.chat.application.notification.WebPushCredentialProtectionPort;
import com.fallingnight.chat.application.notification.WebPushCredentialUnprotectionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Offline ACCESS EXCLUSIVE rewrite from old custody to new active encryption/lookup keys. */
public final class PostgresWebPushSubscriptionKeyRotation {
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_SUBSCRIPTIONS = 1_000_000;

    private final DataSource dataSource;
    private final WebPushCredentialUnprotectionPort source;
    private final WebPushCredentialProtectionPort target;
    private final String targetEncryptionKeyId;

    public PostgresWebPushSubscriptionKeyRotation(
            DataSource dataSource,
            WebPushCredentialUnprotectionPort source,
            WebPushCredentialProtectionPort target,
            String targetEncryptionKeyId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.targetEncryptionKeyId = requireKeyId(targetEncryptionKeyId);
    }

    public WebPushSubscriptionKeyRotationReport rotate(int fetchBatchSize, int maximumRows) {
        if (fetchBatchSize < 1 || fetchBatchSize > MAX_BATCH_SIZE
                || maximumRows < 1 || maximumRows > MAX_SUBSCRIPTIONS) {
            throw new IllegalArgumentException("Web Push rotation bounds are invalid");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockTable(connection);
                int total = subscriptionCount(connection);
                if (total > maximumRows) {
                    throw new IllegalStateException("Web Push rotation row limit exceeded");
                }
                Set<String> sourceKeyIds = new LinkedHashSet<>();
                int rotated = rewrite(connection, fetchBatchSize, sourceKeyIds);
                if (rotated != total) {
                    throw new SQLException("Web Push rotation count changed under table lock");
                }
                connection.commit();
                return new WebPushSubscriptionKeyRotationReport(
                        rotated, sourceKeyIds, targetEncryptionKeyId);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new NotificationPersistenceException(
                    "Web Push subscription key rotation failed", exception);
        }
    }

    private int rewrite(
            Connection connection, int fetchBatchSize, Set<String> sourceKeyIds)
            throws SQLException {
        String select = """
                SELECT account_id, installation_id, browser_expires_at,
                       encryption_key_id, endpoint_ciphertext, p256dh_ciphertext,
                       auth_secret_ciphertext, endpoint_lookup_tag
                FROM chat.web_push_subscription
                ORDER BY account_id, installation_id
                FOR UPDATE
                """;
        String update = """
                UPDATE chat.web_push_subscription
                SET endpoint_ciphertext = ?, p256dh_ciphertext = ?,
                    auth_secret_ciphertext = ?, endpoint_lookup_tag = ?,
                    encryption_key_id = ?, updated_at = transaction_timestamp()
                WHERE account_id = ? AND installation_id = ?
                """;
        int rotated = 0;
        try (PreparedStatement query = connection.prepareStatement(select);
                PreparedStatement mutation = connection.prepareStatement(update)) {
            query.setFetchSize(fetchBatchSize);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    UUID account = result.getObject(1, UUID.class);
                    UUID installation = result.getObject(2, UUID.class);
                    String sourceKeyId = result.getString(4);
                    try (ProtectedWebPushSubscription oldValue =
                                    readProtected(result, account, installation, sourceKeyId);
                            WebPushSubscriptionRegistration plain = Objects.requireNonNull(
                                    source.unprotect(oldValue), "unprotectedSubscription");
                            ProtectedWebPushSubscription newValue = Objects.requireNonNull(
                                    target.protect(plain), "reprotectedSubscription")) {
                        requireSameBinding(oldValue, plain, newValue);
                        if (!targetEncryptionKeyId.equals(newValue.encryptionKeyId())) {
                            throw new IllegalStateException(
                                    "Web Push rotation target key ID differs from configuration");
                        }
                        newValue.withCopies((endpoint, p256dh, auth, lookupTag) -> {
                            mutation.setBytes(1, endpoint);
                            mutation.setBytes(2, p256dh);
                            mutation.setBytes(3, auth);
                            mutation.setBytes(4, lookupTag);
                            mutation.setString(5, newValue.encryptionKeyId());
                            mutation.setObject(6, account);
                            mutation.setObject(7, installation);
                            if (mutation.executeUpdate() != 1) {
                                throw new SQLException("Web Push rotation lost a locked row");
                            }
                            return null;
                        });
                    }
                    sourceKeyIds.add(sourceKeyId);
                    rotated++;
                }
            }
        }
        return rotated;
    }

    private static ProtectedWebPushSubscription readProtected(
            ResultSet result, UUID account, UUID installation, String keyId)
            throws SQLException {
        byte[] endpoint = result.getBytes(5);
        byte[] p256dh = result.getBytes(6);
        byte[] auth = result.getBytes(7);
        byte[] lookupTag = result.getBytes(8);
        try {
            OffsetDateTime expiry = result.getObject(3, OffsetDateTime.class);
            return ProtectedWebPushSubscription.copyOf(
                    account, installation,
                    Optional.ofNullable(expiry).map(OffsetDateTime::toInstant),
                    keyId, endpoint, p256dh, auth, lookupTag);
        } finally {
            clear(endpoint);
            clear(p256dh);
            clear(auth);
            clear(lookupTag);
        }
    }

    private static void requireSameBinding(
            ProtectedWebPushSubscription oldValue,
            WebPushSubscriptionRegistration plain,
            ProtectedWebPushSubscription newValue) {
        if (!oldValue.accountId().equals(plain.accountId())
                || !oldValue.installationId().equals(plain.installationId())
                || !oldValue.browserExpiresAt().equals(plain.browserExpiresAt())
                || !oldValue.accountId().equals(newValue.accountId())
                || !oldValue.installationId().equals(newValue.installationId())
                || !oldValue.browserExpiresAt().equals(newValue.browserExpiresAt())) {
            throw new IllegalStateException("Web Push rotation changed subscription binding");
        }
    }

    private static void lockTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "LOCK TABLE chat.web_push_subscription IN ACCESS EXCLUSIVE MODE")) {
            statement.execute();
        }
    }

    private static int subscriptionCount(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM chat.web_push_subscription");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Web Push rotation count missing");
            long count = result.getLong(1);
            if (count > Integer.MAX_VALUE) {
                throw new IllegalStateException("Web Push rotation row count is too large");
            }
            return (int) count;
        }
    }

    private static String requireKeyId(String value) {
        Objects.requireNonNull(value, "targetEncryptionKeyId");
        if (!value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("invalid target encryption key ID");
        }
        return value;
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

package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.notification.ProtectedWebPushSubscription;
import com.fallingnight.chat.application.notification.WebPushCredentialProtectionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Ciphertext-only PostgreSQL subscription replacement and account/install erasure. */
public final class PostgresWebPushSubscriptionAdapter implements WebPushSubscriptionPort {
    private final DataSource dataSource;
    private final WebPushCredentialProtectionPort protection;

    public PostgresWebPushSubscriptionAdapter(
            DataSource dataSource, WebPushCredentialProtectionPort protection) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @Override
    public void replace(WebPushSubscriptionRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        try (ProtectedWebPushSubscription protectedSubscription = Objects.requireNonNull(
                protection.protect(registration), "protectedSubscription")) {
            requireSameBinding(registration, protectedSubscription);
            protectedSubscription.withCopies((endpoint, p256dh, auth, lookupTag) -> {
                replaceProtected(protectedSubscription, endpoint, p256dh, auth, lookupTag);
                return null;
            });
        } catch (SQLException exception) {
            throw new NotificationPersistenceException(
                    "Web Push subscription replacement failed", exception);
        }
    }

    @Override
    public boolean delete(UUID accountId, UUID installationId) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(installationId, "installationId");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM chat.web_push_subscription "
                                + "WHERE account_id = ? AND installation_id = ?")) {
            statement.setObject(1, accountId);
            statement.setObject(2, installationId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new NotificationPersistenceException(
                    "Web Push subscription deletion failed", exception);
        }
    }

    private void replaceProtected(
            ProtectedWebPushSubscription subscription,
            byte[] endpoint,
            byte[] p256dh,
            byte[] auth,
            byte[] lookupTag) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                transferEndpointOwnership(connection, subscription, lookupTag);
                upsert(connection, subscription, endpoint, p256dh, auth, lookupTag);
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static void transferEndpointOwnership(
            Connection connection,
            ProtectedWebPushSubscription subscription,
            byte[] lookupTag) throws SQLException {
        String sql = "DELETE FROM chat.web_push_subscription "
                + "WHERE endpoint_lookup_tag = ? "
                + "AND (account_id <> ? OR installation_id <> ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, lookupTag);
            statement.setObject(2, subscription.accountId());
            statement.setObject(3, subscription.installationId());
            statement.executeUpdate();
        }
    }

    private static void upsert(
            Connection connection,
            ProtectedWebPushSubscription subscription,
            byte[] endpoint,
            byte[] p256dh,
            byte[] auth,
            byte[] lookupTag) throws SQLException {
        String sql = """
                INSERT INTO chat.web_push_subscription(
                    account_id, installation_id, endpoint_ciphertext,
                    p256dh_ciphertext, auth_secret_ciphertext, endpoint_lookup_tag,
                    encryption_key_id, browser_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, installation_id) DO UPDATE SET
                    endpoint_ciphertext = EXCLUDED.endpoint_ciphertext,
                    p256dh_ciphertext = EXCLUDED.p256dh_ciphertext,
                    auth_secret_ciphertext = EXCLUDED.auth_secret_ciphertext,
                    endpoint_lookup_tag = EXCLUDED.endpoint_lookup_tag,
                    encryption_key_id = EXCLUDED.encryption_key_id,
                    browser_expires_at = EXCLUDED.browser_expires_at,
                    updated_at = transaction_timestamp()
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, subscription.accountId());
            statement.setObject(2, subscription.installationId());
            statement.setBytes(3, endpoint);
            statement.setBytes(4, p256dh);
            statement.setBytes(5, auth);
            statement.setBytes(6, lookupTag);
            statement.setString(7, subscription.encryptionKeyId());
            if (subscription.browserExpiresAt().isPresent()) {
                statement.setObject(8, OffsetDateTime.ofInstant(
                        subscription.browserExpiresAt().orElseThrow(), ZoneOffset.UTC));
            } else {
                statement.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Web Push subscription replacement changed no row");
            }
        }
    }

    private static void requireSameBinding(
            WebPushSubscriptionRegistration registration,
            ProtectedWebPushSubscription protectedSubscription) {
        if (!registration.accountId().equals(protectedSubscription.accountId())
                || !registration.installationId().equals(
                        protectedSubscription.installationId())
                || !registration.browserExpiresAt().equals(
                        protectedSubscription.browserExpiresAt())) {
            throw new IllegalStateException("protected subscription binding differs");
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}

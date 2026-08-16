package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.notification.ProtectedWebPushSubscription;
import com.fallingnight.chat.application.notification.WebPushCredentialProtectionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import com.fallingnight.chat.application.notification.WebPushSubscriptionReplaceResult;
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
    public static final int MAX_SUBSCRIPTIONS_PER_ACCOUNT = 10;
    private final DataSource dataSource;
    private final WebPushCredentialProtectionPort protection;

    public PostgresWebPushSubscriptionAdapter(
            DataSource dataSource, WebPushCredentialProtectionPort protection) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.protection = Objects.requireNonNull(protection, "protection");
    }

    @Override
    public WebPushSubscriptionReplaceResult replace(
            WebPushSubscriptionRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        try (ProtectedWebPushSubscription protectedSubscription = Objects.requireNonNull(
                protection.protect(registration), "protectedSubscription")) {
            requireSameBinding(registration, protectedSubscription);
            return protectedSubscription.withCopies((endpoint, p256dh, auth, lookupTag) ->
                    replaceProtected(
                            protectedSubscription, endpoint, p256dh, auth, lookupTag));
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

    private WebPushSubscriptionReplaceResult replaceProtected(
            ProtectedWebPushSubscription subscription,
            byte[] endpoint,
            byte[] p256dh,
            byte[] auth,
            byte[] lookupTag) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!lockAvailableAccount(connection, subscription.accountId())) {
                    connection.rollback();
                    return WebPushSubscriptionReplaceResult.ACCOUNT_UNAVAILABLE;
                }
                transferEndpointOwnership(connection, subscription, lookupTag);
                if (wouldExceedAccountLimit(connection, subscription)) {
                    connection.rollback();
                    return WebPushSubscriptionReplaceResult.LIMIT_REACHED;
                }
                upsert(connection, subscription, endpoint, p256dh, auth, lookupTag);
                connection.commit();
                return WebPushSubscriptionReplaceResult.REPLACED;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static boolean lockAvailableAccount(Connection connection, UUID accountId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT disabled_at FROM chat.account WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, accountId);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getObject(1) == null && !result.next();
            }
        }
    }

    private static boolean wouldExceedAccountLimit(
            Connection connection, ProtectedWebPushSubscription subscription)
            throws SQLException {
        String sql = "SELECT count(*), count(*) FILTER (WHERE installation_id = ?) "
                + "FROM chat.web_push_subscription WHERE account_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, subscription.installationId());
            statement.setObject(2, subscription.accountId());
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Web Push quota query returned no row");
                long count = result.getLong(1);
                long existing = result.getLong(2);
                if (result.next()) throw new SQLException("Web Push quota query returned many rows");
                return existing == 0 && count >= MAX_SUBSCRIPTIONS_PER_ACCOUNT;
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

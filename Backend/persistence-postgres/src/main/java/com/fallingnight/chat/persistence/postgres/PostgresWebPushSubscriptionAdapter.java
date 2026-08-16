package com.fallingnight.chat.persistence.postgres;

import com.fallingnight.chat.application.notification.ProtectedWebPushSubscription;
import com.fallingnight.chat.application.notification.ProtectedWebPushSubscriptionBatch;
import com.fallingnight.chat.application.notification.WebPushCredentialProtectionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import com.fallingnight.chat.application.notification.WebPushSubscriptionReplaceResult;
import com.fallingnight.chat.application.notification.WebPushProtectedSubscriptionPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Ciphertext-only PostgreSQL subscription replacement and account/install erasure. */
public final class PostgresWebPushSubscriptionAdapter
        implements WebPushSubscriptionPort, WebPushProtectedSubscriptionPort {
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

    @Override
    public ProtectedWebPushSubscriptionBatch loadActive(
            UUID accountId, Instant observedAt) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(observedAt, "observedAt");
        String sql = """
                SELECT subscription.installation_id,
                       subscription.endpoint_ciphertext,
                       subscription.p256dh_ciphertext,
                       subscription.auth_secret_ciphertext,
                       subscription.endpoint_lookup_tag,
                       subscription.encryption_key_id,
                       subscription.browser_expires_at
                FROM chat.web_push_subscription subscription
                JOIN chat.account account ON account.id = subscription.account_id
                  AND account.disabled_at IS NULL
                WHERE subscription.account_id = ?
                  AND (subscription.browser_expires_at IS NULL
                       OR subscription.browser_expires_at > ?)
                ORDER BY subscription.installation_id
                LIMIT ?
                """;
        List<ProtectedWebPushSubscription> subscriptions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, accountId);
            statement.setObject(2, OffsetDateTime.ofInstant(observedAt, ZoneOffset.UTC));
            statement.setInt(3, ProtectedWebPushSubscriptionBatch.MAX_SUBSCRIPTIONS + 1);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    subscriptions.add(readProtected(accountId, result));
                }
            }
            if (subscriptions.size()
                    > ProtectedWebPushSubscriptionBatch.MAX_SUBSCRIPTIONS) {
                closeAll(subscriptions);
                throw new NotificationPersistenceException(
                        "Web Push subscription quota invariant violated",
                        new IllegalStateException("too many active subscriptions"));
            }
            return new ProtectedWebPushSubscriptionBatch(accountId, subscriptions);
        } catch (SQLException | RuntimeException exception) {
            closeAll(subscriptions);
            if (exception instanceof NotificationPersistenceException persistence) {
                throw persistence;
            }
            throw new NotificationPersistenceException(
                    "Web Push protected subscription load failed", exception);
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

    private static void closeAll(List<ProtectedWebPushSubscription> subscriptions) {
        subscriptions.forEach(ProtectedWebPushSubscription::close);
    }

    private static ProtectedWebPushSubscription readProtected(
            UUID accountId, java.sql.ResultSet result) throws SQLException {
        byte[] endpoint = result.getBytes(2);
        byte[] p256dh = result.getBytes(3);
        byte[] auth = result.getBytes(4);
        byte[] lookupTag = result.getBytes(5);
        try {
            return ProtectedWebPushSubscription.copyOf(
                    accountId,
                    result.getObject(1, UUID.class),
                    java.util.Optional.ofNullable(result.getObject(7, OffsetDateTime.class))
                            .map(OffsetDateTime::toInstant),
                    result.getString(6), endpoint, p256dh, auth, lookupTag);
        } finally {
            Arrays.fill(endpoint, (byte) 0);
            Arrays.fill(p256dh, (byte) 0);
            Arrays.fill(auth, (byte) 0);
            Arrays.fill(lookupTag, (byte) 0);
        }
    }
}

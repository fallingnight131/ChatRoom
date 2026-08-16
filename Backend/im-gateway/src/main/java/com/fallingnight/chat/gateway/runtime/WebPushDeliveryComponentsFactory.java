package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.application.notification.WebPushCredentialUnprotectionPort;
import com.fallingnight.chat.application.notification.WebPushDeliveryPolicy;
import com.fallingnight.chat.application.notification.WebPushDeliveryWorkerService;
import com.fallingnight.chat.application.notification.WebPushProtectedSubscriptionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionPort;
import com.fallingnight.chat.gateway.operations.ExactWebPushProviderOriginPolicy;
import com.fallingnight.chat.gateway.operations.ExponentialWebPushRetrySchedule;
import com.fallingnight.chat.gateway.operations.RfcWebPushProviderAdapter;
import com.fallingnight.chat.gateway.operations.WebPushDeliveryLoopTelemetry;
import com.fallingnight.chat.gateway.operations.WebPushDeliveryReadinessProbe;
import com.fallingnight.chat.gateway.operations.WebPushDeliveryRuntime;
import com.fallingnight.chat.gateway.operations.WebPushWorkerTelemetry;
import com.fallingnight.chat.identity.crypto.FileRfc8292VapidKeyCustody;
import com.fallingnight.chat.identity.crypto.Rfc8291WebPushPayloadEncoder;
import com.fallingnight.chat.persistence.postgres.PostgresWebPushOutboxAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresWebPushRecipientPolicyAdapter;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Composes the complete delivery graph while leaving activation to its lifecycle owner. */
public final class WebPushDeliveryComponentsFactory {
    private WebPushDeliveryComponentsFactory() { }

    public static Optional<WebPushDeliveryComponents> create(
            WebPushDeliveryRuntimeConfig config,
            DataSource dataSource,
            WebPushCredentialUnprotectionPort unprotection,
            WebPushProtectedSubscriptionPort protectedSubscriptions,
            WebPushSubscriptionPort subscriptionMutations,
            Clock clock) {
        Objects.requireNonNull(config, "config");
        if (!config.enabled()) return Optional.empty();
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(unprotection, "unprotection");
        Objects.requireNonNull(protectedSubscriptions, "protectedSubscriptions");
        Objects.requireNonNull(subscriptionMutations, "subscriptionMutations");
        Objects.requireNonNull(clock, "clock");

        FileRfc8292VapidKeyCustody custody = FileRfc8292VapidKeyCustody.load(
                config.vapidPrivateKeyFile(), config.vapidPublicKeyFile(),
                config.vapidSubject(), clock, config.vapidTokenLifetime());
        WebPushDeliveryRuntime runtime = null;
        try {
            var provider = RfcWebPushProviderAdapter.createJdk(
                    new Rfc8291WebPushPayloadEncoder(), custody::sign,
                    new ExactWebPushProviderOriginPolicy(config.providerOrigins()), clock);
            var outbox = new PostgresWebPushOutboxAdapter(dataSource);
            var workerTelemetry = new WebPushWorkerTelemetry();
            var service = new WebPushDeliveryWorkerService(
                    new WebPushDeliveryPolicy(true),
                    new PostgresWebPushRecipientPolicyAdapter(dataSource),
                    protectedSubscriptions,
                    unprotection,
                    provider,
                    subscriptionMutations,
                    outbox,
                    new ExponentialWebPushRetrySchedule(),
                    workerTelemetry);
            var loopTelemetry = new WebPushDeliveryLoopTelemetry();
            WebPushDeliveryRuntime createdRuntime = new WebPushDeliveryRuntime(
                    outbox, service, clock, UUID.randomUUID(), config.lease(),
                    config.batchSize(), config.backoff(), loopTelemetry,
                    config.shutdownTimeout());
            runtime = createdRuntime;
            var readiness = new WebPushDeliveryReadinessProbe(
                    createdRuntime::running, outbox, loopTelemetry::snapshot,
                    config.readinessPolicy(), clock);
            return Optional.of(new WebPushDeliveryComponents(
                    createdRuntime, custody, readiness, outbox, loopTelemetry,
                    workerTelemetry, clock));
        } catch (RuntimeException exception) {
            closeAfterFailure(runtime, custody, exception);
            throw exception;
        }
    }

    private static void closeAfterFailure(
            AutoCloseable runtime,
            AutoCloseable custody,
            RuntimeException failure) {
        close(runtime, failure);
        close(custody, failure);
    }

    private static void close(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception cleanup) {
            failure.addSuppressed(cleanup);
        }
    }
}

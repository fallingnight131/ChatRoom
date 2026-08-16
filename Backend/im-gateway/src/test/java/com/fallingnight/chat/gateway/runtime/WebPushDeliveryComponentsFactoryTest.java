package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.notification.WebPushSubscriptionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import com.fallingnight.chat.application.notification.WebPushSubscriptionReplaceResult;
import com.fallingnight.chat.gateway.operations.WebPushDeliveryReadiness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.jupiter.api.Test;

final class WebPushDeliveryComponentsFactoryTest {
    @TempDir
    Path temporary;

    @Test
    void disabledConfigurationBuildsNothingAndTouchesNoDependencies() {
        WebPushDeliveryRuntimeConfig config =
                WebPushDeliveryRuntimeConfig.fromEnvironment(Map.of(), false);

        assertTrue(WebPushDeliveryComponentsFactory.create(
                config, null, null, null, null, null).isEmpty());
    }

    @Test
    void composesProtectedProviderGraphWithoutStartingIt() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        Path privateKey = writeProtected("vapid-private.der", pair.getPrivate().getEncoded());
        Path publicKey = writeProtected("vapid-public.der", pair.getPublic().getEncoded());
        Map<String, String> environment = new HashMap<>();
        environment.put(WebPushDeliveryRuntimeConfig.ENABLED, "true");
        environment.put(WebPushDeliveryRuntimeConfig.VAPID_PRIVATE_KEY, privateKey.toString());
        environment.put(WebPushDeliveryRuntimeConfig.VAPID_PUBLIC_KEY, publicKey.toString());
        environment.put(WebPushDeliveryRuntimeConfig.VAPID_SUBJECT,
                "mailto:push@example.com");
        environment.put(WebPushDeliveryRuntimeConfig.PROVIDER_ORIGINS,
                "https://push.example");
        WebPushDeliveryRuntimeConfig config =
                WebPushDeliveryRuntimeConfig.fromEnvironment(environment, true);
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl("jdbc:postgresql://127.0.0.1:1/unreachable");
        WebPushSubscriptionPort mutations = new WebPushSubscriptionPort() {
            @Override
            public WebPushSubscriptionReplaceResult replace(
                    WebPushSubscriptionRegistration registration) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean delete(UUID accountId, UUID installationId) {
                throw new UnsupportedOperationException();
            }
        };

        WebPushDeliveryComponents components = WebPushDeliveryComponentsFactory.create(
                config, dataSource,
                subscription -> { throw new UnsupportedOperationException(); },
                (account, observedAt) -> { throw new UnsupportedOperationException(); },
                mutations, Clock.systemUTC()).orElseThrow();
        try {
            assertTrue(components.readiness().reason()
                    == WebPushDeliveryReadiness.Reason.STOPPED);
            assertTrue(components.metrics().contains(
                    "chat_gateway_web_push_delivery_reason_stopped 1\n"));
        } finally {
            components.close();
        }
    }

    private Path writeProtected(String name, byte[] bytes) throws Exception {
        Path path = temporary.resolve(name);
        Files.write(path, bytes);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        return path;
    }
}

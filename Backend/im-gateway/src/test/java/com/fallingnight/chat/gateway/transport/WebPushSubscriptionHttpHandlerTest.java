package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.notification.WebPushDeliveryPolicy;
import com.fallingnight.chat.application.notification.WebPushSubscriptionAdmissionDecision;
import com.fallingnight.chat.application.notification.WebPushSubscriptionMutationService;
import com.fallingnight.chat.application.notification.WebPushSubscriptionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import com.fallingnight.chat.application.notification.WebPushSubscriptionReplaceResult;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WebPushSubscriptionHttpHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final String ORIGIN = "https://chat.example";
    private static final String BEARER = "a".repeat(32);
    private static final String CSRF = "b".repeat(32);

    @Test
    void authenticatesAndReplacesOffEventLoopWithoutTrustingAccountJson() {
        UUID account = UUID.randomUUID(); UUID session = UUID.randomUUID();
        AtomicReference<UUID> persistedAccount = new AtomicReference<>();
        AtomicReference<byte[]> observedBearer = new AtomicReference<>();
        WebPushHttpTelemetry telemetry = new WebPushHttpTelemetry();
        WebPushSubscriptionMutationService mutations = service(new WebPushSubscriptionPort() {
            @Override public WebPushSubscriptionReplaceResult replace(
                    WebPushSubscriptionRegistration registration) {
                persistedAccount.set(registration.accountId());
                return WebPushSubscriptionReplaceResult.REPLACED;
            }
            @Override public boolean delete(UUID accountId, UUID installationId) { return false; }
        });
        var handler = new WebPushSubscriptionHttpHandler(
                WebPushHttpApiPolicy.enabled(Set.of(ORIGIN)),
                (bearer, csrf, observedAt) -> {
                    observedBearer.set(bearer);
                    assertEquals(NOW, observedAt);
                    return new WebPushHttpAuthenticationResult.Authenticated(
                            new WebPushHttpActor(account, session));
                }, mutations, new WebPushSubscriptionJsonCodec(),
                Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run, telemetry);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        try {
            channel.writeInbound(request(HttpMethod.PUT, UUID.randomUUID(), subscriptionJson()));
            channel.runPendingTasks();
            FullHttpResponse response = channel.readOutbound();
            assertEquals(HttpResponseStatus.NO_CONTENT, response.status()); response.release();
            assertEquals(account, persistedAccount.get());
            assertTrue(allZero(observedBearer.get()));
            assertEquals(1, telemetry.count(WebPushHttpOutcome.REPLACED));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void rejectsCsrfBeforeMutationAndMapsItToForbidden() {
        AtomicInteger mutations = new AtomicInteger();
        WebPushHttpTelemetry telemetry = new WebPushHttpTelemetry();
        EmbeddedChannel channel = new EmbeddedChannel(new WebPushSubscriptionHttpHandler(
                WebPushHttpApiPolicy.enabled(Set.of(ORIGIN)),
                (bearer, csrf, at) -> WebPushHttpAuthenticationResult.Rejected.INVALID_CSRF,
                service(countingPort(mutations)), new WebPushSubscriptionJsonCodec(),
                Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run, telemetry));
        try {
            channel.writeInbound(request(HttpMethod.DELETE, UUID.randomUUID(), new byte[0]));
            channel.runPendingTasks();
            FullHttpResponse response = channel.readOutbound();
            assertEquals(HttpResponseStatus.FORBIDDEN, response.status()); response.release();
            assertEquals(0, mutations.get());
            assertEquals(1, telemetry.count(WebPushHttpOutcome.INVALID_CSRF));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void defaultOffAndOriginRejectionDoNotCallSessionBoundary() {
        AtomicInteger sessions = new AtomicInteger();
        WebPushHttpSessionPort session = (bearer, csrf, at) -> {
            sessions.incrementAndGet(); return WebPushHttpAuthenticationResult.Rejected.INVALID_SESSION;
        };
        EmbeddedChannel disabled = new EmbeddedChannel(new WebPushSubscriptionHttpHandler(
                WebPushHttpApiPolicy.DISABLED, session, service(countingPort(new AtomicInteger())),
                new WebPushSubscriptionJsonCodec(), Clock.systemUTC(), Runnable::run,
                WebPushHttpEventSink.NOOP));
        try {
            disabled.writeInbound(request(HttpMethod.DELETE, UUID.randomUUID(), new byte[0]));
            FullHttpResponse response = disabled.readOutbound();
            assertEquals(HttpResponseStatus.NOT_FOUND, response.status()); response.release();
        } finally { disabled.finishAndReleaseAll(); }
        assertEquals(0, sessions.get());

        EmbeddedChannel wrongOrigin = new EmbeddedChannel(new WebPushSubscriptionHttpHandler(
                WebPushHttpApiPolicy.enabled(Set.of(ORIGIN)), session,
                service(countingPort(new AtomicInteger())), new WebPushSubscriptionJsonCodec(),
                Clock.systemUTC(), Runnable::run, WebPushHttpEventSink.NOOP));
        try {
            DefaultFullHttpRequest request = request(
                    HttpMethod.DELETE, UUID.randomUUID(), new byte[0]);
            request.headers().set(HttpHeaderNames.ORIGIN, "https://evil.example");
            wrongOrigin.writeInbound(request);
            FullHttpResponse response = wrongOrigin.readOutbound();
            assertEquals(HttpResponseStatus.FORBIDDEN, response.status()); response.release();
        } finally { wrongOrigin.finishAndReleaseAll(); }
        assertEquals(0, sessions.get());
    }

    private static WebPushSubscriptionMutationService service(WebPushSubscriptionPort port) {
        return new WebPushSubscriptionMutationService(
                new WebPushDeliveryPolicy(true),
                (account, installation, action, at) ->
                        WebPushSubscriptionAdmissionDecision.Allowed.INSTANCE,
                port, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WebPushSubscriptionPort countingPort(AtomicInteger calls) {
        return new WebPushSubscriptionPort() {
            @Override public WebPushSubscriptionReplaceResult replace(
                    WebPushSubscriptionRegistration registration) {
                calls.incrementAndGet(); return WebPushSubscriptionReplaceResult.REPLACED;
            }
            @Override public boolean delete(UUID accountId, UUID installationId) {
                calls.incrementAndGet(); return true;
            }
        };
    }

    private static DefaultFullHttpRequest request(
            HttpMethod method, UUID installation, byte[] body) {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method,
                WebPushSubscriptionHttpHandler.PATH_PREFIX + installation,
                Unpooled.wrappedBuffer(body));
        request.headers().set(HttpHeaderNames.ORIGIN, ORIGIN);
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + BEARER);
        request.headers().set(WebPushSubscriptionHttpHandler.CSRF_HEADER, CSRF);
        if (method == HttpMethod.PUT)
            request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        return request;
    }

    private static byte[] subscriptionJson() {
        byte[] p256dh = new byte[65]; p256dh[0] = 0x04;
        byte[] auth = new byte[16]; Arrays.fill(auth, (byte) 7);
        String json = "{\"endpoint\":\"https://push.example/sub/opaque\","
                + "\"expirationTime\":null,\"keys\":{\"p256dh\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(p256dh)
                + "\",\"auth\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(auth) + "\"}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) if (item != 0) return false;
        return true;
    }
}

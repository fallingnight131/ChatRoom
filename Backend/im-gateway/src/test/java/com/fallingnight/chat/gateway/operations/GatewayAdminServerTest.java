package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.gateway.transport.AuthenticationTelemetry;
import com.fallingnight.chat.gateway.transport.MessagingTelemetry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class GatewayAdminServerTest {
    @Test
    void servesLoopbackLivenessReadinessAndMetricsWithSafeHeaders() throws Exception {
        GatewayReadiness readiness = new GatewayReadiness();
        AuthenticationTelemetry telemetry = new AuthenticationTelemetry();
        MessagingTelemetry messaging = new MessagingTelemetry();
        telemetry.accepted(false);
        messaging.accepted(true);
        try (GatewayAdminServer server = new GatewayAdminServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                1,
                telemetry,
                messaging,
                () -> 2,
                () -> 3,
                readiness)) {
            server.start();
            assertResponse(server, "/health/live", 200, "live\n");
            assertResponse(server, "/health/ready", 503, "not_ready\n");
            readiness.markReady();
            assertResponse(server, "/health/ready", 200, "ready\n");
            HttpResponse<String> metrics = get(server, "/metrics");
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains(
                    "chat_gateway_authentication_total{outcome=\"accepted\"} 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_messaging_total{outcome=\"duplicate\"} 1"));
            assertTrue(metrics.body().contains("chat_gateway_messaging_workers_active 2"));
            assertTrue(metrics.body().contains("chat_gateway_messaging_queue_size 3"));
            assertEquals("no-store", metrics.headers()
                    .firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", metrics.headers()
                    .firstValue("X-Content-Type-Options").orElseThrow());

            HttpRequest post = HttpRequest.newBuilder(uri(server, "/metrics"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            assertEquals(405, HttpClient.newHttpClient()
                    .send(post, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertResponse(server, "/metrics/extra", 404, "");
        }
    }

    @Test
    void refusesWildcardOrExcessWorkerConfiguration() throws Exception {
        AuthenticationTelemetry telemetry = new AuthenticationTelemetry();
        GatewayReadiness readiness = new GatewayReadiness();
        assertThrows(IllegalArgumentException.class, () -> new GatewayAdminServer(
                new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0),
                1,
                telemetry,
                new MessagingTelemetry(),
                () -> 0,
                () -> 0,
                readiness));
        assertThrows(IllegalArgumentException.class, () -> new GatewayAdminServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                5,
                telemetry,
                new MessagingTelemetry(),
                () -> 0,
                () -> 0,
                readiness));
    }

    private static void assertResponse(
            GatewayAdminServer server,
            String path,
            int status,
            String body) throws Exception {
        HttpResponse<String> response = get(server, path);
        assertEquals(status, response.statusCode());
        assertEquals(body, response.body());
    }

    private static HttpResponse<String> get(GatewayAdminServer server, String path)
            throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri(server, path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(GatewayAdminServer server, String path) throws Exception {
        InetSocketAddress address = server.address();
        return new URI(
                "http",
                null,
                address.getAddress().getHostAddress(),
                address.getPort(),
                path,
                null,
                null);
    }
}

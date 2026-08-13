package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.attachment.AttachmentCleanupReport;
import com.fallingnight.chat.gateway.transport.AuthenticationTelemetry;
import com.fallingnight.chat.gateway.transport.MessagingTelemetry;
import com.fallingnight.chat.gateway.transport.DeviceManagementTelemetry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GatewayAdminServerTest {
    @Test
    void servesLoopbackLivenessReadinessAndMetricsWithSafeHeaders() throws Exception {
        GatewayReadiness readiness = new GatewayReadiness();
        AuthenticationTelemetry telemetry = new AuthenticationTelemetry();
        MessagingTelemetry messaging = new MessagingTelemetry();
        DeviceManagementTelemetry devices = new DeviceManagementTelemetry();
        AttachmentCleanupTelemetry cleanup = new AttachmentCleanupTelemetry();
        telemetry.accepted(false);
        messaging.accepted(true);
        messaging.livePublished(2);
        messaging.liveSlowConsumerClosed(1);
        messaging.liveSlowConsumerBacklog(196_608);
        messaging.editApplied(true, false);
        messaging.forwardAccepted(false);
        messaging.forwardAccepted(true);
        devices.revoked(true);
        devices.disconnected(2);
        cleanup.completed(
                new AttachmentCleanupReport(1, 1, 1, 0, 0),
                0,
                Duration.ofSeconds(60));
        try (GatewayAdminServer server = new GatewayAdminServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                1,
                telemetry,
                messaging,
                devices,
                cleanup,
                () -> 4,
                () -> 5,
                () -> 2,
                () -> 3,
                () -> new PostgresPoolSnapshot(true, 2, 1, 3, 4, 8),
                () -> new EventLoopSnapshot(true, 4, 12, 2_000_000, 7_000_000, 3),
                readiness,
                () -> "# TYPE chat_gateway_distributed_metrics_available gauge\n"
                        + "chat_gateway_distributed_metrics_available 1\n",
                new GatewayReleaseIdentity(
                        "1.2.3", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 2, 1))) {
            server.start();
            assertResponse(server, "/health/live", 200, "live\n");
            HttpResponse<String> identity = get(server, "/identity");
            assertEquals(200, identity.statusCode());
            assertEquals("{\"schemaVersion\":1,\"releaseVersion\":\"1.2.3\","
                    + "\"sourceRevision\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                    + "\"protocolVersion\":2,\"compatibilityEpoch\":1}\n",
                    identity.body());
            assertEquals("application/json; charset=utf-8", identity.headers()
                    .firstValue("Content-Type").orElseThrow());
            assertEquals("no-store", identity.headers()
                    .firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", identity.headers()
                    .firstValue("X-Content-Type-Options").orElseThrow());
            assertResponse(server, "/health/ready", 503, "not_ready\n");
            readiness.markReady();
            assertResponse(server, "/health/ready", 200, "ready\n");
            HttpResponse<String> metrics = get(server, "/metrics");
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains(
                    "chat_gateway_authentication_total{outcome=\"accepted\"} 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_authentication_workers_active 4"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_authentication_queue_size 5"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_release_info{release_version=\"1.2.3\","
                            + "source_revision=\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
                            + "protocol_version=\"2\",compatibility_epoch=\"1\"} 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_messaging_total{outcome=\"duplicate\"} 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_messaging_total{outcome=\"live_published\"} 2"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_messaging_total{outcome=\"live_slow_consumer_closed\"} 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_messaging_slow_consumer_maximum_bytes_before_writable 196608"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_messaging_total{outcome=\"edit_changed\"} 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_messaging_total{outcome=\"forward_accepted\"} 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_messaging_total{outcome=\"forward_duplicate\"} 1"));
            assertTrue(metrics.body().contains("chat_gateway_messaging_workers_active 2"));
            assertTrue(metrics.body().contains("chat_gateway_messaging_queue_size 3"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_postgres_pool_metrics_available 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_postgres_connections_active 2"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_postgres_connections_idle 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_postgres_connections_total 3"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_postgres_threads_awaiting_connection 4"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_postgres_connections_maximum 8"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_event_loop_metrics_available 1"));
            assertTrue(metrics.body().contains("chat_gateway_event_loop_workers 4"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_event_loop_probe_samples_total 12"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_event_loop_latest_max_lag_seconds 0.002000000"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_event_loop_max_lag_seconds 0.007000000"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_event_loop_pending_tasks 3"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_device_management_total{outcome=\"revoked\"} 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_device_management_total{outcome=\"disconnected\"} 2"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_attachment_cleanup_total{outcome=\"deleted\"} 1"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_attachment_cleanup_next_delay_seconds 60"));
            assertTrue(metrics.body().contains(
                    "chat_gateway_distributed_metrics_available 1"));
            assertEquals("no-store", metrics.headers()
                    .firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", metrics.headers()
                    .firstValue("X-Content-Type-Options").orElseThrow());

            HttpRequest post = HttpRequest.newBuilder(uri(server, "/metrics"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            assertEquals(405, HttpClient.newHttpClient()
                    .send(post, HttpResponse.BodyHandlers.discarding()).statusCode());
            HttpRequest identityPost = HttpRequest.newBuilder(uri(server, "/identity"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            assertEquals(405, HttpClient.newHttpClient()
                    .send(identityPost, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertResponse(server, "/metrics/extra", 404, "");
            assertResponse(server, "/identity/extra", 404, "");
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
                new DeviceManagementTelemetry(),
                new AttachmentCleanupTelemetry(),
                () -> 0,
                () -> 0,
                () -> 0,
                () -> 0,
                () -> PostgresPoolSnapshot.unavailable(8),
                EventLoopSnapshot::unavailable,
                readiness));
        assertThrows(IllegalArgumentException.class, () -> new GatewayAdminServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                5,
                telemetry,
                new MessagingTelemetry(),
                new DeviceManagementTelemetry(),
                new AttachmentCleanupTelemetry(),
                () -> 0,
                () -> 0,
                () -> 0,
                () -> 0,
                () -> PostgresPoolSnapshot.unavailable(8),
                EventLoopSnapshot::unavailable,
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

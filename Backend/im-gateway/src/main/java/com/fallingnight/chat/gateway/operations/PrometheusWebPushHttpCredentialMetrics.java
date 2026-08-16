package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.gateway.transport.WebPushHttpCredentialTelemetrySnapshot;
import java.util.Objects;

/** Renders fixed-name Web Push credential counters without identity labels. */
public final class PrometheusWebPushHttpCredentialMetrics {
    private PrometheusWebPushHttpCredentialMetrics() { }

    public static String render(WebPushHttpCredentialTelemetrySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return "# TYPE chat_gateway_web_push_http_credentials_issued_total counter\n"
                + "chat_gateway_web_push_http_credentials_issued_total " + snapshot.issued() + "\n"
                + "# TYPE chat_gateway_web_push_http_credentials_denied_total counter\n"
                + "chat_gateway_web_push_http_credentials_denied_total " + snapshot.denied() + "\n"
                + "# TYPE chat_gateway_web_push_http_credentials_saturated_total counter\n"
                + "chat_gateway_web_push_http_credentials_saturated_total "
                + snapshot.saturated() + "\n"
                + "# TYPE chat_gateway_web_push_http_credentials_failed_total counter\n"
                + "chat_gateway_web_push_http_credentials_failed_total " + snapshot.failed() + "\n";
    }
}

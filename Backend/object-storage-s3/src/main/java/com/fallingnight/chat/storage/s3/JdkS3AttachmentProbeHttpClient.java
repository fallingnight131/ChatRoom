package com.fallingnight.chat.storage.s3;

import com.fallingnight.chat.storage.s3.S3AttachmentCapabilityProbe.ProbeHttpClient;
import com.fallingnight.chat.storage.s3.S3AttachmentCapabilityProbe.ProbeHttpResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** JDK-only probe transport which never includes a signed URL in an exception. */
public final class JdkS3AttachmentProbeHttpClient implements ProbeHttpClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private final HttpClient client;

    public JdkS3AttachmentProbeHttpClient(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public static JdkS3AttachmentProbeHttpClient create() {
        return new JdkS3AttachmentProbeHttpClient(HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    @Override
    public ProbeHttpResponse preflight(
            URI uploadUri, URI webOrigin, Set<String> requestedHeaders) {
        HttpRequest request = HttpRequest.newBuilder(uploadUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Origin", webOrigin.toString())
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", String.join(",", requestedHeaders))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request);
    }

    @Override
    public ProbeHttpResponse put(
            URI uploadUri,
            URI webOrigin,
            Map<String, String> requiredHeaders,
            byte[] payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uploadUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Origin", webOrigin.toString());
        requiredHeaders.forEach(builder::header);
        return send(builder.PUT(HttpRequest.BodyPublishers.ofByteArray(payload)).build());
    }

    private ProbeHttpResponse send(HttpRequest request) {
        try {
            HttpResponse<Void> response = client.send(
                    request, HttpResponse.BodyHandlers.discarding());
            LinkedHashMap<String, List<String>> corsHeaders = new LinkedHashMap<>();
            copyHeader(response, corsHeaders, "access-control-allow-origin");
            copyHeader(response, corsHeaders, "access-control-allow-methods");
            copyHeader(response, corsHeaders, "access-control-allow-headers");
            return new ProbeHttpResponse(response.statusCode(), corsHeaders);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AttachmentObjectStoreCapabilityProbeException(
                    "attachment provider HTTP request was interrupted");
        } catch (IOException | RuntimeException exception) {
            throw new AttachmentObjectStoreCapabilityProbeException(
                    "attachment provider HTTP request failed");
        }
    }

    private static void copyHeader(
            HttpResponse<Void> response,
            Map<String, List<String>> target,
            String name) {
        List<String> values = response.headers().allValues(name);
        if (!values.isEmpty()) {
            target.put(name, values);
        }
    }
}

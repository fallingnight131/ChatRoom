package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.notification.WebPushProviderCommand;
import com.fallingnight.chat.application.notification.WebPushProviderPort;
import com.fallingnight.chat.application.notification.WebPushProviderResult;
import com.fallingnight.chat.identity.crypto.Rfc8291WebPushPayloadEncoder;
import com.fallingnight.chat.identity.crypto.Rfc8292VapidAuthorization;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** RFC 8030/8291/8292 provider adapter. It never exposes response bodies or endpoint diagnostics. */
public final class RfcWebPushProviderAdapter implements WebPushProviderPort {
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_TTL_SECONDS = Duration.ofHours(24).toSeconds();

    @FunctionalInterface
    public interface Transport {
        int post(URI endpoint, byte[] authorizationAscii, byte[] encryptedBody, long ttlSeconds)
                throws IOException, InterruptedException;
    }

    private final Rfc8291WebPushPayloadEncoder encoder;
    private final Function<URI, Rfc8292VapidAuthorization> authorization;
    private final Predicate<URI> endpointPolicy;
    private final Transport transport;
    private final Clock clock;

    public RfcWebPushProviderAdapter(
            Rfc8291WebPushPayloadEncoder encoder,
            Function<URI, Rfc8292VapidAuthorization> authorization,
            Predicate<URI> endpointPolicy,
            Transport transport,
            Clock clock) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.endpointPolicy = Objects.requireNonNull(endpointPolicy, "endpointPolicy");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static RfcWebPushProviderAdapter createJdk(
            Rfc8291WebPushPayloadEncoder encoder,
            Function<URI, Rfc8292VapidAuthorization> authorization,
            Predicate<URI> endpointPolicy,
            Clock clock) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new RfcWebPushProviderAdapter(encoder, authorization, endpointPolicy,
                (endpoint, auth, body, ttl) -> send(client, endpoint, auth, body, ttl), clock);
    }

    @Override
    public WebPushProviderResult deliver(WebPushProviderCommand command) {
        Objects.requireNonNull(command, "command");
        long ttl = Math.min(MAX_TTL_SECONDS,
                command.expiresAt().getEpochSecond() - clock.instant().getEpochSecond());
        if (ttl <= 0) return WebPushProviderResult.TRANSIENT_FAILURE;
        URI endpoint;
        try { endpoint = command.registration().withEndpointCopy(RfcWebPushProviderAdapter::endpoint); }
        catch (RuntimeException exception) { return WebPushProviderResult.INVALID_SUBSCRIPTION; }
        try {
            if (!endpointPolicy.test(endpoint)) return WebPushProviderResult.INVALID_SUBSCRIPTION;
        } catch (RuntimeException exception) {
            return WebPushProviderResult.INVALID_SUBSCRIPTION;
        }
        byte[] payload = payload(command);
        try {
            return command.registration().withP256dhCopy(p256dh ->
                    command.registration().withAuthSecretCopy(authSecret ->
                            deliver(endpoint, payload, p256dh, authSecret, ttl)));
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private WebPushProviderResult deliver(
            URI endpoint, byte[] payload, byte[] p256dh, byte[] authSecret, long ttl) {
        byte[] encrypted;
        try { encrypted = encoder.encode(payload, p256dh, authSecret); }
        catch (IllegalArgumentException exception) { return WebPushProviderResult.INVALID_SUBSCRIPTION; }
        catch (RuntimeException exception) { return WebPushProviderResult.TRANSIENT_FAILURE; }
        try (Rfc8292VapidAuthorization owned = authorization.apply(endpoint)) {
            int status = owned.withAsciiCopy(value -> {
                try { return transport.post(endpoint, value, encrypted, ttl); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return 599;
                } catch (IOException | RuntimeException exception) { return 599; }
            });
            return classify(status);
        } catch (RuntimeException exception) {
            return WebPushProviderResult.AUTHENTICATION_FAILURE;
        } finally {
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    private static int send(
            HttpClient client, URI endpoint, byte[] authorizationAscii, byte[] body, long ttl)
            throws IOException, InterruptedException {
        String authorization = new String(authorizationAscii, StandardCharsets.US_ASCII);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorization)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", Long.toString(ttl))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static WebPushProviderResult classify(int status) {
        if (status == 201 || status == 202) return WebPushProviderResult.DELIVERED;
        if (status == 404 || status == 410) return WebPushProviderResult.INVALID_SUBSCRIPTION;
        if (status == 401 || status == 403) return WebPushProviderResult.AUTHENTICATION_FAILURE;
        return WebPushProviderResult.TRANSIENT_FAILURE;
    }

    private static URI endpoint(byte[] bytes) {
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("invalid Web Push endpoint", exception);
        }
        URI endpoint = URI.create(value);
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null
                || !endpoint.toASCIIString().equals(value)) {
            throw new IllegalArgumentException("invalid Web Push endpoint");
        }
        return endpoint;
    }

    private static byte[] payload(WebPushProviderCommand command) {
        String json = "{\"version\":1,\"notificationId\":\"" + command.notificationId()
                + "\",\"conversationId\":\"" + command.conversationId()
                + "\",\"messageId\":\"" + command.messageId()
                + "\",\"mentioned\":" + command.mentioned() + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}

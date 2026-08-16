package com.fallingnight.chat.application.notification;

import com.fallingnight.chat.application.security.SecretBytes;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** Owns one browser subscription's sensitive endpoint and application-server keys. */
public final class WebPushSubscriptionCredentials implements AutoCloseable {
    public static final int MAX_ENDPOINT_BYTES = 2_048;
    public static final int P256DH_BYTES = 65;
    public static final int AUTH_SECRET_BYTES = 16;

    private final SecretBytes endpoint;
    private final SecretBytes p256dh;
    private final SecretBytes authSecret;

    private WebPushSubscriptionCredentials(
            SecretBytes endpoint, SecretBytes p256dh, SecretBytes authSecret) {
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.authSecret = authSecret;
    }

    public static WebPushSubscriptionCredentials copyOf(
            byte[] endpoint, byte[] p256dh, byte[] authSecret) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(p256dh, "p256dh");
        Objects.requireNonNull(authSecret, "authSecret");
        validateEndpoint(endpoint);
        if (p256dh.length != P256DH_BYTES || p256dh[0] != 0x04) {
            throw new IllegalArgumentException(
                    "p256dh must be one uncompressed P-256 public key");
        }
        if (authSecret.length != AUTH_SECRET_BYTES) {
            throw new IllegalArgumentException("authSecret must contain exactly 16 bytes");
        }
        return new WebPushSubscriptionCredentials(
                SecretBytes.copyOf(endpoint),
                SecretBytes.copyOf(p256dh),
                SecretBytes.copyOf(authSecret));
    }

    public <T> T withEndpointCopy(Function<byte[], T> action) {
        return endpoint.withCopy(action);
    }

    public <T> T withP256dhCopy(Function<byte[], T> action) {
        return p256dh.withCopy(action);
    }

    public <T> T withAuthSecretCopy(Function<byte[], T> action) {
        return authSecret.withCopy(action);
    }

    public boolean isClosed() {
        return endpoint.isClosed() && p256dh.isClosed() && authSecret.isClosed();
    }

    @Override
    public void close() {
        endpoint.close();
        p256dh.close();
        authSecret.close();
    }

    @Override
    public String toString() {
        return "WebPushSubscriptionCredentials[REDACTED]";
    }

    private static void validateEndpoint(byte[] encodedEndpoint) {
        if (encodedEndpoint.length < 1 || encodedEndpoint.length > MAX_ENDPOINT_BYTES) {
            throw new IllegalArgumentException("endpoint ASCII length must be 1..2048");
        }
        CharBuffer decoded = null;
        try {
            decoded = StandardCharsets.US_ASCII.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encodedEndpoint));
            String value = decoded.toString();
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme())
                    || host == null
                    || host.isBlank()
                    || !host.equals(host.toLowerCase(Locale.ROOT))
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() == 443
                    || !value.equals(uri.normalize().toASCIIString())) {
                throw new IllegalArgumentException("endpoint must be a canonical HTTPS URI");
            }
        } catch (URISyntaxException | java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("endpoint must be a canonical HTTPS URI", exception);
        } finally {
            if (decoded != null && !decoded.isReadOnly()) {
                for (int index = 0; index < decoded.limit(); index++) {
                    decoded.put(index, '\0');
                }
            }
        }
    }
}

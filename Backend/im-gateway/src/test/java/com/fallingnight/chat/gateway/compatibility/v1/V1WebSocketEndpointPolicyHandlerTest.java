package com.fallingnight.chat.gateway.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.gateway.transport.WebSocketEndpointPolicy;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

class V1WebSocketEndpointPolicyHandlerTest {
    @Test
    void acceptsOnlyExactVersionedWebUpgradeAndFreezesIt() {
        EmbeddedChannel channel = channel();
        try {
            FullHttpRequest request = upgrade(V1WebSocketEndpointPolicyHandler.WEB_PATH);
            assertTrue(channel.writeInbound(request));
            ((FullHttpRequest) channel.readInbound()).release();
            assertEquals(Boolean.TRUE,
                    channel.attr(V1ConnectionAttributes.WEB_UPGRADE_ACCEPTED).get());

            assertFalse(channel.writeInbound(upgrade(
                    V1WebSocketEndpointPolicyHandler.WEB_PATH)));
            FullHttpResponse response = channel.readOutbound();
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            response.release();
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsOriginPathQuerySubprotocolAndUpgradeBypasses() {
        FullHttpRequest missingOrigin = upgrade(V1WebSocketEndpointPolicyHandler.WEB_PATH);
        missingOrigin.headers().remove(HttpHeaderNames.ORIGIN);
        assertRejected(missingOrigin);

        FullHttpRequest hostileOrigin = upgrade(V1WebSocketEndpointPolicyHandler.WEB_PATH);
        hostileOrigin.headers().set(HttpHeaderNames.ORIGIN, "https://evil.example");
        assertRejected(hostileOrigin);

        assertRejected(upgrade("/v2/web"));
        assertRejected(upgrade(V1WebSocketEndpointPolicyHandler.WEB_PATH + "?token=bad"));

        FullHttpRequest wrongSubprotocol = upgrade(V1WebSocketEndpointPolicyHandler.WEB_PATH);
        wrongSubprotocol.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "chat.v2");
        assertRejected(wrongSubprotocol);

        FullHttpRequest missingUpgrade = upgrade(V1WebSocketEndpointPolicyHandler.WEB_PATH);
        missingUpgrade.headers().remove(HttpHeaderNames.UPGRADE);
        assertRejected(missingUpgrade);

        FullHttpRequest malformed = upgrade(V1WebSocketEndpointPolicyHandler.WEB_PATH);
        malformed.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("bad")));
        assertRejected(malformed);
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new V1WebSocketEndpointPolicyHandler(
                new WebSocketEndpointPolicy(List.of("https://chat.example.com"))));
    }

    private static FullHttpRequest upgrade(String path) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, path);
        request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        request.headers().set(HttpHeaderNames.CONNECTION, "keep-alive, Upgrade");
        request.headers().set(
                HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL,
                V1WebSocketEndpointPolicyHandler.SUBPROTOCOL);
        request.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
        return request;
    }

    private static void assertRejected(FullHttpRequest request) {
        EmbeddedChannel channel = channel();
        try {
            assertFalse(channel.writeInbound(request));
            FullHttpResponse response = channel.readOutbound();
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            assertEquals("no-store", response.headers().get("Cache-Control"));
            response.release();
            assertFalse(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}

package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.identity.ClientPlatform;
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

class WebSocketEndpointPolicyHandlerTest {
    @Test
    void acceptsExactWebAndWindowsUpgradeShapesAndFreezesPlatform() {
        EmbeddedChannel web = channel();
        try {
            FullHttpRequest request = upgrade(WebSocketEndpointPolicy.WEB_PATH);
            request.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
            assertTrue(web.writeInbound(request));
            ((FullHttpRequest) web.readInbound()).release();
            assertEquals(ClientPlatform.WEB,
                    web.attr(V2ConnectionAttributes.EXPECTED_CLIENT_PLATFORM).get());
        } finally {
            web.finishAndReleaseAll();
        }

        EmbeddedChannel windows = channel();
        try {
            assertTrue(windows.writeInbound(upgrade(WebSocketEndpointPolicy.WINDOWS_PATH)));
            ((FullHttpRequest) windows.readInbound()).release();
            assertEquals(ClientPlatform.WINDOWS,
                    windows.attr(V2ConnectionAttributes.EXPECTED_CLIENT_PLATFORM).get());
        } finally {
            windows.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsOriginBypassMalformedUpgradeQueryAndRepeat() {
        assertRejected(upgrade(WebSocketEndpointPolicy.WEB_PATH));

        FullHttpRequest windowsWithOrigin = upgrade(WebSocketEndpointPolicy.WINDOWS_PATH);
        windowsWithOrigin.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
        assertRejected(windowsWithOrigin);

        FullHttpRequest wrongMethod = upgrade(WebSocketEndpointPolicy.WEB_PATH);
        wrongMethod.setMethod(HttpMethod.POST);
        wrongMethod.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
        assertRejected(wrongMethod);

        FullHttpRequest query = upgrade(WebSocketEndpointPolicy.WEB_PATH + "?token=bad");
        query.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
        assertRejected(query);

        FullHttpRequest missingConnection = upgrade(WebSocketEndpointPolicy.WEB_PATH);
        missingConnection.headers().remove(HttpHeaderNames.CONNECTION);
        missingConnection.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
        assertRejected(missingConnection);

        FullHttpRequest missingUpgrade = upgrade(WebSocketEndpointPolicy.WEB_PATH);
        missingUpgrade.headers().remove(HttpHeaderNames.UPGRADE);
        missingUpgrade.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
        assertRejected(missingUpgrade);

        FullHttpRequest missingSubprotocol = upgrade(WebSocketEndpointPolicy.WEB_PATH);
        missingSubprotocol.headers().remove(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        missingSubprotocol.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
        assertRejected(missingSubprotocol);

        FullHttpRequest malformed = upgrade(WebSocketEndpointPolicy.WEB_PATH);
        malformed.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("bad")));
        malformed.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
        assertRejected(malformed);

        EmbeddedChannel repeated = channel();
        try {
            FullHttpRequest first = upgrade(WebSocketEndpointPolicy.WEB_PATH);
            first.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
            assertTrue(repeated.writeInbound(first));
            ((FullHttpRequest) repeated.readInbound()).release();
            FullHttpRequest second = upgrade(WebSocketEndpointPolicy.WEB_PATH);
            second.headers().set(HttpHeaderNames.ORIGIN, "https://chat.example.com");
            assertFalse(repeated.writeInbound(second));
            FullHttpResponse response = repeated.readOutbound();
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            response.release();
            assertFalse(repeated.isActive());
        } finally {
            repeated.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new WebSocketEndpointPolicyHandler(
                new WebSocketEndpointPolicy(List.of("https://chat.example.com"))));
    }

    private static FullHttpRequest upgrade(String path) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, path);
        request.headers().set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        request.headers().set(HttpHeaderNames.CONNECTION, "keep-alive, Upgrade");
        request.headers().set(
                HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL,
                WebSocketEndpointPolicyHandler.V2_SUBPROTOCOL);
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

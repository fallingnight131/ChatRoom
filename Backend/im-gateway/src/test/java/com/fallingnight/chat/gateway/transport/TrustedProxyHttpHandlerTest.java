package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedProxyHttpHandlerTest {
    @Test
    void freezesTrustedForwardedPeerBeforePassingUpgradeRequest() throws Exception {
        TrustedProxyPolicy policy = TrustedProxyPolicy.trusted(
                List.of("10.0.0.0/8"), 4);
        EmbeddedChannel channel = new EmbeddedChannel(new TrustedProxyHttpHandler(
                policy, ignored -> peer("10.0.0.5")));
        FullHttpRequest request = request();
        request.headers().set("X-Forwarded-For",
                "203.0.113.99, 198.51.100.7");
        try {
            assertTrue(channel.writeInbound(request));
            assertSame(request, channel.readInbound());
            assertEquals("198.51.100.7", channel
                    .attr(V2ConnectionAttributes.CLIENT_PEER_ADDRESS)
                    .get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void ignoresSpoofedHeaderOnDirectMode() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new TrustedProxyHttpHandler(
                TrustedProxyPolicy.directOnly(), ignored -> peer("192.0.2.10")));
        FullHttpRequest request = request();
        request.headers().set("X-Forwarded-For", "203.0.113.99");
        try {
            assertTrue(channel.writeInbound(request));
            assertEquals("192.0.2.10", channel
                    .attr(V2ConnectionAttributes.CLIENT_PEER_ADDRESS)
                    .get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsMissingTrustedForwardingAndASecondUpgradeRequest() throws Exception {
        TrustedProxyPolicy policy = TrustedProxyPolicy.trusted(
                List.of("10.0.0.0/8"), 4);
        EmbeddedChannel missing = new EmbeddedChannel(new TrustedProxyHttpHandler(
                policy, ignored -> peer("10.0.0.5")));
        try {
            assertFalse(missing.writeInbound(request()));
            FullHttpResponse response = missing.readOutbound();
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            response.release();
            assertFalse(missing.isActive());
        } finally {
            missing.finishAndReleaseAll();
        }

        EmbeddedChannel repeated = new EmbeddedChannel(new TrustedProxyHttpHandler(
                TrustedProxyPolicy.directOnly(), ignored -> peer("192.0.2.10")));
        try {
            assertTrue(repeated.writeInbound(request()));
            ((FullHttpRequest) repeated.readInbound()).release();
            assertFalse(repeated.writeInbound(request()));
            FullHttpResponse response = repeated.readOutbound();
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            response.release();
            assertFalse(repeated.isActive());
        } finally {
            repeated.finishAndReleaseAll();
        }
    }

    private static FullHttpRequest request() {
        return new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/v2/ws");
    }

    private static InetSocketAddress peer(String value) {
        try {
            return new InetSocketAddress(InetAddress.getByName(value), 443);
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}

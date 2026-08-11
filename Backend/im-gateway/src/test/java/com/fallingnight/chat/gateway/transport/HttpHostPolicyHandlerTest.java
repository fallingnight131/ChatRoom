package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpHostPolicyHandlerTest {
    @Test
    void passesOneAllowedHostAndRejectsMissingOrRepeatedRequests() {
        EmbeddedChannel allowed = channel();
        try {
            FullHttpRequest request = request();
            request.headers().set(HttpHeaderNames.HOST, "gateway.example.com:443");
            assertTrue(allowed.writeInbound(request));
            ((FullHttpRequest) allowed.readInbound()).release();
            assertFalse(allowed.writeInbound(request()));
            assertBadRequest(allowed);
        } finally {
            allowed.finishAndReleaseAll();
        }

        EmbeddedChannel missing = channel();
        try {
            assertFalse(missing.writeInbound(request()));
            assertBadRequest(missing);
        } finally {
            missing.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new HttpHostPolicyHandler(
                new HttpHostPolicy(List.of("gateway.example.com"))));
    }

    private static FullHttpRequest request() {
        return new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/v2/web");
    }

    private static void assertBadRequest(EmbeddedChannel channel) {
        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertEquals("no-store", response.headers().get("Cache-Control"));
        response.release();
        assertFalse(channel.isActive());
    }
}

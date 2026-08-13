package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ProductReadinessHttpHandlerTest {
    @Test void returnsDynamicNoStoreReadinessAndPassesUnrelatedRequests() {
        AtomicBoolean ready = new AtomicBoolean();
        EmbeddedChannel channel = new EmbeddedChannel(
                new ProductReadinessHttpHandler(ready::get));
        try {
            assertTrue(channel.writeInbound(request(HttpMethod.GET, "/v2/web")));
            var unrelated = assertInstanceOf(DefaultFullHttpRequest.class,
                    channel.readInbound());
            unrelated.release();

            assertFalse(channel.writeInbound(request(
                    HttpMethod.GET, ProductReadinessHttpHandler.PATH)));
            FullHttpResponse unavailable = channel.readOutbound();
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, unavailable.status());
            assertEquals("not_ready\n", unavailable.content().toString(
                    io.netty.util.CharsetUtil.UTF_8));
            assertEquals("no-store", unavailable.headers().get(HttpHeaderNames.CACHE_CONTROL));
            unavailable.release();
        } finally { channel.finishAndReleaseAll(); }

        ready.set(true);
        EmbeddedChannel healthy = new EmbeddedChannel(
                new ProductReadinessHttpHandler(ready::get));
        try {
            assertFalse(healthy.writeInbound(request(
                    HttpMethod.GET, ProductReadinessHttpHandler.PATH)));
            FullHttpResponse response = healthy.readOutbound();
            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals("ready\n", response.content().toString(
                    io.netty.util.CharsetUtil.UTF_8));
            response.release();
        } finally { healthy.finishAndReleaseAll(); }
    }

    @Test void supportsHeadRejectsOtherMethodsAndFailsClosedOnSupplierError() {
        EmbeddedChannel head = new EmbeddedChannel(
                new ProductReadinessHttpHandler(() -> true));
        try {
            head.writeInbound(request(HttpMethod.HEAD, ProductReadinessHttpHandler.PATH));
            FullHttpResponse response = head.readOutbound();
            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals(0, response.content().readableBytes());
            response.release();
        } finally { head.finishAndReleaseAll(); }

        EmbeddedChannel post = new EmbeddedChannel(
                new ProductReadinessHttpHandler(() -> true));
        try {
            post.writeInbound(request(HttpMethod.POST, ProductReadinessHttpHandler.PATH));
            FullHttpResponse response = post.readOutbound();
            assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status());
            assertEquals("GET, HEAD", response.headers().get(HttpHeaderNames.ALLOW));
            response.release();
        } finally { post.finishAndReleaseAll(); }

        EmbeddedChannel failed = new EmbeddedChannel(
                new ProductReadinessHttpHandler(() -> { throw new IllegalStateException(); }));
        try {
            failed.writeInbound(request(HttpMethod.GET, ProductReadinessHttpHandler.PATH));
            FullHttpResponse response = failed.readOutbound();
            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
            response.release();
        } finally { failed.finishAndReleaseAll(); }
    }

    private static DefaultFullHttpRequest request(HttpMethod method, String path) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path);
    }
}

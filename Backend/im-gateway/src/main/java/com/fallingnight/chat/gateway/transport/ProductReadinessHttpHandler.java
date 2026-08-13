package com.fallingnight.chat.gateway.transport;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Minimal TLS product-port readiness response for an active load balancer check. */
public final class ProductReadinessHttpHandler extends ChannelInboundHandlerAdapter {
    public static final String PATH = "/health/ready";
    private final BooleanSupplier readiness;

    public ProductReadinessHttpHandler(BooleanSupplier readiness) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!(message instanceof FullHttpRequest request)
                || !PATH.equals(request.uri())) {
            context.fireChannelRead(message);
            return;
        }
        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            respond(context, request, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    "method_not_allowed\n");
            return;
        }
        boolean ready;
        try {
            ready = readiness.getAsBoolean();
        } catch (RuntimeException exception) {
            ready = false;
        }
        respond(context, request,
                ready ? HttpResponseStatus.OK : HttpResponseStatus.SERVICE_UNAVAILABLE,
                ready ? "ready\n" : "not_ready\n");
    }

    private static void respond(ChannelHandlerContext context, FullHttpRequest request,
            HttpResponseStatus status, String body) {
        boolean head = request.method() == HttpMethod.HEAD;
        byte[] bytes = body.getBytes(CharsetUtil.UTF_8);
        var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                head ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                "text/plain; charset=utf-8");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        if (status == HttpResponseStatus.METHOD_NOT_ALLOWED) {
            response.headers().set(HttpHeaderNames.ALLOW, "GET, HEAD");
        }
        HttpUtil.setKeepAlive(response, false);
        context.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        request.release();
    }
}

package com.fallingnight.chat.gateway.transport;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Objects;

/** Rejects missing, duplicate, malformed, or unapproved Host before upgrade. */
public final class HttpHostPolicyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final HttpHostPolicy policy;
    private boolean accepted;

    public HttpHostPolicyHandler(HttpHostPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        if (accepted || !policy.allows(request.headers().getAll(HttpHeaderNames.HOST))) {
            reject(context);
            return;
        }
        accepted = true;
        context.fireChannelRead(request.retain());
    }

    private static void reject(ChannelHandlerContext context) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        response.headers().set("Cache-Control", "no-store");
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}

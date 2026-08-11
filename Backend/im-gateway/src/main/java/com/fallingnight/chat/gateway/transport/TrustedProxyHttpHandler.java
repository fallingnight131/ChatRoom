package com.fallingnight.chat.gateway.transport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.Function;

/** Resolves and freezes the admission peer before a WebSocket HTTP upgrade. */
public final class TrustedProxyHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final CharSequence X_FORWARDED_FOR = "X-Forwarded-For";
    private final TrustedProxyPolicy policy;
    private final Function<Channel, InetSocketAddress> directPeer;

    public TrustedProxyHttpHandler(TrustedProxyPolicy policy) {
        this(policy, TrustedProxyHttpHandler::remoteInetAddress);
    }

    TrustedProxyHttpHandler(
            TrustedProxyPolicy policy,
            Function<Channel, InetSocketAddress> directPeer) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.directPeer = Objects.requireNonNull(directPeer, "directPeer");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        if (context.channel().attr(V2ConnectionAttributes.CLIENT_PEER_ADDRESS).get() != null) {
            reject(context);
            return;
        }
        PeerResolution resolution;
        try {
            resolution = policy.resolve(
                    directPeer.apply(context.channel()),
                    request.headers().getAll(X_FORWARDED_FOR));
        } catch (RuntimeException exception) {
            reject(context);
            return;
        }
        if (!resolution.accepted()) {
            reject(context);
            return;
        }
        context.channel()
                .attr(V2ConnectionAttributes.CLIENT_PEER_ADDRESS)
                .set(resolution.clientAddress());
        context.fireChannelRead(request.retain());
    }

    private static void reject(ChannelHandlerContext context) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static InetSocketAddress remoteInetAddress(Channel channel) {
        SocketAddress remote = channel.remoteAddress();
        return remote instanceof InetSocketAddress address ? address : null;
    }
}

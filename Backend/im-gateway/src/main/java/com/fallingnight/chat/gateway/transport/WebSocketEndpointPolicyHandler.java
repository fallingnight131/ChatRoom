package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.ClientPlatform;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.Locale;
import java.util.Objects;

/** Enforces product endpoint, Origin, and upgrade shape before WebSocket negotiation. */
public final class WebSocketEndpointPolicyHandler
        extends SimpleChannelInboundHandler<FullHttpRequest> {
    public static final String V2_SUBPROTOCOL = "chat.v2";
    private final WebSocketEndpointPolicy policy;

    public WebSocketEndpointPolicyHandler(WebSocketEndpointPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        if (context.channel().attr(V2ConnectionAttributes.EXPECTED_CLIENT_PLATFORM).get()
                != null
                || !validUpgradeShape(request)) {
            reject(context);
            return;
        }
        final ClientPlatform expected;
        try {
            expected = policy.expectedPlatform(
                    request.uri(), request.headers().getAll(HttpHeaderNames.ORIGIN));
        } catch (RuntimeException exception) {
            reject(context);
            return;
        }
        context.channel()
                .attr(V2ConnectionAttributes.EXPECTED_CLIENT_PLATFORM)
                .set(expected);
        context.fireChannelRead(request.retain());
    }

    private static boolean validUpgradeShape(FullHttpRequest request) {
        if (request.method() != HttpMethod.GET || request.decoderResult().isFailure()) {
            return false;
        }
        if (!HttpHeaderValues.WEBSOCKET.toString().equalsIgnoreCase(
                request.headers().get(HttpHeaderNames.UPGRADE))) {
            return false;
        }
        String connection = request.headers().get(HttpHeaderNames.CONNECTION);
        if (connection == null) {
            return false;
        }
        boolean upgradeToken = java.util.Arrays.stream(connection.split(","))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch("upgrade"::equals);
        java.util.List<String> subprotocols = request.headers().getAll(
                HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        return upgradeToken
                && request.uri().indexOf('?') < 0
                && subprotocols.size() == 1
                && V2_SUBPROTOCOL.equals(subprotocols.getFirst());
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

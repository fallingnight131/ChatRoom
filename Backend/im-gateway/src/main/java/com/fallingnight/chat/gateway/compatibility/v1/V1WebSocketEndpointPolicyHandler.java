package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.gateway.transport.WebSocketEndpointPolicy;
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

/** Exact, inactive HTTP upgrade guard for the future V1 browser route. */
public final class V1WebSocketEndpointPolicyHandler
        extends SimpleChannelInboundHandler<FullHttpRequest> {
    public static final String WEB_PATH = "/v1/web";
    public static final String SUBPROTOCOL = "chat.v1";

    private final WebSocketEndpointPolicy originPolicy;

    public V1WebSocketEndpointPolicyHandler(WebSocketEndpointPolicy originPolicy) {
        this.originPolicy = Objects.requireNonNull(originPolicy, "originPolicy");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        if (Boolean.TRUE.equals(context.channel()
                        .attr(V1ConnectionAttributes.WEB_UPGRADE_ACCEPTED)
                        .get())
                || !validUpgradeShape(request)) {
            reject(context);
            return;
        }
        try {
            originPolicy.requireAllowedWebOrigin(
                    request.headers().getAll(HttpHeaderNames.ORIGIN));
        } catch (RuntimeException exception) {
            reject(context);
            return;
        }
        context.channel().attr(V1ConnectionAttributes.WEB_UPGRADE_ACCEPTED).set(true);
        context.fireChannelRead(request.retain());
    }

    private static boolean validUpgradeShape(FullHttpRequest request) {
        if (request.method() != HttpMethod.GET
                || request.decoderResult().isFailure()
                || !WEB_PATH.equals(request.uri())) {
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
                && subprotocols.size() == 1
                && SUBPROTOCOL.equals(subprotocols.getFirst());
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

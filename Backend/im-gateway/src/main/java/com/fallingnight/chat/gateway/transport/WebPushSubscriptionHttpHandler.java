package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.notification.WebPushSubscriptionMutationResult;
import com.fallingnight.chat.application.notification.WebPushSubscriptionMutationService;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Default-off authenticated Web Push subscription route; blocking work is off-event-loop. */
public final class WebPushSubscriptionHttpHandler
        extends SimpleChannelInboundHandler<FullHttpRequest> {
    public static final String PATH_PREFIX = "/api/v2/web-push/subscriptions/";
    public static final CharSequence CSRF_HEADER = "X-CSRF-Token";
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{32,256}";

    private final WebPushHttpApiPolicy policy;
    private final WebPushHttpSessionPort sessions;
    private final WebPushSubscriptionMutationService mutations;
    private final WebPushSubscriptionJsonCodec codec;
    private final Clock clock;
    private final Executor worker;
    private final WebPushHttpEventSink events;
    private boolean accepted;

    public WebPushSubscriptionHttpHandler(
            WebPushHttpApiPolicy policy, WebPushHttpSessionPort sessions,
            WebPushSubscriptionMutationService mutations,
            WebPushSubscriptionJsonCodec codec, Clock clock, Executor worker,
            WebPushHttpEventSink events) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        if (!request.uri().startsWith(PATH_PREFIX)) {
            context.fireChannelRead(request.retain()); return;
        }
        if (accepted) { reject(context, request, HttpResponseStatus.BAD_REQUEST,
                WebPushHttpOutcome.BAD_REQUEST, null); return; }
        accepted = true;
        if (!policy.enabled()) { reject(context, request, HttpResponseStatus.NOT_FOUND,
                WebPushHttpOutcome.DISABLED, null); return; }
        UUID installation = installation(request.uri());
        if (installation == null) { reject(context, request, HttpResponseStatus.BAD_REQUEST,
                WebPushHttpOutcome.BAD_REQUEST, null); return; }
        if (!policy.allows(request.headers().getAll(HttpHeaderNames.ORIGIN))) {
            reject(context, request, HttpResponseStatus.FORBIDDEN,
                    WebPushHttpOutcome.ORIGIN_REJECTED, null); return;
        }
        boolean replace = request.method() == HttpMethod.PUT;
        if (!replace && request.method() != HttpMethod.DELETE) {
            reject(context, request, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    WebPushHttpOutcome.METHOD_REJECTED, "PUT, DELETE"); return;
        }
        int length = request.content().readableBytes();
        if ((replace && (length < 1 || length > WebPushSubscriptionJsonCodec.MAX_WIRE_BYTES))
                || (!replace && length != 0)) {
            reject(context, request, length > WebPushSubscriptionJsonCodec.MAX_WIRE_BYTES
                            ? HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE
                            : HttpResponseStatus.BAD_REQUEST,
                    WebPushHttpOutcome.BODY_REJECTED, null); return;
        }
        if (replace && !singleEquals(request.headers().getAll(HttpHeaderNames.CONTENT_TYPE),
                "application/json")) {
            reject(context, request, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
                    WebPushHttpOutcome.MEDIA_REJECTED, null); return;
        }
        byte[][] credentials = credentials(request);
        if (credentials == null) { reject(context, request, HttpResponseStatus.UNAUTHORIZED,
                WebPushHttpOutcome.INVALID_SESSION, null); return; }
        byte[] body = new byte[length]; request.content().getBytes(request.content().readerIndex(), body);
        try {
            worker.execute(() -> execute(context, installation, replace,
                    credentials[0], credentials[1], body));
        } catch (RuntimeException exception) {
            clear(credentials[0]); clear(credentials[1]); clear(body);
            reject(context, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    WebPushHttpOutcome.WORKER_REJECTED, null);
        }
    }

    private void execute(ChannelHandlerContext context, UUID installation, boolean replace,
            byte[] bearer, byte[] csrf, byte[] body) {
        HttpResponseStatus status; WebPushHttpOutcome outcome; Long retryAfter = null;
        try {
            WebPushHttpAuthenticationResult authentication = Objects.requireNonNull(
                    sessions.authenticate(bearer, csrf, clock.instant()), "authentication");
            if (authentication == WebPushHttpAuthenticationResult.Rejected.INVALID_SESSION) {
                status = HttpResponseStatus.UNAUTHORIZED; outcome = WebPushHttpOutcome.INVALID_SESSION;
            } else if (authentication == WebPushHttpAuthenticationResult.Rejected.INVALID_CSRF) {
                status = HttpResponseStatus.FORBIDDEN; outcome = WebPushHttpOutcome.INVALID_CSRF;
            } else {
                UUID account = ((WebPushHttpAuthenticationResult.Authenticated) authentication)
                        .actor().accountId();
                WebPushSubscriptionMutationResult result = replace
                        ? mutations.replace(account, codec.decode(installation, body))
                        : mutations.delete(account, installation);
                status = status(result); outcome = outcome(result);
                if (result.retryAfter().isPresent())
                    retryAfter = Math.max(1,
                            (result.retryAfter().orElseThrow().toMillis() + 999) / 1_000);
            }
        } catch (IllegalArgumentException exception) {
            status = HttpResponseStatus.BAD_REQUEST; outcome = WebPushHttpOutcome.BAD_REQUEST;
        } catch (RuntimeException exception) {
            status = HttpResponseStatus.SERVICE_UNAVAILABLE; outcome = WebPushHttpOutcome.FAILURE;
        } finally {
            clear(bearer); clear(csrf); clear(body);
        }
        HttpResponseStatus finalStatus = status; WebPushHttpOutcome finalOutcome = outcome;
        Long finalRetryAfter = retryAfter;
        try {
            context.executor().execute(() -> respond(context, finalStatus, finalOutcome,
                    null, finalRetryAfter));
        } catch (RuntimeException exception) {
            safeRecord(WebPushHttpOutcome.FAILURE);
        }
    }

    private void reject(ChannelHandlerContext context, FullHttpRequest request,
            HttpResponseStatus status, WebPushHttpOutcome outcome, String allow) {
        respond(context, status, outcome, allow, null);
    }

    private void respond(ChannelHandlerContext context, HttpResponseStatus status,
            WebPushHttpOutcome outcome, String allow, Long retryAfter) {
        safeRecord(outcome);
        var response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        if (allow != null) response.headers().set(HttpHeaderNames.ALLOW, allow);
        if (retryAfter != null) response.headers().set(HttpHeaderNames.RETRY_AFTER, retryAfter);
        if (status == HttpResponseStatus.UNAUTHORIZED)
            response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer");
        HttpUtil.setKeepAlive(response, false);
        context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void safeRecord(WebPushHttpOutcome outcome) {
        try { events.record(outcome); }
        catch (RuntimeException ignored) { }
    }

    private static UUID installation(String uri) {
        if (uri.indexOf('?', PATH_PREFIX.length()) >= 0 || uri.indexOf('#') >= 0) return null;
        try { return UUID.fromString(uri.substring(PATH_PREFIX.length())); }
        catch (IllegalArgumentException exception) { return null; }
    }

    private static byte[][] credentials(FullHttpRequest request) {
        List<String> authorization = request.headers().getAll(HttpHeaderNames.AUTHORIZATION);
        List<String> csrf = request.headers().getAll(CSRF_HEADER);
        if (authorization.size() != 1 || csrf.size() != 1
                || !authorization.getFirst().startsWith("Bearer ")) return null;
        String bearer = authorization.getFirst().substring(7);
        String csrfValue = csrf.getFirst();
        if (!bearer.matches(TOKEN_PATTERN) || !csrfValue.matches(TOKEN_PATTERN)) return null;
        return new byte[][] {bearer.getBytes(StandardCharsets.US_ASCII),
                csrfValue.getBytes(StandardCharsets.US_ASCII)};
    }

    private static boolean singleEquals(List<String> values, String expected) {
        return values.size() == 1 && expected.equals(values.getFirst());
    }

    private static HttpResponseStatus status(WebPushSubscriptionMutationResult result) {
        return switch (result.outcome()) {
            case REPLACED, DELETED, UNCHANGED -> HttpResponseStatus.NO_CONTENT;
            case DISABLED -> HttpResponseStatus.NOT_FOUND;
            case ACCOUNT_UNAVAILABLE -> HttpResponseStatus.FORBIDDEN;
            case LIMIT_REACHED -> HttpResponseStatus.CONFLICT;
            case RATE_LIMITED -> HttpResponseStatus.TOO_MANY_REQUESTS;
        };
    }

    private static WebPushHttpOutcome outcome(WebPushSubscriptionMutationResult result) {
        return WebPushHttpOutcome.valueOf(result.outcome().name());
    }

    private static void clear(byte[] value) { Arrays.fill(value, (byte) 0); }
}

package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.application.identity.AuthenticationUseCase;
import com.fallingnight.chat.application.identity.SessionResumeUseCase;
import com.fallingnight.chat.application.messaging.MessageHistoryPort;
import com.fallingnight.chat.application.messaging.MessageSubmissionPort;
import com.fallingnight.chat.gateway.transport.AuthenticationAdmissionControl;
import com.fallingnight.chat.gateway.transport.AuthenticationEventSink;
import com.fallingnight.chat.gateway.transport.GatewayConnectionLimitHandler;
import com.fallingnight.chat.gateway.transport.GatewayConnectionLimiter;
import com.fallingnight.chat.gateway.transport.GatewayChannelExceptionHandler;
import com.fallingnight.chat.gateway.transport.HttpHostPolicyHandler;
import com.fallingnight.chat.gateway.transport.MessagingEventSink;
import com.fallingnight.chat.gateway.transport.TrustedProxyHttpHandler;
import com.fallingnight.chat.gateway.transport.V2EnvelopeDecoder;
import com.fallingnight.chat.gateway.transport.V2WebSocketUpgradeHandler;
import com.fallingnight.chat.gateway.transport.WebSocketEndpointPolicy;
import com.fallingnight.chat.gateway.transport.WebSocketEndpointPolicyHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/** Owned Netty WSS listener lifecycle; construction validates TLS before bind. */
@SuppressWarnings("deprecation") // Netty 4.1 pins NIO bootstrap to NioEventLoopGroup.
public final class V2GatewayServer implements AutoCloseable {
    private static final int SOCKET_BACKLOG = 256;
    private static final int MAX_HTTP_CONTENT_BYTES = 16_384;
    private static final int HTTP_INITIAL_LINE_BYTES = 4_096;
    private static final int HTTP_HEADER_BYTES = 8_192;
    private static final int HTTP_CHUNK_BYTES = 8_192;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final GatewayRuntimeConfig config;
    private final AuthenticationUseCase authentication;
    private final SessionResumeUseCase sessionResume;
    private final MessageSubmissionPort submissions;
    private final MessageHistoryPort history;
    private final Executor authenticationExecutor;
    private final Executor messagingExecutor;
    private final AuthenticationAdmissionControl admission;
    private final AuthenticationEventSink events;
    private final MessagingEventSink messagingEvents;
    private final SslContext sslContext;
    private final GatewayConnectionLimiter connectionLimiter;
    private final ChannelGroup children = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private Channel listener;
    private ChannelFuture termination;

    public V2GatewayServer(
            GatewayRuntimeConfig config,
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            Executor authenticationExecutor,
            Executor messagingExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            MessagingEventSink messagingEvents) {
        this(
                config,
                authentication,
                sessionResume,
                submissions,
                history,
                authenticationExecutor,
                messagingExecutor,
                admission,
                events,
                messagingEvents,
                createSslContext(config));
    }

    V2GatewayServer(
            GatewayRuntimeConfig config,
            AuthenticationUseCase authentication,
            SessionResumeUseCase sessionResume,
            MessageSubmissionPort submissions,
            MessageHistoryPort history,
            Executor authenticationExecutor,
            Executor messagingExecutor,
            AuthenticationAdmissionControl admission,
            AuthenticationEventSink events,
            MessagingEventSink messagingEvents,
            SslContext sslContext) {
        this.config = Objects.requireNonNull(config, "config");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.sessionResume = Objects.requireNonNull(sessionResume, "sessionResume");
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.history = Objects.requireNonNull(history, "history");
        this.authenticationExecutor = Objects.requireNonNull(
                authenticationExecutor, "authenticationExecutor");
        this.messagingExecutor = Objects.requireNonNull(messagingExecutor, "messagingExecutor");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.events = Objects.requireNonNull(events, "events");
        this.messagingEvents = Objects.requireNonNull(messagingEvents, "messagingEvents");
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
        connectionLimiter = new GatewayConnectionLimiter(config.maximumConnections());
    }

    public synchronized void start() {
        if (listener != null) {
            throw new IllegalStateException("gateway listener already started");
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(config.eventLoopWorkers());
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, SOCKET_BACKLOG)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(
                            ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(
                                    config.writeBufferLowWaterMark(),
                                    config.writeBufferHighWaterMark()))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            children.add(channel);
                            installInitialPipeline(channel.pipeline());
                        }
                    });
            listener = bootstrap.bind(config.listenerAddress()).syncUninterruptibly().channel();
            termination = listener.closeFuture();
        } catch (RuntimeException exception) {
            close();
            throw new IllegalStateException("gateway listener bind failed", exception);
        }
    }

    public synchronized InetSocketAddress address() {
        if (listener == null) {
            throw new IllegalStateException("gateway listener is not started");
        }
        return (InetSocketAddress) listener.localAddress();
    }

    public synchronized boolean isRunning() {
        return listener != null && listener.isActive();
    }

    public void awaitClose() {
        ChannelFuture current;
        synchronized (this) {
            if (termination == null) {
                throw new IllegalStateException("gateway listener is not started");
            }
            current = termination;
        }
        current.syncUninterruptibly();
    }

    public int activeConnections() {
        return connectionLimiter.activeConnections();
    }

    @Override
    public synchronized void close() {
        if (listener != null) {
            listener.close().syncUninterruptibly();
            listener = null;
        }
        children.close().awaitUninterruptibly(SHUTDOWN_TIMEOUT.toMillis());
        shutdown(workerGroup);
        shutdown(bossGroup);
        workerGroup = null;
        bossGroup = null;
    }

    private void installInitialPipeline(ChannelPipeline pipeline) {
        pipeline.addLast("connection-limit", new GatewayConnectionLimitHandler(connectionLimiter));
        var sslHandler = sslContext.newHandler(pipeline.channel().alloc());
        sslHandler.setHandshakeTimeoutMillis(config.handshakeTimeout().toMillis());
        pipeline.addLast("tls", sslHandler);
        pipeline.addLast("authenticated-reader-idle", new IdleStateHandler(
                config.authenticatedIdleTimeout().toSeconds(), 0, 0, TimeUnit.SECONDS));
        pipeline.addLast("http-codec", new HttpServerCodec(
                HTTP_INITIAL_LINE_BYTES,
                HTTP_HEADER_BYTES,
                HTTP_CHUNK_BYTES));
        pipeline.addLast("http-aggregate", new HttpObjectAggregator(MAX_HTTP_CONTENT_BYTES));
        pipeline.addLast("host-policy", new HttpHostPolicyHandler(config.hostPolicy()));
        pipeline.addLast("proxy-policy", new TrustedProxyHttpHandler(config.proxyPolicy()));
        pipeline.addLast(
                "endpoint-policy", new WebSocketEndpointPolicyHandler(config.endpointPolicy()));
        pipeline.addLast("websocket", new WebSocketServerProtocolHandler(webSocketConfig()));
        pipeline.addLast("v2-upgrade", new V2WebSocketUpgradeHandler(
                authentication,
                sessionResume,
                submissions,
                history,
                authenticationExecutor,
                messagingExecutor,
                admission,
                events,
                messagingEvents,
                config.handshakeTimeout(),
                config.authenticationTimeout()));
        pipeline.addLast("safe-channel-error", new GatewayChannelExceptionHandler());
    }

    private WebSocketServerProtocolConfig webSocketConfig() {
        return WebSocketServerProtocolConfig.newBuilder()
                .websocketPath("/v2")
                .checkStartsWith(true)
                .subprotocols(WebSocketEndpointPolicyHandler.V2_SUBPROTOCOL)
                .handshakeTimeoutMillis(config.handshakeTimeout().toMillis())
                .maxFramePayloadLength(V2EnvelopeDecoder.MAX_WIRE_BYTES)
                .expectMaskedFrames(true)
                .allowMaskMismatch(false)
                .allowExtensions(false)
                .closeOnProtocolViolation(true)
                .withUTF8Validator(true)
                .dropPongFrames(true)
                .handleCloseFrames(true)
                .build();
    }

    private static SslContext createSslContext(GatewayRuntimeConfig config) {
        Objects.requireNonNull(config, "config");
        String password = config.tlsPrivateKeyPassword();
        try {
            return SslContextBuilder.forServer(
                            config.tlsCertificateChain().toFile(),
                            config.tlsPrivateKey().toFile(),
                            password.isEmpty() ? null : password)
                    .protocols("TLSv1.3", "TLSv1.2")
                    .build();
        } catch (SSLException | RuntimeException exception) {
            throw new IllegalArgumentException("gateway TLS material is invalid", exception);
        }
    }

    private static void shutdown(NioEventLoopGroup group) {
        if (group != null) {
            group.shutdownGracefully(0, SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .syncUninterruptibly();
        }
    }
}

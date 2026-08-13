package com.fallingnight.chat.performance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fallingnight.chat.gateway.runtime.GatewayRuntime;
import com.fallingnight.chat.gateway.runtime.GatewayRuntimeConfig;
import com.fallingnight.chat.persistence.postgres.PostgresMigrator;
import com.fallingnight.chat.protocol.v2.Authenticate;
import com.fallingnight.chat.protocol.v2.ClientHello;
import com.fallingnight.chat.protocol.v2.ClientPlatform;
import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.fallingnight.chat.protocol.v2.MessageAccepted;
import com.fallingnight.chat.protocol.v2.MessageContentType;
import com.fallingnight.chat.protocol.v2.MessageHistoryPage;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.fallingnight.chat.protocol.v2.MessageRecord;
import com.fallingnight.chat.protocol.v2.MessageType;
import com.fallingnight.chat.protocol.v2.ReadMessageHistory;
import com.fallingnight.chat.protocol.v2.ResumeSession;
import com.fallingnight.chat.protocol.v2.SessionEstablished;
import com.fallingnight.chat.protocol.v2.SubmitMessage;
import com.google.protobuf.ByteString;
import com.sun.management.OperatingSystemMXBean;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.postgresql.ds.PGSimpleDataSource;

/** Bounded real-network baseline for one production gateway and disposable PostgreSQL. */
public final class GatewayMessagingBaseline {
    private static final String CONFIRMATION = "DISPOSABLE_POSTGRES_ONLY";
    private static final String PASSWORD = "java-v2-test-password";
    private static final String PASSWORD_HASH =
            "$argon2id$v=19$m=65536,t=2,p=1$E1wX9i9QVyERI3DZqWy0Kg$"
                    + "nDO9/91zFAJGLsvBZudV4nKX4eGGHWTwuimwcjPzPcw";

    private GatewayMessagingBaseline() {}

    public static void main(String[] arguments) throws Exception {
        Configuration configuration = Configuration.parse(arguments);
        if (!CONFIRMATION.equals(System.getenv("CHATROOM_PERFORMANCE_CONFIRM"))) {
            throw new IllegalArgumentException(
                    "CHATROOM_PERFORMANCE_CONFIRM must be DISPOSABLE_POSTGRES_ONLY");
        }
        requireLoopback(configuration.jdbcUrl());
        new PostgresMigrator(configuration.jdbcUrl(), configuration.username(),
                configuration.password()).migrate();

        UUID senderAccount = UUID.randomUUID();
        List<UUID> peerAccounts = new ArrayList<>(configuration.receivers());
        for (int index = 0; index < configuration.receivers(); ++index) {
            peerAccounts.add(UUID.randomUUID());
        }
        UUID conversation = UUID.randomUUID();
        seed(configuration, senderAccount, peerAccounts, conversation);

        OperatingSystemMXBean operatingSystem = (OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = operatingSystem.getProcessCpuTime();
        Instant startedAt = Instant.now();
        long peakHeap = usedHeap();
        GatewayRuntime runtime = null;
        ClientConnection sender = null;
        List<ClientConnection> peers = new ArrayList<>(configuration.receivers());
        try {
            runtime = GatewayRuntime.create(runtimeConfiguration(configuration));
            runtime.start();
            long senderSetupStart = System.nanoTime();
            sender = connectAndAuthenticate(configuration, "gateway-sender", "sender-device");
            long senderSetupMicros = elapsedMicros(senderSetupStart);
            List<Long> setupMicros = new ArrayList<>(configuration.receivers() + 1);
            setupMicros.add(senderSetupMicros);
            for (int index = 0; index < configuration.receivers(); ++index) {
                long peerSetupStart = System.nanoTime();
                ClientConnection peer = connectAndAuthenticate(
                        configuration, "gateway-peer-" + index, "peer-device-" + index);
                setupMicros.add(elapsedMicros(peerSetupStart));
                catchUp(peer, conversation);
                peers.add(peer);
            }

            for (int index = 0; index < configuration.warmupOperations(); ++index) {
                roundTrip(sender, peers, conversation, "warmup-" + index,
                        configuration.payloadBytes(), index + 1L);
            }
            List<Long> acknowledgementMicros = new ArrayList<>(configuration.messageOperations());
            List<Long> fanoutMicros = new ArrayList<>(configuration.messageOperations());
            long measuredStart = System.nanoTime();
            for (int index = 0; index < configuration.messageOperations(); ++index) {
                long expectedSequence = configuration.warmupOperations() + index + 1L;
                TimedRoundTrip result = roundTrip(
                        sender, peers, conversation, "measured-" + index,
                        configuration.payloadBytes(), expectedSequence);
                acknowledgementMicros.add(result.acknowledgementMicros());
                fanoutMicros.add(result.fanoutMicros());
                peakHeap = Math.max(peakHeap, usedHeap());
            }
            long measuredNanos = System.nanoTime() - measuredStart;
            long durableMessages = (long) configuration.warmupOperations()
                    + configuration.messageOperations();
            requireMessageState(configuration, conversation, durableMessages);
            List<ClientConnection> activeConnections = new ArrayList<>(peers.size() + 1);
            activeConnections.add(sender);
            activeConnections.addAll(peers);
            ReconnectResult reconnect = reconnect(configuration, activeConnections);
            if (!reconnect.connections().isEmpty()) {
                sender = reconnect.connections().getFirst();
                peers.clear();
                peers.addAll(reconnect.connections().subList(1, reconnect.connections().size()));
            }
            long cpuNanos = operatingSystem.getProcessCpuTime() - cpuStart;
            peakHeap = Math.max(peakHeap, usedHeap());
            write(configuration, startedAt, Duration.between(startedAt, Instant.now()),
                    cpuNanos, peakHeap, durableMessages,
                    setupMicros,
                    acknowledgementMicros, fanoutMicros, measuredNanos, reconnect);
        } finally {
            for (ClientConnection peer : peers) peer.close();
            if (sender != null) sender.close();
            if (runtime != null) runtime.close();
        }
    }

    private static ClientConnection connectAndAuthenticate(
            Configuration configuration, String login, String deviceId) throws Exception {
        EnvelopeListener listener = new EnvelopeListener();
        HttpClient client = HttpClient.newBuilder()
                .sslContext(trustAllTls())
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        WebSocket socket = client.newWebSocketBuilder()
                .subprotocols("chat.v2")
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create("wss://localhost:" + configuration.gatewayPort()
                        + "/v2/windows"), listener)
                .get(5, TimeUnit.SECONDS);
        try {
            send(socket, clientHello(deviceId));
            requireType(listener.next(), MessageType.MESSAGE_TYPE_SERVER_HELLO);
            send(socket, authenticate(login));
            Envelope response = listener.next();
            requireType(response, MessageType.MESSAGE_TYPE_SESSION_ESTABLISHED);
            SessionEstablished session = SessionEstablished.parseFrom(response.getPayload());
            if (session.getSessionId().isBlank()) {
                throw new IllegalStateException("gateway authentication returned no session");
            }
            return new ClientConnection(client, socket, listener, session, deviceId);
        } catch (Exception exception) {
            socket.abort();
            throw exception;
        }
    }

    private static ClientConnection connectAndResume(
            Configuration configuration, ClientConnection previous) throws Exception {
        EnvelopeListener listener = new EnvelopeListener();
        HttpClient client = HttpClient.newBuilder()
                .sslContext(trustAllTls())
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        WebSocket socket = client.newWebSocketBuilder()
                .subprotocols("chat.v2")
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create("wss://localhost:" + configuration.gatewayPort()
                        + "/v2/windows"), listener)
                .get(5, TimeUnit.SECONDS);
        try {
            send(socket, clientHello(previous.deviceId()));
            requireType(listener.next(), MessageType.MESSAGE_TYPE_SERVER_HELLO);
            ResumeSession payload = ResumeSession.newBuilder()
                    .setSessionId(previous.session().getSessionId())
                    .setResumeToken(previous.session().getResumeToken())
                    .build();
            send(socket, command(MessageType.MESSAGE_TYPE_RESUME_SESSION,
                    "resume-" + previous.deviceId(), "", "", payload.toByteString()));
            Envelope response = listener.next();
            requireType(response, MessageType.MESSAGE_TYPE_SESSION_ESTABLISHED);
            SessionEstablished rotated = SessionEstablished.parseFrom(response.getPayload());
            if (!rotated.getSessionId().equals(previous.session().getSessionId())
                    || !rotated.getAccountId().equals(previous.session().getAccountId())
                    || !rotated.getDeviceId().equals(previous.session().getDeviceId())
                    || rotated.getResumeToken().equals(previous.session().getResumeToken())) {
                throw new IllegalStateException("session resume did not rotate exact identity");
            }
            return new ClientConnection(client, socket, listener, rotated, previous.deviceId());
        } catch (Exception exception) {
            socket.abort();
            throw exception;
        }
    }

    private static ReconnectResult reconnect(
            Configuration configuration, List<ClientConnection> initial) throws Exception {
        if (configuration.reconnectRounds() == 0) return ReconnectResult.NONE;
        List<ClientConnection> current = List.copyOf(initial);
        List<Long> latencies = new ArrayList<>(
                current.size() * configuration.reconnectRounds());
        long measuredStart = System.nanoTime();
        for (int round = 0; round < configuration.reconnectRounds(); ++round) {
            for (ClientConnection connection : current) connection.close();
            CountDownLatch ready = new CountDownLatch(current.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResumeAttempt>> futures = new ArrayList<>(current.size());
            try (ExecutorService executor = Executors.newFixedThreadPool(current.size())) {
                for (int index = 0; index < current.size(); ++index) {
                    int position = index;
                    ClientConnection previous = current.get(index);
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        long started = System.nanoTime();
                        ClientConnection resumed = connectAndResume(configuration, previous);
                        return new ResumeAttempt(position, resumed, elapsedMicros(started));
                    }));
                }
                if (!ready.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("reconnect workers did not become ready");
                }
                start.countDown();
                List<ResumeAttempt> attempts = new ArrayList<>(current.size());
                for (Future<ResumeAttempt> future : futures) attempts.add(get(future));
                attempts.sort(java.util.Comparator.comparingInt(ResumeAttempt::position));
                current = attempts.stream().map(ResumeAttempt::connection).toList();
                attempts.forEach(attempt -> latencies.add(attempt.latencyMicros()));
            } catch (Exception exception) {
                closeCompletedReconnects(futures);
                throw exception;
            }
        }
        return new ReconnectResult(current, List.copyOf(latencies),
                System.nanoTime() - measuredStart, 0);
    }

    private static void closeCompletedReconnects(List<Future<ResumeAttempt>> futures) {
        for (Future<ResumeAttempt> future : futures) {
            if (!future.isDone() || future.isCancelled()) continue;
            try {
                future.get().connection().close();
            } catch (Exception ignored) {
                // Failed attempts own no live connection; successful ones are closed above.
            }
        }
    }

    private static ResumeAttempt get(Future<ResumeAttempt> future) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            throw new IllegalStateException("reconnect worker failed", cause);
        }
    }

    private static void catchUp(ClientConnection peer, UUID conversation) throws Exception {
        ReadMessageHistory payload = ReadMessageHistory.newBuilder()
                .setConversationId(conversation.toString())
                .setAfterSequence(0)
                .setLimit(100)
                .build();
        send(peer.socket(), command(MessageType.MESSAGE_TYPE_READ_MESSAGE_HISTORY,
                "history-1", peer.sessionId(), "", payload.toByteString()));
        Envelope response = peer.listener().next();
        requireType(response, MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE);
        MessageHistoryPage page = MessageHistoryPage.parseFrom(response.getPayload());
        if (page.getMessagesCount() != 0 || page.getLatestSequence() != 0) {
            throw new IllegalStateException("fresh benchmark conversation was not empty");
        }
    }

    private static TimedRoundTrip roundTrip(
            ClientConnection sender, List<ClientConnection> peers, UUID conversation,
            String clientMessageId, int payloadBytes, long expectedSequence) throws Exception {
        byte[] content = new byte[payloadBytes];
        java.util.Arrays.fill(content, (byte) 'm');
        SubmitMessage payload = SubmitMessage.newBuilder()
                .setConversationId(conversation.toString())
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFrom(content))
                .build();
        Envelope request = command(MessageType.MESSAGE_TYPE_SUBMIT_MESSAGE,
                "submit-" + clientMessageId, sender.sessionId(), clientMessageId,
                payload.toByteString());
        long started = System.nanoTime();
        send(sender.socket(), request);
        Envelope acknowledgement = sender.listener().next();
        long acknowledgementMicros = elapsedMicros(started);
        requireType(acknowledgement, MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED);
        MessageAccepted accepted = MessageAccepted.parseFrom(acknowledgement.getPayload());
        if (accepted.getDuplicate() || accepted.getConversationSequence() != expectedSequence
                || !accepted.getConversationId().equals(conversation.toString())) {
            throw new IllegalStateException("gateway acknowledgement did not reconcile");
        }
        for (ClientConnection peer : peers) {
            Envelope publication = peer.listener().next();
            requireType(publication, MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED);
            MessageRecord record = MessageRecord.parseFrom(publication.getPayload());
            if (record.getConversationSequence() != expectedSequence
                    || !record.getConversationId().equals(conversation.toString())
                    || !record.getClientMessageId().equals(clientMessageId)
                    || record.getContent().size() != payloadBytes) {
                throw new IllegalStateException("gateway live publication did not reconcile");
            }
        }
        long fanoutMicros = elapsedMicros(started);
        return new TimedRoundTrip(acknowledgementMicros, fanoutMicros);
    }

    private static void send(WebSocket socket, Envelope envelope) {
        socket.sendBinary(ByteBuffer.wrap(envelope.toByteArray()), true).join();
    }

    private static Envelope clientHello(String deviceId) {
        ClientHello payload = ClientHello.newBuilder()
                .setMinimumProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setMaximumProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setPlatform(ClientPlatform.CLIENT_PLATFORM_WINDOWS)
                .setAppVersion("gateway-performance-baseline")
                .setClientDeviceId(deviceId)
                .build();
        return command(MessageType.MESSAGE_TYPE_CLIENT_HELLO, "hello-" + deviceId,
                "", "", payload.toByteString());
    }

    private static Envelope authenticate(String login) {
        Authenticate payload = Authenticate.newBuilder()
                .setUsername(login)
                .setPasswordUtf8(ByteString.copyFromUtf8(PASSWORD))
                .build();
        return command(MessageType.MESSAGE_TYPE_AUTHENTICATE, "auth-" + login,
                "", "", payload.toByteString());
    }

    private static Envelope command(
            MessageType type, String requestId, String sessionId,
            String clientMessageId, ByteString payload) {
        return Envelope.newBuilder()
                .setProtocolVersion(EnvelopePolicy.PROTOCOL_VERSION)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(type.getNumber())
                .setRequestId(requestId)
                .setSessionId(sessionId)
                .setClientMessageId(clientMessageId)
                .setSentAtEpochMs(System.currentTimeMillis())
                .setPayload(payload)
                .build();
    }

    private static void requireType(Envelope envelope, MessageType type) {
        if (envelope.getMessageType() != type.getNumber()) {
            throw new IllegalStateException("unexpected gateway response type: "
                    + envelope.getMessageType());
        }
    }

    private static GatewayRuntimeConfig runtimeConfiguration(Configuration configuration) {
        Map<String, String> environment = new HashMap<>();
        environment.put("CHATROOM_GATEWAY_PORT", Integer.toString(configuration.gatewayPort()));
        environment.put("CHATROOM_GATEWAY_ADMIN_PORT", Integer.toString(configuration.adminPort()));
        environment.put("CHATROOM_GATEWAY_TLS_CERTIFICATE", configuration.certificate().toString());
        environment.put("CHATROOM_GATEWAY_TLS_PRIVATE_KEY", configuration.privateKey().toString());
        environment.put("CHATROOM_GATEWAY_ALLOWED_HOSTS",
                "localhost:" + configuration.gatewayPort());
        environment.put("CHATROOM_GATEWAY_WEB_ORIGINS", "https://chat.example.test");
        environment.put("CHATROOM_POSTGRES_URL", configuration.jdbcUrl());
        environment.put("CHATROOM_POSTGRES_USER", configuration.username());
        environment.put("CHATROOM_POSTGRES_PASSWORD", "disposable-trust-password");
        environment.put("CHATROOM_POSTGRES_ALLOW_INSECURE_LOCAL", "true");
        environment.put("CHATROOM_POSTGRES_POOL_MAXIMUM", "8");
        environment.put("CHATROOM_POSTGRES_POOL_MINIMUM_IDLE", "1");
        return GatewayRuntimeConfig.fromEnvironment(environment);
    }

    private static void seed(
            Configuration configuration, UUID sender, List<UUID> peers, UUID conversation)
            throws SQLException {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(configuration.jdbcUrl());
        dataSource.setUser(configuration.username());
        dataSource.setPassword(configuration.password());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'gateway-sender', 'Gateway Sender', ?)",
                    sender, PASSWORD_HASH);
            for (int index = 0; index < peers.size(); ++index) {
                execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                        + "password_hash) VALUES (?, ?, ?, ?)", peers.get(index),
                        "gateway-peer-" + index, "Gateway Peer " + index, PASSWORD_HASH);
            }
            if (configuration.receivers() == 1) {
                execute(connection,
                        "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')",
                        conversation);
            } else {
                execute(connection, "INSERT INTO chat.conversation(id, kind, title) "
                        + "VALUES (?, 'GROUP', 'Gateway Benchmark Group')", conversation);
            }
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, role) VALUES (?, ?, ?)", conversation, sender,
                    configuration.receivers() == 1 ? "MEMBER" : "OWNER");
            for (UUID peer : peers) {
                execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                        + "account_id) VALUES (?, ?)", conversation, peer);
            }
            connection.commit();
        }
    }

    private static void requireMessageState(
            Configuration configuration, UUID conversation, long expected) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                configuration.jdbcUrl(), configuration.username(), configuration.password());
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*), max(conversation_sequence), "
                                + "(SELECT next_sequence FROM chat.conversation WHERE id = ?) "
                                + "FROM chat.message WHERE conversation_id = ?")) {
            statement.setObject(1, conversation);
            statement.setObject(2, conversation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != expected
                        || result.getLong(2) != expected || result.getLong(3) != expected + 1L) {
                    throw new IllegalStateException("gateway durable message state did not reconcile");
                }
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; ++index) {
                statement.setObject(index + 1, values[index]);
            }
            if (statement.executeUpdate() != 1) {
                throw new SQLException("benchmark seed did not affect one row");
            }
        }
    }

    private static void write(
            Configuration configuration, Instant startedAt, Duration wall,
            long cpuNanos, long peakHeap, long durableMessages,
            List<Long> setupMicros, List<Long> acknowledgementMicros,
            List<Long> fanoutMicros, long measuredNanos, ReconnectResult reconnect)
            throws IOException {
        Path parent = configuration.output().toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (JsonGenerator json = new JsonFactory().createGenerator(
                Files.newOutputStream(configuration.output()))) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            boolean group = configuration.receivers() > 1;
            boolean reconnectMeasured = configuration.reconnectRounds() > 0;
            json.writeNumberField("schemaVersion", reconnectMeasured ? 3 : (group ? 2 : 1));
            json.writeStringField("benchmark", "java-v2-gateway-messaging");
            json.writeStringField("startedAt", startedAt.toString());
            json.writeStringField("warning", "loopback development evidence; not a capacity claim");
            json.writeObjectFieldStart("environment");
            json.writeStringField("javaVersion", System.getProperty("java.version"));
            json.writeStringField("vm", System.getProperty("java.vm.name"));
            json.writeStringField("os", System.getProperty("os.name"));
            json.writeStringField("osVersion", System.getProperty("os.version"));
            json.writeStringField("architecture", System.getProperty("os.arch"));
            json.writeNumberField("availableProcessors", Runtime.getRuntime().availableProcessors());
            json.writeNumberField("maximumHeapBytes", Runtime.getRuntime().maxMemory());
            json.writeNumberField("peakObservedHeapBytes", peakHeap);
            json.writeNumberField("processCpuSeconds", round(cpuNanos / 1_000_000_000.0));
            json.writeNumberField("scenarioWallSeconds", round(wall.toNanos() / 1_000_000_000.0));
            json.writeEndObject();
            json.writeObjectFieldStart("scenario");
            json.writeNumberField("connections", configuration.receivers() + 1);
            json.writeNumberField("receiversPerMessage", configuration.receivers());
            if (group) json.writeStringField("conversationKind", "GROUP");
            json.writeNumberField("warmupOperations", configuration.warmupOperations());
            json.writeNumberField("messageOperations", configuration.messageOperations());
            json.writeNumberField("payloadBytes", configuration.payloadBytes());
            json.writeNumberField("durableMessages", durableMessages);
            if (reconnectMeasured) {
                json.writeNumberField("reconnectRounds", configuration.reconnectRounds());
                json.writeNumberField("reconnectOperations", reconnect.latencyMicros().size());
            }
            json.writeEndObject();
            json.writeObjectFieldStart("results");
            distribution(json, "connectionSetupLatencyMicros", setupMicros);
            distribution(json, "submitToAcceptLatencyMicros", acknowledgementMicros);
            distribution(json, group
                    ? "submitToAllPeersPublishedLatencyMicros"
                    : "submitToPeerPublishLatencyMicros", fanoutMicros);
            json.writeNumberField("completedMessageThroughputPerSecond",
                    throughput(configuration.messageOperations(), measuredNanos));
            if (group) json.writeNumberField("peerPublications",
                    (long) configuration.messageOperations() * configuration.receivers());
            if (reconnectMeasured) {
                distribution(json, "sessionResumeLatencyMicros", reconnect.latencyMicros());
                json.writeNumberField("sessionResumeThroughputPerSecond",
                        throughput(reconnect.latencyMicros().size(), reconnect.elapsedNanos()));
                json.writeNumberField("resumeErrors", reconnect.errors());
            }
            json.writeNumberField("errors", 0);
            json.writeEndObject();
            json.writeEndObject();
        }
    }

    private static void distribution(JsonGenerator json, String name, List<Long> samples)
            throws IOException {
        if (samples.isEmpty()) throw new IllegalArgumentException("samples must not be empty");
        List<Long> ordered = new ArrayList<>(samples);
        Collections.sort(ordered);
        double mean = ordered.stream().mapToLong(Long::longValue).average().orElseThrow();
        json.writeObjectFieldStart(name);
        json.writeNumberField("samples", ordered.size());
        json.writeNumberField("min", ordered.getFirst());
        json.writeNumberField("p50", percentile(ordered, 0.50));
        json.writeNumberField("p95", percentile(ordered, 0.95));
        json.writeNumberField("p99", percentile(ordered, 0.99));
        json.writeNumberField("max", ordered.getLast());
        json.writeNumberField("mean", round(mean));
        json.writeEndObject();
    }

    private static long percentile(List<Long> ordered, double quantile) {
        int index = (int) Math.ceil(quantile * ordered.size()) - 1;
        return ordered.get(Math.max(0, Math.min(index, ordered.size() - 1)));
    }

    private static double throughput(int operations, long elapsedNanos) {
        return round(operations / (elapsedNanos / 1_000_000_000.0));
    }

    private static double round(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long elapsedMicros(long startedNanos) {
        return Math.max(1L, TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedNanos));
    }

    private static SSLContext trustAllTls() throws Exception {
        TrustManager[] trustManagers = {new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
            @Override public void checkClientTrusted(
                    X509Certificate[] chain, String authenticationType) {
            }
            @Override public void checkServerTrusted(
                    X509Certificate[] chain, String authenticationType) {
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        return context;
    }

    private static void requireLoopback(String jdbcUrl) {
        if (!(jdbcUrl.startsWith("jdbc:postgresql://127.0.0.1:")
                || jdbcUrl.startsWith("jdbc:postgresql://[::1]:"))) {
            throw new IllegalArgumentException("gateway baseline requires loopback PostgreSQL");
        }
    }

    private record TimedRoundTrip(long acknowledgementMicros, long fanoutMicros) {}

    private record ResumeAttempt(int position, ClientConnection connection, long latencyMicros) {}

    private record ReconnectResult(
            List<ClientConnection> connections, List<Long> latencyMicros,
            long elapsedNanos, int errors) {
        private static final ReconnectResult NONE =
                new ReconnectResult(List.of(), List.of(), 0, 0);

        private ReconnectResult {
            connections = List.copyOf(connections);
            latencyMicros = List.copyOf(latencyMicros);
        }
    }

    private record ClientConnection(
            HttpClient client, WebSocket socket, EnvelopeListener listener,
            SessionEstablished session, String deviceId)
            implements AutoCloseable {
        private ClientConnection {
            Objects.requireNonNull(client, "client");
            Objects.requireNonNull(socket, "socket");
            Objects.requireNonNull(listener, "listener");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(deviceId, "deviceId");
        }

        private String sessionId() {
            return session.getSessionId();
        }

        @Override public void close() {
            socket.abort();
        }
    }

    private static final class EnvelopeListener implements WebSocket.Listener {
        private final BlockingQueue<Envelope> envelopes = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();

        @Override public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override public synchronized CompletionStage<?> onBinary(
                WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            fragments.write(bytes, 0, bytes.length);
            if (last) {
                try {
                    envelopes.add(Envelope.parseFrom(fragments.toByteArray()));
                } catch (Exception exception) {
                    failure.compareAndSet(null, exception);
                } finally {
                    fragments.reset();
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            failure.compareAndSet(null, error);
        }

        private Envelope next() throws Exception {
            Envelope envelope = envelopes.poll(10, TimeUnit.SECONDS);
            Throwable error = failure.get();
            if (error != null) throw new IllegalStateException("WebSocket listener failed", error);
            if (envelope == null) throw new IllegalStateException("timed out waiting for gateway");
            return envelope;
        }
    }

    private record Configuration(
            String jdbcUrl, String username, String password,
            Path certificate, Path privateKey, int gatewayPort, int adminPort,
            Path output, int warmupOperations, int messageOperations,
            int payloadBytes, int receivers, int reconnectRounds) {
        private Configuration {
            Objects.requireNonNull(jdbcUrl, "jdbcUrl");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(certificate, "certificate");
            Objects.requireNonNull(privateKey, "privateKey");
            Objects.requireNonNull(output, "output");
            if (!Files.isRegularFile(certificate) || !Files.isRegularFile(privateKey)) {
                throw new IllegalArgumentException("gateway TLS files must exist");
            }
            bounded("gateway port", gatewayPort, 1, 65_535);
            bounded("admin port", adminPort, 1, 65_535);
            if (gatewayPort == adminPort) throw new IllegalArgumentException("ports must differ");
            bounded("warmup", warmupOperations, 0, 10_000);
            bounded("messages", messageOperations, 1, 100_000);
            bounded("payload bytes", payloadBytes, 1, 1_048_576);
            // The default gateway allows 60 authentication attempts per direct peer;
            // the sender consumes one and the benchmark must not weaken that policy.
            bounded("receivers", receivers, 1, 59);
            bounded("reconnect rounds", reconnectRounds, 0, 20);
            long authenticationAttempts = (long) (receivers + 1) * (reconnectRounds + 1);
            if (authenticationAttempts > 60) {
                throw new IllegalArgumentException(
                        "initial authentication plus resumes exceed the default peer window");
            }
        }

        private static Configuration parse(String[] arguments) {
            if (arguments.length % 2 != 0) {
                throw new IllegalArgumentException("arguments must be --name value pairs");
            }
            Map<String, String> values = new HashMap<>();
            for (int index = 0; index < arguments.length; index += 2) {
                if (!arguments[index].startsWith("--")
                        || values.put(arguments[index], arguments[index + 1]) != null) {
                    throw new IllegalArgumentException("invalid or duplicate argument");
                }
            }
            java.util.Set<String> expected = java.util.Set.of(
                    "--jdbc-url", "--username", "--password", "--certificate",
                    "--private-key", "--gateway-port", "--admin-port", "--output",
                    "--warmup", "--messages", "--payload-bytes", "--receivers",
                    "--reconnect-rounds");
            if (!values.keySet().equals(expected)) {
                throw new IllegalArgumentException("missing or unknown gateway argument");
            }
            try {
                return new Configuration(
                        values.get("--jdbc-url"), values.get("--username"),
                        values.get("--password"), Path.of(values.get("--certificate")),
                        Path.of(values.get("--private-key")),
                        Integer.parseInt(values.get("--gateway-port")),
                        Integer.parseInt(values.get("--admin-port")),
                        Path.of(values.get("--output")),
                        Integer.parseInt(values.get("--warmup")),
                        Integer.parseInt(values.get("--messages")),
                        Integer.parseInt(values.get("--payload-bytes")),
                        Integer.parseInt(values.get("--receivers")),
                        Integer.parseInt(values.get("--reconnect-rounds")));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("gateway counts must be integers", exception);
            }
        }

        private static void bounded(String name, int value, int minimum, int maximum) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "%s must be in %d..%d", name, minimum, maximum));
            }
        }
    }
}

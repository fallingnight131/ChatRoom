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
import com.fallingnight.chat.protocol.v2.ProtocolError;
import com.fallingnight.chat.protocol.v2.ProtocolErrorCode;
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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicLong;
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
        List<UUID> saturationAccounts = new ArrayList<>(configuration.postgresSaturationSenders());
        for (int index = 0; index < configuration.postgresSaturationSenders(); ++index) {
            saturationAccounts.add(UUID.randomUUID());
        }
        List<UUID> conversations = new ArrayList<>(configuration.activeConversations());
        for (int index = 0; index < configuration.activeConversations(); ++index) {
            conversations.add(UUID.randomUUID());
        }
        seed(configuration, senderAccount, peerAccounts, saturationAccounts, conversations);

        OperatingSystemMXBean operatingSystem = (OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = operatingSystem.getProcessCpuTime();
        Instant startedAt = Instant.now();
        long peakHeap = usedHeap();
        GatewayRuntime runtime = null;
        ClientConnection sender = null;
        List<ClientConnection> peers = new ArrayList<>(configuration.receivers());
        List<ClientConnection> saturationSenders =
                new ArrayList<>(configuration.postgresSaturationSenders());
        List<Long> conversationActivationMicros = new ArrayList<>(configuration.receivers());
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
                long activationStarted = System.nanoTime();
                for (UUID conversation : conversations) catchUp(peer, conversation);
                conversationActivationMicros.add(elapsedMicros(activationStarted));
                peers.add(peer);
            }
            for (int index = 0; index < configuration.postgresSaturationSenders(); ++index) {
                long setupStart = System.nanoTime();
                saturationSenders.add(connectAndAuthenticate(configuration,
                        "gateway-saturation-" + index, "saturation-device-" + index));
                setupMicros.add(elapsedMicros(setupStart));
            }

            for (int index = 0; index < configuration.warmupOperations(); ++index) {
                int conversationIndex = index % conversations.size();
                long expectedSequence = index / conversations.size() + 1L;
                roundTrip(sender, peers, conversations.get(conversationIndex),
                        "warmup-" + index, configuration.payloadBytes(), expectedSequence);
            }
            List<Long> acknowledgementMicros = new ArrayList<>(configuration.messageOperations());
            List<Long> fanoutMicros = new ArrayList<>(configuration.messageOperations());
            long measuredStart = System.nanoTime();
            for (int index = 0; index < configuration.messageOperations(); ++index) {
                int conversationIndex = index % conversations.size();
                long expectedSequence = configuration.warmupOperations()
                        / conversations.size() + index / conversations.size() + 1L;
                TimedRoundTrip result = roundTrip(
                        sender, peers, conversations.get(conversationIndex), "measured-" + index,
                        configuration.payloadBytes(), expectedSequence);
                acknowledgementMicros.add(result.acknowledgementMicros());
                fanoutMicros.add(result.fanoutMicros());
                peakHeap = Math.max(peakHeap, usedHeap());
            }
            long measuredNanos = System.nanoTime() - measuredStart;
            long durableMessages = (long) configuration.warmupOperations()
                    + configuration.messageOperations();
            requireMessageState(configuration, conversations,
                    durableMessages / conversations.size());
            UUID conversation = conversations.getFirst();
            SlowConsumerResult slowConsumer = slowConsumer(
                    configuration, sender, peers, conversation, durableMessages);
            if (slowConsumer.measured()) {
                peers.set(peers.size() - 1, slowConsumer.recoveredConnection());
                durableMessages += slowConsumer.durableMessages();
                requireMessageState(configuration, conversation, durableMessages);
            }
            PostgresSaturationResult saturation = postgresSaturation(
                    configuration, saturationSenders, peers, conversation, durableMessages);
            if (saturation.measured()) {
                durableMessages += saturationSenders.size();
                requireMessageState(configuration, conversation, durableMessages);
            }
            PostgresOutageResult outage = postgresOutage(
                    configuration, sender, peers, conversation, durableMessages);
            if (outage.measured()) {
                durableMessages += 1L;
                requireMessageState(configuration, conversation, durableMessages);
            }
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
                    setupMicros, conversationActivationMicros,
                    acknowledgementMicros, fanoutMicros, measuredNanos, reconnect,
                    slowConsumer, saturation, outage);
        } finally {
            for (ClientConnection saturationSender : saturationSenders) {
                saturationSender.close();
            }
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
        List<Long> arrivalJitterMicros = new ArrayList<>(
                current.size() * configuration.reconnectRounds());
        long measuredStart = System.nanoTime();
        for (int round = 0; round < configuration.reconnectRounds(); ++round) {
            for (ClientConnection connection : current) connection.close();
            CountDownLatch ready = new CountDownLatch(current.size());
            CountDownLatch start = new CountDownLatch(1);
            AtomicLong roundStart = new AtomicLong();
            List<Future<ResumeAttempt>> futures = new ArrayList<>(current.size());
            try (ExecutorService executor = Executors.newFixedThreadPool(current.size())) {
                for (int index = 0; index < current.size(); ++index) {
                    int position = index;
                    ClientConnection previous = current.get(index);
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        int batch = configuration.reconnectBatchSize() == 0
                                ? 0 : position / configuration.reconnectBatchSize();
                        long scheduledOffsetNanos = TimeUnit.MILLISECONDS.toNanos(
                                (long) batch * configuration.reconnectBatchIntervalMillis());
                        waitUntil(roundStart.get() + scheduledOffsetNanos);
                        long started = System.nanoTime();
                        ClientConnection resumed = connectAndResume(configuration, previous);
                        long jitterMicros = Math.max(1L, TimeUnit.NANOSECONDS.toMicros(
                                Math.abs(started - roundStart.get() - scheduledOffsetNanos)));
                        return new ResumeAttempt(
                                position, resumed, elapsedMicros(started), jitterMicros);
                    }));
                }
                if (!ready.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("reconnect workers did not become ready");
                }
                roundStart.set(System.nanoTime());
                start.countDown();
                List<ResumeAttempt> attempts = new ArrayList<>(current.size());
                for (Future<ResumeAttempt> future : futures) attempts.add(get(future));
                attempts.sort(java.util.Comparator.comparingInt(ResumeAttempt::position));
                current = attempts.stream().map(ResumeAttempt::connection).toList();
                attempts.forEach(attempt -> latencies.add(attempt.latencyMicros()));
                attempts.forEach(attempt -> arrivalJitterMicros.add(
                        attempt.arrivalJitterMicros()));
            } catch (Exception exception) {
                closeCompletedReconnects(futures);
                throw exception;
            }
        }
        return new ReconnectResult(current, List.copyOf(latencies),
                List.copyOf(arrivalJitterMicros), System.nanoTime() - measuredStart, 0);
    }

    private static void waitUntil(long deadlineNanos) {
        long remaining;
        while ((remaining = deadlineNanos - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
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

    private static SlowConsumerResult slowConsumer(
            Configuration configuration,
            ClientConnection sender,
            List<ClientConnection> peers,
            UUID conversation,
            long initialSequence) throws Exception {
        if (configuration.slowConsumerMaxMessages() == 0) {
            return SlowConsumerResult.NONE;
        }
        ClientConnection slow = peers.getLast();
        List<ClientConnection> healthy = List.copyOf(
                peers.subList(0, peers.size() - 1));
        slow.listener().pauseDemand();
        List<Long> healthyLatencies = new ArrayList<>();
        int sent = 0;
        for (int index = 0; index < configuration.slowConsumerMaxMessages(); ++index) {
            long expectedSequence = initialSequence + index + 1L;
            TimedRoundTrip result = roundTrip(
                    sender, healthy, conversation, "slow-" + index,
                    configuration.payloadBytes(), expectedSequence);
            healthyLatencies.add(result.fanoutMicros());
            sent += 1;
            if (metric(configuration, "live_slow_consumer_closed") == 1L) break;
        }
        long closures = metric(configuration, "live_slow_consumer_closed");
        if (closures != 1L) {
            throw new IllegalStateException(
                    "slow consumer did not cross the production write watermark");
        }

        slow.close();
        ClientConnection recovered = connectAndResume(configuration, slow);
        try {
            recoverHistory(recovered, conversation, initialSequence, sent,
                    configuration.payloadBytes());
            List<ClientConnection> recoveredPeers = new ArrayList<>(healthy.size() + 1);
            recoveredPeers.addAll(healthy);
            recoveredPeers.add(recovered);
            TimedRoundTrip probe = roundTrip(
                    sender, recoveredPeers, conversation, "slow-recovery-probe",
                    configuration.payloadBytes(), initialSequence + sent + 1L);
            return new SlowConsumerResult(
                    recovered, sent, List.copyOf(healthyLatencies),
                    probe.fanoutMicros(), closures);
        } catch (Exception exception) {
            recovered.close();
            throw exception;
        }
    }

    private static PostgresSaturationResult postgresSaturation(
            Configuration configuration,
            List<ClientConnection> senders,
            List<ClientConnection> peers,
            UUID conversation,
            long initialSequence) throws Exception {
        if (senders.isEmpty()) return PostgresSaturationResult.NONE;
        CountDownLatch ready = new CountDownLatch(senders.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SaturationAttempt>> futures = new ArrayList<>(senders.size());
        try (ExecutorService executor = Executors.newFixedThreadPool(senders.size())) {
            for (int index = 0; index < senders.size(); ++index) {
                int position = index;
                ClientConnection sender = senders.get(index);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return saturationAttempt(sender, conversation, position);
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("saturation workers did not become ready");
            }
            start.countDown();
            awaitMetricAtLeast(configuration, "chat_gateway_messaging_workers_active", 2);
            int unavailableStatus = readinessStatus(configuration);
            if (unavailableStatus != 503) {
                throw new IllegalStateException(
                        "gateway remained ready while the PostgreSQL pool was saturated");
            }
            List<SaturationAttempt> attempts = new ArrayList<>(senders.size());
            for (Future<SaturationAttempt> future : futures) attempts.add(getSaturation(future));
            long retryableFailures = attempts.stream().filter(attempt -> !attempt.accepted()).count();
            if (retryableFailures < 1 || retryableFailures >= attempts.size()) {
                throw new IllegalStateException(
                        "saturation did not produce both accepted and retryable outcomes");
            }
            removeSaturationTrigger(configuration);
            int recoveredStatus = awaitReadiness(configuration);
            List<SaturationAttempt> converged = new ArrayList<>(attempts.size());
            for (SaturationAttempt attempt : attempts) {
                if (attempt.accepted()) {
                    converged.add(attempt);
                    continue;
                }
                SaturationAttempt retry = saturationAttempt(
                        senders.get(attempt.position()), conversation, attempt.position());
                if (!retry.accepted()) {
                    throw new IllegalStateException("saturation retry did not recover");
                }
                converged.add(retry);
            }
            validateSaturationAttempts(converged, initialSequence);
            validateSaturationPublications(
                    peers, converged, conversation, initialSequence, senders.size());
            return new PostgresSaturationResult(
                    attempts.stream().map(SaturationAttempt::latencyMicros).toList(),
                    unavailableStatus,
                    recoveredStatus,
                    (long) peers.size() * senders.size(),
                    Math.toIntExact(retryableFailures),
                    Math.toIntExact(retryableFailures),
                    0);
        }
    }

    private static SaturationAttempt saturationAttempt(
            ClientConnection sender, UUID conversation, int position) throws Exception {
        String clientMessageId = "saturation-" + position;
        SubmitMessage payload = SubmitMessage.newBuilder()
                .setConversationId(conversation.toString())
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8("database saturation"))
                .build();
        long started = System.nanoTime();
        send(sender.socket(), command(MessageType.MESSAGE_TYPE_SUBMIT_MESSAGE,
                "submit-" + clientMessageId, sender.sessionId(), clientMessageId,
                payload.toByteString()));
        Envelope response = sender.listener().next();
        long latency = elapsedMicros(started);
        if (response.getMessageType() == MessageType.MESSAGE_TYPE_PROTOCOL_ERROR_VALUE) {
            ProtocolError error = ProtocolError.parseFrom(response.getPayload());
            if (error.getCode() != ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR
                    || !error.getRetryable()) {
                throw new IllegalStateException("saturation failure was not safely retryable");
            }
            return new SaturationAttempt(position, clientMessageId, 0, latency, false);
        }
        requireType(response, MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED);
        MessageAccepted accepted = MessageAccepted.parseFrom(response.getPayload());
        if (accepted.getDuplicate()
                || !accepted.getConversationId().equals(conversation.toString())) {
            throw new IllegalStateException("saturation acknowledgement did not reconcile");
        }
        return new SaturationAttempt(position, clientMessageId,
                accepted.getConversationSequence(), latency, true);
    }

    private static PostgresOutageResult postgresOutage(
            Configuration configuration,
            ClientConnection sender,
            List<ClientConnection> peers,
            UUID conversation,
            long initialSequence) throws Exception {
        if (!configuration.postgresOutage()) return PostgresOutageResult.NONE;
        signal(configuration.postgresOutageControlDir(), "postgres-stop-request");
        awaitSignal(configuration.postgresOutageControlDir(), "postgres-stopped");

        String clientMessageId = "postgres-outage-retry";
        SubmitMessage payload = SubmitMessage.newBuilder()
                .setConversationId(conversation.toString())
                .setContentType(MessageContentType.MESSAGE_CONTENT_TYPE_TEXT_UTF8_VALUE)
                .setContent(ByteString.copyFromUtf8("database outage recovery"))
                .build();
        Envelope request = command(MessageType.MESSAGE_TYPE_SUBMIT_MESSAGE,
                "submit-" + clientMessageId, sender.sessionId(), clientMessageId,
                payload.toByteString());
        long failureStarted = System.nanoTime();
        send(sender.socket(), request);
        Envelope failureResponse = sender.listener().next();
        long failureMicros = elapsedMicros(failureStarted);
        requireType(failureResponse, MessageType.MESSAGE_TYPE_PROTOCOL_ERROR);
        ProtocolError error = ProtocolError.parseFrom(failureResponse.getPayload());
        if (error.getCode() != ProtocolErrorCode.PROTOCOL_ERROR_CODE_INTERNAL_ERROR
                || !error.getRetryable()
                || !error.getSafeMessage().equals("messaging is temporarily unavailable")) {
            throw new IllegalStateException("database outage failure was not safely retryable");
        }
        int unavailableStatus = readinessStatus(configuration);
        if (unavailableStatus != 503) {
            throw new IllegalStateException("gateway remained ready during database outage");
        }
        int availableLivenessStatus = livenessStatus(configuration);
        if (availableLivenessStatus != 200) {
            throw new IllegalStateException("database outage made gateway liveness unavailable");
        }
        if (sender.socket().isInputClosed() || sender.socket().isOutputClosed()) {
            throw new IllegalStateException("database outage closed the authenticated connection");
        }

        long recoveryStarted = System.nanoTime();
        signal(configuration.postgresOutageControlDir(), "postgres-start-request");
        awaitSignal(configuration.postgresOutageControlDir(), "postgres-started");
        int recoveredStatus = awaitReadiness(configuration);
        send(sender.socket(), request);
        Envelope retryResponse = sender.listener().next();
        requireType(retryResponse, MessageType.MESSAGE_TYPE_MESSAGE_ACCEPTED);
        MessageAccepted accepted = MessageAccepted.parseFrom(retryResponse.getPayload());
        long expectedSequence = initialSequence + 1L;
        if (accepted.getDuplicate()
                || accepted.getConversationSequence() != expectedSequence
                || !accepted.getConversationId().equals(conversation.toString())) {
            throw new IllegalStateException("database outage retry did not reconcile");
        }
        for (ClientConnection peer : peers) {
            Envelope publication = peer.listener().next();
            requireType(publication, MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED);
            MessageRecord record = MessageRecord.parseFrom(publication.getPayload());
            if (record.getConversationSequence() != expectedSequence
                    || !record.getConversationId().equals(conversation.toString())
                    || !record.getClientMessageId().equals(clientMessageId)) {
                throw new IllegalStateException("database outage publication did not reconcile");
            }
        }
        return new PostgresOutageResult(
                failureMicros, elapsedMicros(recoveryStarted), unavailableStatus,
                availableLivenessStatus, recoveredStatus, peers.size(), 1, 1, 0);
    }

    private static void signal(Path directory, String name) throws IOException {
        Files.writeString(directory.resolve(name), name + "\n");
    }

    private static void awaitSignal(Path directory, String name) throws Exception {
        Path marker = directory.resolve(name);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(marker)) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("timed out waiting for PostgreSQL control: " + name);
    }

    private static void removeSaturationTrigger(Configuration configuration) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                configuration.jdbcUrl(), configuration.username(), configuration.password());
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER gateway_performance_delay ON chat.message");
            statement.execute("DROP FUNCTION chat.gateway_performance_delay()");
        }
    }

    private static SaturationAttempt getSaturation(Future<SaturationAttempt> future)
            throws Exception {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            throw new IllegalStateException("saturation worker failed", cause);
        }
    }

    private static void validateSaturationAttempts(
            List<SaturationAttempt> attempts, long initialSequence) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<Long> sequences = new java.util.HashSet<>();
        for (SaturationAttempt attempt : attempts) {
            ids.add(attempt.clientMessageId());
            sequences.add(attempt.sequence());
        }
        for (int index = 0; index < attempts.size(); ++index) {
            if (!ids.contains("saturation-" + index)
                    || !sequences.contains(initialSequence + index + 1L)) {
                throw new IllegalStateException("saturation durable identities did not reconcile");
            }
        }
    }

    private static void validateSaturationPublications(
            List<ClientConnection> peers,
            List<SaturationAttempt> attempts,
            UUID conversation,
            long initialSequence,
            int senders) throws Exception {
        for (ClientConnection peer : peers) {
            java.util.Set<String> ids = new java.util.HashSet<>();
            java.util.Set<Long> sequences = new java.util.HashSet<>();
            for (int index = 0; index < senders; ++index) {
                Envelope publication = peer.listener().next();
                requireType(publication, MessageType.MESSAGE_TYPE_MESSAGE_PUBLISHED);
                MessageRecord record = MessageRecord.parseFrom(publication.getPayload());
                if (!record.getConversationId().equals(conversation.toString())) {
                    throw new IllegalStateException("saturation publication conversation differed");
                }
                ids.add(record.getClientMessageId());
                sequences.add(record.getConversationSequence());
            }
            if (ids.size() != attempts.size() || sequences.size() != attempts.size()) {
                throw new IllegalStateException("saturation publications were duplicated");
            }
            for (int index = 0; index < attempts.size(); ++index) {
                if (!ids.contains("saturation-" + index)
                        || !sequences.contains(initialSequence + index + 1L)) {
                    throw new IllegalStateException(
                            "saturation publications did not reconcile");
                }
            }
        }
    }

    private static void awaitMetricAtLeast(
            Configuration configuration, String metric, long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (scalarMetric(configuration, metric) >= expected) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("gateway metric did not reach saturation: " + metric);
    }

    private static int awaitReadiness(Configuration configuration) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int status;
        do {
            status = readinessStatus(configuration);
            if (status == 200) return status;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("gateway readiness did not recover");
    }

    private static int readinessStatus(Configuration configuration) throws Exception {
        return healthStatus(configuration, "/health/ready");
    }

    private static int livenessStatus(Configuration configuration) throws Exception {
        return healthStatus(configuration, "/health/live");
    }

    private static int healthStatus(Configuration configuration, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + configuration.adminPort() + path))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static long scalarMetric(Configuration configuration, String metric) throws Exception {
        String body = metrics(configuration);
        String prefix = metric + " ";
        for (String line : body.lines().toList()) {
            if (line.startsWith(prefix)) return Long.parseLong(line.substring(prefix.length()));
        }
        throw new IllegalStateException("gateway metric was absent: " + metric);
    }

    private static void recoverHistory(
            ClientConnection recovered,
            UUID conversation,
            long afterSequence,
            int expectedMessages,
            int payloadBytes) throws Exception {
        long cursor = afterSequence;
        int recoveredMessages = 0;
        long expectedLatest = afterSequence + expectedMessages;
        while (recoveredMessages < expectedMessages) {
            // Current compatible pages carry each message in both messages[] and entries[].
            // Four maximum-size text messages keep the encoded envelope below 1 MiB.
            int limit = Math.min(4, expectedMessages - recoveredMessages);
            ReadMessageHistory payload = ReadMessageHistory.newBuilder()
                    .setConversationId(conversation.toString())
                    .setAfterSequence(cursor)
                    .setLimit(limit)
                    .build();
            send(recovered.socket(), command(MessageType.MESSAGE_TYPE_READ_MESSAGE_HISTORY,
                    "slow-history-" + recoveredMessages, recovered.sessionId(), "",
                    payload.toByteString()));
            Envelope response = recovered.listener().next();
            requireType(response, MessageType.MESSAGE_TYPE_MESSAGE_HISTORY_PAGE);
            MessageHistoryPage page = MessageHistoryPage.parseFrom(response.getPayload());
            if (page.getMessagesCount() < 1 || page.getMessagesCount() > limit
                    || page.getLatestSequence() != expectedLatest
                    || page.getNextSequence() <= cursor) {
                throw new IllegalStateException("slow consumer history page did not reconcile");
            }
            for (MessageRecord record : page.getMessagesList()) {
                long expectedSequence = afterSequence + recoveredMessages + 1L;
                if (record.getConversationSequence() != expectedSequence
                        || !record.getClientMessageId().equals("slow-" + recoveredMessages)
                        || record.getContent().size() != payloadBytes) {
                    throw new IllegalStateException(
                            "slow consumer history message did not reconcile");
                }
                recoveredMessages += 1;
            }
            cursor = page.getNextSequence();
            if ((recoveredMessages < expectedMessages) != page.getHasMore()) {
                throw new IllegalStateException("slow consumer history continuation was invalid");
            }
        }
        if (cursor != expectedLatest) {
            throw new IllegalStateException("slow consumer final history cursor was invalid");
        }
    }

    private static long metric(Configuration configuration, String outcome) throws Exception {
        String body = metrics(configuration);
        String expected = "chat_gateway_messaging_total{outcome=\"" + outcome + "\"} ";
        for (String line : body.lines().toList()) {
            if (line.startsWith(expected)) return Long.parseLong(line.substring(expected.length()));
        }
        throw new IllegalStateException("gateway metric was absent: " + outcome);
    }

    private static String metrics(Configuration configuration) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + configuration.adminPort() + "/metrics"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("gateway metrics endpoint was unavailable");
        }
        return response.body();
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
        boolean databaseFailureScenario = configuration.postgresSaturationSenders() > 0
                || configuration.postgresOutage();
        environment.put("CHATROOM_POSTGRES_POOL_MAXIMUM",
                databaseFailureScenario ? "2" : "8");
        environment.put("CHATROOM_POSTGRES_POOL_MINIMUM_IDLE",
                databaseFailureScenario ? "2" : "1");
        if (databaseFailureScenario) {
            environment.put("CHATROOM_POSTGRES_CONNECTION_TIMEOUT_SECONDS", "1");
        }
        if (configuration.postgresSaturationSenders() > 0) {
            environment.put("CHATROOM_GATEWAY_MESSAGING_WORKERS",
                    Integer.toString(configuration.postgresSaturationSenders()));
            environment.put("CHATROOM_GATEWAY_MESSAGING_QUEUE_CAPACITY", "16");
        }
        return GatewayRuntimeConfig.fromEnvironment(environment);
    }

    private static void seed(
            Configuration configuration, UUID sender, List<UUID> peers,
            List<UUID> saturationSenders, List<UUID> conversations)
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
            for (int index = 0; index < saturationSenders.size(); ++index) {
                execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                                + "password_hash) VALUES (?, ?, ?, ?)", saturationSenders.get(index),
                        "gateway-saturation-" + index, "Gateway Saturation " + index,
                        PASSWORD_HASH);
            }
            for (int conversationIndex = 0;
                    conversationIndex < conversations.size(); ++conversationIndex) {
                UUID conversation = conversations.get(conversationIndex);
                if (!configuration.group()) {
                    execute(connection,
                            "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')",
                            conversation);
                } else {
                    execute(connection, "INSERT INTO chat.conversation(id, kind, title) "
                            + "VALUES (?, 'GROUP', ?)", conversation,
                            "Gateway Benchmark Group " + conversationIndex);
                }
                execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                        + "account_id, role) VALUES (?, ?, ?)", conversation, sender,
                        configuration.group() ? "OWNER" : "MEMBER");
                for (UUID peer : peers) {
                    execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                            + "account_id) VALUES (?, ?)", conversation, peer);
                }
                for (UUID saturationSender : saturationSenders) {
                    execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                            + "account_id) VALUES (?, ?)", conversation, saturationSender);
                }
            }
            if (!saturationSenders.isEmpty()) installSaturationTrigger(connection);
            connection.commit();
        }
    }

    private static void installSaturationTrigger(Connection connection) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION chat.gateway_performance_delay() RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                      IF NEW.client_message_id LIKE 'saturation-%' THEN
                        PERFORM pg_sleep(2);
                      END IF;
                      RETURN NEW;
                    END
                    $$
                    """);
            statement.execute("""
                    CREATE TRIGGER gateway_performance_delay
                    BEFORE INSERT ON chat.message
                    FOR EACH ROW EXECUTE FUNCTION chat.gateway_performance_delay()
                    """);
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

    private static void requireMessageState(
            Configuration configuration, List<UUID> conversations, long expectedPerConversation)
            throws SQLException {
        for (UUID conversation : conversations) {
            requireMessageState(configuration, conversation, expectedPerConversation);
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
            List<Long> setupMicros, List<Long> conversationActivationMicros,
            List<Long> acknowledgementMicros,
            List<Long> fanoutMicros, long measuredNanos, ReconnectResult reconnect,
            SlowConsumerResult slowConsumer, PostgresSaturationResult saturation,
            PostgresOutageResult outage)
            throws IOException {
        Path parent = configuration.output().toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (JsonGenerator json = new JsonFactory().createGenerator(
                Files.newOutputStream(configuration.output()))) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            boolean group = configuration.group();
            boolean reconnectMeasured = configuration.reconnectRounds() > 0;
            boolean slowConsumerMeasured = configuration.slowConsumerMaxMessages() > 0;
            boolean saturationMeasured = configuration.postgresSaturationSenders() > 0;
            boolean outageMeasured = configuration.postgresOutage();
            boolean activeConversationsMeasured = configuration.activeConversations() > 1;
            boolean pacedReconnectMeasured = configuration.reconnectBatchSize() > 0;
            json.writeNumberField("schemaVersion", pacedReconnectMeasured
                    ? 8 : (activeConversationsMeasured
                            ? 7 : (outageMeasured ? 6 : (saturationMeasured ? 5
                                    : (slowConsumerMeasured ? 4
                                            : (reconnectMeasured ? 3 : (group ? 2 : 1)))))));
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
            json.writeNumberField("connections", configuration.receivers() + 1
                    + configuration.postgresSaturationSenders());
            json.writeNumberField("receiversPerMessage", configuration.receivers());
            if (group) json.writeStringField("conversationKind", "GROUP");
            json.writeNumberField("warmupOperations", configuration.warmupOperations());
            json.writeNumberField("messageOperations", configuration.messageOperations());
            json.writeNumberField("payloadBytes", configuration.payloadBytes());
            json.writeNumberField("durableMessages", durableMessages);
            if (activeConversationsMeasured) {
                json.writeNumberField("activeConversations",
                        configuration.activeConversations());
                json.writeNumberField("memberships",
                        (long) configuration.activeConversations()
                                * (configuration.receivers() + 1));
                json.writeNumberField("routingSubscriptions",
                        (long) configuration.activeConversations()
                                * configuration.receivers());
                json.writeNumberField("durableMessagesPerConversation",
                        durableMessages / configuration.activeConversations());
            }
            if (reconnectMeasured) {
                json.writeNumberField("reconnectRounds", configuration.reconnectRounds());
                json.writeNumberField("reconnectOperations", reconnect.latencyMicros().size());
                if (pacedReconnectMeasured) {
                    int connections = configuration.receivers() + 1;
                    int batches = (connections + configuration.reconnectBatchSize() - 1)
                            / configuration.reconnectBatchSize();
                    json.writeNumberField("reconnectBatchSize",
                            configuration.reconnectBatchSize());
                    json.writeNumberField("reconnectBatchIntervalMillis",
                            configuration.reconnectBatchIntervalMillis());
                    json.writeNumberField("reconnectBatchesPerRound", batches);
                    json.writeNumberField("scheduledReconnectSpanMillis",
                            (long) (batches - 1)
                                    * configuration.reconnectBatchIntervalMillis());
                    json.writeNumberField("scheduledReconnectBatchRatePerSecond",
                            round(1000.0 / configuration.reconnectBatchIntervalMillis()));
                }
            }
            if (slowConsumerMeasured) {
                json.writeNumberField(
                        "slowConsumerMaxMessages", configuration.slowConsumerMaxMessages());
                json.writeNumberField(
                        "slowConsumerMessagesBeforeClosure", slowConsumer.messagesBeforeClosure());
                json.writeNumberField("slowConsumerHealthyReceivers",
                        configuration.receivers() - 1);
            }
            if (saturationMeasured) {
                json.writeNumberField(
                        "postgresSaturationSenders", configuration.postgresSaturationSenders());
                json.writeNumberField("postgresPoolMaximum", 2);
                json.writeNumberField("postgresConnectionTimeoutMillis", 1000);
                json.writeNumberField("postgresInjectedDelayMillis", 2000);
            }
            if (outageMeasured) {
                json.writeBooleanField("postgresOutage", true);
                json.writeBooleanField("postgresOutageRetryOnOriginalConnection", true);
                json.writeNumberField("postgresPoolMaximum", 2);
                json.writeNumberField("postgresConnectionTimeoutMillis", 1000);
            }
            json.writeEndObject();
            json.writeObjectFieldStart("results");
            distribution(json, "connectionSetupLatencyMicros", setupMicros);
            if (activeConversationsMeasured) {
                distribution(json, "conversationActivationLatencyMicros",
                        conversationActivationMicros);
            }
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
                if (pacedReconnectMeasured) {
                    distribution(json, "sessionResumeArrivalJitterMicros",
                            reconnect.arrivalJitterMicros());
                }
            }
            if (slowConsumerMeasured) {
                distribution(json, "slowConsumerHealthyPublishLatencyMicros",
                        slowConsumer.healthyPublishLatencyMicros());
                distribution(json, "slowConsumerRecoveryProbeLatencyMicros",
                        List.of(slowConsumer.recoveryProbeLatencyMicros()));
                json.writeNumberField("slowConsumerHealthyPeerPublications",
                        (long) slowConsumer.messagesBeforeClosure()
                                * (configuration.receivers() - 1));
                json.writeNumberField("slowConsumerRecoveredHistoryMessages",
                        slowConsumer.messagesBeforeClosure());
                json.writeNumberField("slowConsumerClosed", slowConsumer.closures());
                json.writeNumberField("slowConsumerErrors", 0);
            }
            if (saturationMeasured) {
                distribution(json, "postgresSaturationAcceptLatencyMicros",
                        saturation.latencyMicros());
                json.writeNumberField("postgresSaturationPeerPublications",
                        saturation.peerPublications());
                json.writeNumberField("postgresSaturationUnavailableReadinessStatus",
                        saturation.unavailableReadinessStatus());
                json.writeNumberField("postgresSaturationRecoveredReadinessStatus",
                        saturation.recoveredReadinessStatus());
                json.writeNumberField("postgresSaturationRetryableFailures",
                        saturation.retryableFailures());
                json.writeNumberField("postgresSaturationConvergedRetries",
                        saturation.convergedRetries());
                json.writeNumberField("postgresSaturationErrors", saturation.errors());
            }
            if (outageMeasured) {
                distribution(json, "postgresOutageFailureLatencyMicros",
                        List.of(outage.failureLatencyMicros()));
                distribution(json, "postgresOutageRecoveryLatencyMicros",
                        List.of(outage.recoveryLatencyMicros()));
                json.writeNumberField("postgresOutageUnavailableReadinessStatus",
                        outage.unavailableReadinessStatus());
                json.writeNumberField("postgresOutageAvailableLivenessStatus",
                        outage.availableLivenessStatus());
                json.writeNumberField("postgresOutageRecoveredReadinessStatus",
                        outage.recoveredReadinessStatus());
                json.writeNumberField("postgresOutagePeerPublications",
                        outage.peerPublications());
                json.writeNumberField("postgresOutageRetryableFailures",
                        outage.retryableFailures());
                json.writeNumberField("postgresOutageConvergedRetries",
                        outage.convergedRetries());
                json.writeNumberField("postgresOutageErrors", outage.errors());
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

    private record ResumeAttempt(
            int position, ClientConnection connection, long latencyMicros,
            long arrivalJitterMicros) {}

    private record ReconnectResult(
            List<ClientConnection> connections, List<Long> latencyMicros,
            List<Long> arrivalJitterMicros, long elapsedNanos, int errors) {
        private static final ReconnectResult NONE =
                new ReconnectResult(List.of(), List.of(), List.of(), 0, 0);

        private ReconnectResult {
            connections = List.copyOf(connections);
            latencyMicros = List.copyOf(latencyMicros);
            arrivalJitterMicros = List.copyOf(arrivalJitterMicros);
        }
    }

    private record SlowConsumerResult(
            ClientConnection recoveredConnection,
            int messagesBeforeClosure,
            List<Long> healthyPublishLatencyMicros,
            long recoveryProbeLatencyMicros,
            long closures) {
        private static final SlowConsumerResult NONE =
                new SlowConsumerResult(null, 0, List.of(), 0, 0);

        private SlowConsumerResult {
            healthyPublishLatencyMicros = List.copyOf(healthyPublishLatencyMicros);
        }

        private boolean measured() {
            return recoveredConnection != null;
        }

        private long durableMessages() {
            return messagesBeforeClosure + 1L;
        }
    }

    private record SaturationAttempt(
            int position, String clientMessageId, long sequence, long latencyMicros,
            boolean accepted) {}

    private record PostgresSaturationResult(
            List<Long> latencyMicros,
            int unavailableReadinessStatus,
            int recoveredReadinessStatus,
            long peerPublications,
            int retryableFailures,
            int convergedRetries,
            int errors) {
        private static final PostgresSaturationResult NONE =
                new PostgresSaturationResult(List.of(), 0, 0, 0, 0, 0, 0);

        private PostgresSaturationResult {
            latencyMicros = List.copyOf(latencyMicros);
        }

        private boolean measured() {
            return !latencyMicros.isEmpty();
        }
    }

    private record PostgresOutageResult(
            long failureLatencyMicros,
            long recoveryLatencyMicros,
            int unavailableReadinessStatus,
            int availableLivenessStatus,
            int recoveredReadinessStatus,
            long peerPublications,
            int retryableFailures,
            int convergedRetries,
            int errors) {
        private static final PostgresOutageResult NONE =
                new PostgresOutageResult(0, 0, 0, 0, 0, 0, 0, 0, 0);

        private boolean measured() {
            return failureLatencyMicros > 0;
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
        private boolean demandEnabled = true;

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
            if (demandEnabled) webSocket.request(1);
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

        private synchronized void pauseDemand() {
            demandEnabled = false;
        }
    }

    private record Configuration(
            String jdbcUrl, String username, String password,
            Path certificate, Path privateKey, int gatewayPort, int adminPort,
            Path output, int warmupOperations, int messageOperations,
            int payloadBytes, int receivers, int activeConversations, int reconnectRounds,
            int reconnectBatchSize, int reconnectBatchIntervalMillis,
            int slowConsumerMaxMessages, int postgresSaturationSenders,
            boolean postgresOutage, Path postgresOutageControlDir) {
        private Configuration {
            Objects.requireNonNull(jdbcUrl, "jdbcUrl");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(certificate, "certificate");
            Objects.requireNonNull(privateKey, "privateKey");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(postgresOutageControlDir, "postgresOutageControlDir");
            if (!Files.isRegularFile(certificate) || !Files.isRegularFile(privateKey)) {
                throw new IllegalArgumentException("gateway TLS files must exist");
            }
            if (!Files.isDirectory(postgresOutageControlDir)) {
                throw new IllegalArgumentException("PostgreSQL outage control directory must exist");
            }
            bounded("gateway port", gatewayPort, 1, 65_535);
            bounded("admin port", adminPort, 1, 65_535);
            if (gatewayPort == adminPort) throw new IllegalArgumentException("ports must differ");
            bounded("warmup", warmupOperations, 0, 10_000);
            bounded("messages", messageOperations, 1, 100_000);
            bounded("payload bytes", payloadBytes, 1, 65_536);
            // The default gateway allows 60 authentication attempts per direct peer;
            // the sender consumes one and the benchmark must not weaken that policy.
            bounded("receivers", receivers, 1, 59);
            bounded("active conversations", activeConversations, 1, 100);
            bounded("reconnect rounds", reconnectRounds, 0, 20);
            bounded("reconnect batch size", reconnectBatchSize, 0, 59);
            bounded("reconnect batch interval millis", reconnectBatchIntervalMillis, 0, 5_000);
            bounded("slow consumer max messages", slowConsumerMaxMessages, 0, 100);
            bounded("PostgreSQL saturation senders", postgresSaturationSenders, 0, 16);
            if (postgresSaturationSenders == 1) {
                throw new IllegalArgumentException(
                        "PostgreSQL saturation requires zero or at least two senders");
            }
            if (slowConsumerMaxMessages > 0 && receivers < 2) {
                throw new IllegalArgumentException(
                        "slow consumer scenario requires one slow and one healthy receiver");
            }
            if (slowConsumerMaxMessages > 0 && reconnectRounds > 0) {
                throw new IllegalArgumentException(
                        "slow consumer and reconnect scenarios must be measured separately");
            }
            if ((reconnectBatchSize == 0) != (reconnectBatchIntervalMillis == 0)) {
                throw new IllegalArgumentException(
                        "reconnect batch size and interval must both be zero or positive");
            }
            if (reconnectBatchSize > 0 && (reconnectRounds == 0
                    || reconnectBatchSize >= receivers + 1)) {
                throw new IllegalArgumentException(
                        "paced reconnect requires rounds and at least two batches");
            }
            if (postgresSaturationSenders > 0
                    && (slowConsumerMaxMessages > 0 || reconnectRounds > 0)) {
                throw new IllegalArgumentException(
                        "PostgreSQL saturation must be measured separately");
            }
            if (postgresOutage && (postgresSaturationSenders > 0
                    || slowConsumerMaxMessages > 0 || reconnectRounds > 0)) {
                throw new IllegalArgumentException(
                        "PostgreSQL outage must be measured separately");
            }
            if (activeConversations > 1 && (postgresOutage
                    || postgresSaturationSenders > 0 || slowConsumerMaxMessages > 0
                    || reconnectRounds > 0)) {
                throw new IllegalArgumentException(
                        "active-conversation curves must be measured separately");
            }
            if (warmupOperations % activeConversations != 0
                    || messageOperations % activeConversations != 0) {
                throw new IllegalArgumentException(
                        "warmup and messages must divide evenly across active conversations");
            }
            long authenticationAttempts = (long) (receivers + 1) * (reconnectRounds + 1)
                    + (slowConsumerMaxMessages > 0 ? 1L : 0L)
                    + postgresSaturationSenders;
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
                    "--active-conversations",
                    "--reconnect-rounds", "--reconnect-batch-size",
                    "--reconnect-batch-interval-millis", "--slow-consumer-max-messages",
                    "--postgres-saturation-senders", "--postgres-outage",
                    "--postgres-outage-control-dir");
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
                        Integer.parseInt(values.get("--active-conversations")),
                        Integer.parseInt(values.get("--reconnect-rounds")),
                        Integer.parseInt(values.get("--reconnect-batch-size")),
                        Integer.parseInt(values.get("--reconnect-batch-interval-millis")),
                        Integer.parseInt(values.get("--slow-consumer-max-messages")),
                        Integer.parseInt(values.get("--postgres-saturation-senders")),
                        parseBoolean(values.get("--postgres-outage")),
                        Path.of(values.get("--postgres-outage-control-dir")));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("gateway counts must be integers", exception);
            }
        }

        private static boolean parseBoolean(String value) {
            if (value.equals("1")) return true;
            if (value.equals("0")) return false;
            throw new IllegalArgumentException("PostgreSQL outage must be zero or one");
        }

        private static void bounded(String name, int value, int minimum, int maximum) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "%s must be in %d..%d", name, minimum, maximum));
            }
        }

        private boolean group() {
            return receivers > 1 || postgresSaturationSenders > 0
                    || activeConversations > 1;
        }
    }
}

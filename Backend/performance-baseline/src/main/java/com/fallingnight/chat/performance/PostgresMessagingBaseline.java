package com.fallingnight.chat.performance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessageSubmission;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.persistence.postgres.PostgresMessageAdapter;
import com.fallingnight.chat.persistence.postgres.PostgresMigrator;
import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.ds.PGSimpleDataSource;

/** Reproducible disposable-PostgreSQL baseline for the canonical messaging adapter. */
public final class PostgresMessagingBaseline {
    private static final String CONFIRMATION = "DISPOSABLE_POSTGRES_ONLY";

    private PostgresMessagingBaseline() {}

    public static void main(String[] arguments) throws Exception {
        Configuration configuration = Configuration.parse(arguments);
        if (!CONFIRMATION.equals(System.getenv("CHATROOM_PERFORMANCE_CONFIRM"))) {
            throw new IllegalArgumentException(
                    "CHATROOM_PERFORMANCE_CONFIRM must be DISPOSABLE_POSTGRES_ONLY");
        }
        requireLoopback(configuration.jdbcUrl());
        new PostgresMigrator(
                configuration.jdbcUrl(), configuration.username(), configuration.password())
                .migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(configuration.jdbcUrl());
        dataSource.setUser(configuration.username());
        dataSource.setPassword(configuration.password());
        UUID accountId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        seed(dataSource, accountId, deviceId, conversationId);
        PostgresMessageAdapter adapter = new PostgresMessageAdapter(dataSource);
        byte[] payload = new byte[configuration.payloadBytes()];
        Arrays.fill(payload, (byte) 'm');

        OperatingSystemMXBean operatingSystem = (OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = operatingSystem.getProcessCpuTime();
        Instant startedAt = Instant.now();
        long peakHeap = usedHeap();

        for (int index = 0; index < configuration.warmupOperations(); ++index) {
            requireNewAcceptance(adapter.submit(submission(
                    conversationId, accountId, deviceId, "warmup-" + index, payload)));
        }
        long measuredStart = System.nanoTime();
        List<Long> appendMicros = new ArrayList<>(configuration.appendOperations());
        for (int index = 0; index < configuration.appendOperations(); ++index) {
            long start = System.nanoTime();
            requireNewAcceptance(adapter.submit(submission(
                    conversationId, accountId, deviceId, "append-" + index, payload)));
            appendMicros.add(elapsedMicros(start));
            peakHeap = Math.max(peakHeap, usedHeap());
        }
        long appendElapsed = System.nanoTime() - measuredStart;

        MessageSubmission retrySubmission = submission(
                conversationId, accountId, deviceId, "retry-stable", payload);
        requireNewAcceptance(adapter.submit(retrySubmission));
        List<Long> retryMicros = new ArrayList<>(configuration.retryOperations());
        for (int index = 0; index < configuration.retryOperations(); ++index) {
            long start = System.nanoTime();
            MessageSubmissionResult result = adapter.submit(retrySubmission);
            if (!(result instanceof MessageSubmissionResult.Accepted accepted)
                    || !accepted.duplicate()) {
                throw new IllegalStateException("idempotent retry did not converge");
            }
            retryMicros.add(elapsedMicros(start));
        }

        ConcurrentResult concurrent = concurrentAppend(
                adapter, configuration, conversationId, accountId, deviceId, payload);
        peakHeap = Math.max(peakHeap, usedHeap());
        long expectedMessages = (long) configuration.warmupOperations()
                + configuration.appendOperations() + 1L + configuration.concurrentOperations();
        requireMessageState(dataSource, conversationId, expectedMessages);

        long afterSequence = Math.max(0L, expectedMessages - 100L);
        List<Long> historyMicros = new ArrayList<>(configuration.historyReads());
        for (int index = 0; index < configuration.historyReads(); ++index) {
            long start = System.nanoTime();
            MessageHistoryResult result = adapter.readAfter(
                    new MessageHistoryQuery(conversationId, accountId, afterSequence, 100));
            if (!(result instanceof MessageHistoryResult.Page page)
                    || page.latestSequence() != expectedMessages || page.messages().isEmpty()) {
                throw new IllegalStateException("history baseline returned incomplete state");
            }
            historyMicros.add(elapsedMicros(start));
        }
        long cpuNanos = operatingSystem.getProcessCpuTime() - cpuStart;
        Duration wall = Duration.between(startedAt, Instant.now());
        peakHeap = Math.max(peakHeap, usedHeap());

        write(configuration.output(), configuration, startedAt, wall, cpuNanos, peakHeap,
                expectedMessages, appendElapsed, appendMicros, retryMicros,
                concurrent, historyMicros);
    }

    private static ConcurrentResult concurrentAppend(
            PostgresMessageAdapter adapter,
            Configuration configuration,
            UUID conversationId,
            UUID accountId,
            UUID deviceId,
            byte[] payload) throws InterruptedException {
        List<Long> latencyMicros = Collections.synchronizedList(
                new ArrayList<>(configuration.concurrentOperations()));
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(configuration.concurrency());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(configuration.concurrency())) {
            AtomicInteger next = new AtomicInteger();
            for (int worker = 0; worker < configuration.concurrency(); ++worker) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        for (;;) {
                            int index = next.getAndIncrement();
                            if (index >= configuration.concurrentOperations()) return;
                            long operationStart = System.nanoTime();
                            try {
                                requireNewAcceptance(adapter.submit(submission(
                                        conversationId, accountId, deviceId,
                                        "concurrent-" + index, payload)));
                                latencyMicros.add(elapsedMicros(operationStart));
                            } catch (RuntimeException exception) {
                                failures.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        failures.incrementAndGet();
                    }
                });
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent workers did not become ready");
            }
            long started = System.nanoTime();
            start.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                executor.shutdownNow();
                throw new IllegalStateException("concurrent workload timed out");
            }
            if (failures.get() != 0 || latencyMicros.size() != configuration.concurrentOperations()) {
                throw new IllegalStateException("concurrent workload had failures");
            }
            return new ConcurrentResult(List.copyOf(latencyMicros),
                    System.nanoTime() - started, failures.get());
        }
    }

    private static void write(
            Path output,
            Configuration configuration,
            Instant startedAt,
            Duration wall,
            long cpuNanos,
            long peakHeap,
            long expectedMessages,
            long appendElapsed,
            List<Long> appendMicros,
            List<Long> retryMicros,
            ConcurrentResult concurrent,
            List<Long> historyMicros) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (JsonGenerator json = new JsonFactory().createGenerator(
                Files.newOutputStream(output))) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();
            json.writeNumberField("schemaVersion", 1);
            json.writeStringField("benchmark", "java-v2-postgres-messaging");
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
            json.writeNumberField("warmupOperations", configuration.warmupOperations());
            json.writeNumberField("appendOperations", configuration.appendOperations());
            json.writeNumberField("retryOperations", configuration.retryOperations());
            json.writeNumberField("concurrentOperations", configuration.concurrentOperations());
            json.writeNumberField("concurrency", configuration.concurrency());
            json.writeNumberField("historyReads", configuration.historyReads());
            json.writeNumberField("historyPageSize", 100);
            json.writeNumberField("payloadBytes", configuration.payloadBytes());
            json.writeNumberField("durableMessages", expectedMessages);
            json.writeEndObject();
            json.writeObjectFieldStart("results");
            distribution(json, "sequentialAppendLatencyMicros", appendMicros);
            json.writeNumberField("sequentialAppendThroughputPerSecond",
                    throughput(appendMicros.size(), appendElapsed));
            distribution(json, "idempotentRetryLatencyMicros", retryMicros);
            distribution(json, "concurrentAppendLatencyMicros", concurrent.latencyMicros());
            json.writeNumberField("concurrentAppendThroughputPerSecond",
                    throughput(concurrent.latencyMicros().size(), concurrent.elapsedNanos()));
            json.writeNumberField("concurrentErrors", concurrent.failures());
            distribution(json, "historyReadLatencyMicros", historyMicros);
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

    private static MessageSubmission submission(
            UUID conversationId, UUID accountId, UUID deviceId,
            String clientMessageId, byte[] payload) {
        return new MessageSubmission(
                conversationId, accountId, deviceId, clientMessageId, 100, payload);
    }

    private static void requireNewAcceptance(MessageSubmissionResult result) {
        if (!(result instanceof MessageSubmissionResult.Accepted accepted)
                || accepted.duplicate()) {
            throw new IllegalStateException("message was not newly accepted");
        }
    }

    private static void seed(
            PGSimpleDataSource dataSource, UUID account, UUID device, UUID conversation)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'performance-owner', 'Performance Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", account);
            execute(connection, "INSERT INTO chat.device(id, account_id, client_device_id, "
                    + "platform) VALUES (?, ?, 'performance-device', 'WEB')", device, account);
            execute(connection, "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')",
                    conversation);
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id) VALUES (?, ?)", conversation, account);
            connection.commit();
        }
    }

    private static void requireMessageState(
            PGSimpleDataSource dataSource, UUID conversation, long expected) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*), max(conversation_sequence), "
                                + "(SELECT next_sequence FROM chat.conversation WHERE id = ?) "
                                + "FROM chat.message WHERE conversation_id = ?")) {
            statement.setObject(1, conversation);
            statement.setObject(2, conversation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) != expected
                        || result.getLong(2) != expected || result.getLong(3) != expected + 1L) {
                    throw new IllegalStateException("durable message state did not reconcile");
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
                throw new SQLException("seed statement did not affect one row");
            }
        }
    }

    private static void requireLoopback(String jdbcUrl) {
        if (!(jdbcUrl.startsWith("jdbc:postgresql://127.0.0.1:")
                || jdbcUrl.startsWith("jdbc:postgresql://[::1]:"))) {
            throw new IllegalArgumentException("performance baseline requires loopback PostgreSQL");
        }
    }

    private record ConcurrentResult(List<Long> latencyMicros, long elapsedNanos, int failures) {
        private ConcurrentResult {
            latencyMicros = List.copyOf(latencyMicros);
        }
    }

    private record Configuration(
            String jdbcUrl,
            String username,
            String password,
            Path output,
            int warmupOperations,
            int appendOperations,
            int retryOperations,
            int concurrentOperations,
            int concurrency,
            int historyReads,
            int payloadBytes) {
        private Configuration {
            Objects.requireNonNull(jdbcUrl, "jdbcUrl");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(output, "output");
            bounded("warmup", warmupOperations, 0, 100_000);
            bounded("append", appendOperations, 1, 100_000);
            bounded("retry", retryOperations, 1, 100_000);
            bounded("concurrent", concurrentOperations, 1, 100_000);
            bounded("concurrency", concurrency, 1, 64);
            bounded("history", historyReads, 1, 100_000);
            bounded("payload bytes", payloadBytes, 1, 1_048_576);
            if (concurrency > concurrentOperations) {
                throw new IllegalArgumentException("concurrency cannot exceed operations");
            }
        }

        private static Configuration parse(String[] arguments) {
            if (arguments.length % 2 != 0) {
                throw new IllegalArgumentException("arguments must be --name value pairs");
            }
            java.util.Map<String, String> values = new java.util.HashMap<>();
            for (int index = 0; index < arguments.length; index += 2) {
                if (!arguments[index].startsWith("--")
                        || values.put(arguments[index], arguments[index + 1]) != null) {
                    throw new IllegalArgumentException("invalid or duplicate argument");
                }
            }
            java.util.Set<String> expected = java.util.Set.of(
                    "--jdbc-url", "--username", "--password", "--output", "--warmup",
                    "--append", "--retry", "--concurrent", "--concurrency", "--history",
                    "--payload-bytes");
            if (!values.keySet().equals(expected)) {
                throw new IllegalArgumentException("missing or unknown performance argument");
            }
            try {
                return new Configuration(
                        values.get("--jdbc-url"), values.get("--username"),
                        values.get("--password"), Path.of(values.get("--output")),
                        Integer.parseInt(values.get("--warmup")),
                        Integer.parseInt(values.get("--append")),
                        Integer.parseInt(values.get("--retry")),
                        Integer.parseInt(values.get("--concurrent")),
                        Integer.parseInt(values.get("--concurrency")),
                        Integer.parseInt(values.get("--history")),
                        Integer.parseInt(values.get("--payload-bytes")));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("performance counts must be integers", exception);
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

package com.fallingnight.chat.gateway.transport;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Thread-safe, label-bounded authentication metrics and sampled safe logs. */
public final class AuthenticationTelemetry implements AuthenticationEventSink {
    private static final System.Logger LOGGER =
            System.getLogger(AuthenticationTelemetry.class.getName());
    private static final long[] BUCKET_NANOS = {
        1_000_000L,
        5_000_000L,
        10_000_000L,
        25_000_000L,
        50_000_000L,
        100_000_000L,
        250_000_000L,
        500_000_000L,
        1_000_000_000L,
        2_500_000_000L,
        5_000_000_000L
    };

    private final LongAdder accepted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder saturated = new LongAdder();
    private final LongAdder upgradePending = new LongAdder();
    private final EnumMap<AuthenticationLimitDimension, LongAdder> admissionDenials =
            new EnumMap<>(AuthenticationLimitDimension.class);
    private final LongAdder[] durationBuckets = new LongAdder[BUCKET_NANOS.length + 1];
    private final LongAdder durationCount = new LongAdder();
    private final LongAdder durationTotalNanos = new LongAdder();
    private final AtomicLong durationMaxNanos = new AtomicLong();
    private final Consumer<String> warningLog;

    public AuthenticationTelemetry() {
        this(message -> LOGGER.log(System.Logger.Level.WARNING, message));
    }

    AuthenticationTelemetry(Consumer<String> warningLog) {
        this.warningLog = Objects.requireNonNull(warningLog, "warningLog");
        for (AuthenticationLimitDimension dimension : AuthenticationLimitDimension.values()) {
            admissionDenials.put(dimension, new LongAdder());
        }
        for (int index = 0; index < durationBuckets.length; index++) {
            durationBuckets[index] = new LongAdder();
        }
    }

    @Override
    public void accepted(boolean credentialUpgradePending) {
        accepted.increment();
        if (credentialUpgradePending) {
            upgradePending.increment();
        }
    }

    @Override
    public void rejected() {
        rejected.increment();
    }

    @Override
    public void failed() {
        failed.increment();
    }

    @Override
    public void saturated() {
        saturated.increment();
        long count = saturated.sum();
        logAtPowerOfTwo("event=authentication_saturated count=" + count, count);
    }

    @Override
    public void admissionDenied(AuthenticationLimitDimension dimension) {
        Objects.requireNonNull(dimension, "dimension");
        LongAdder counter = admissionDenials.get(dimension);
        counter.increment();
        long count = counter.sum();
        logAtPowerOfTwo(
                "event=authentication_admission_denied dimension="
                        + dimension.name().toLowerCase(java.util.Locale.ROOT)
                        + " count=" + count,
                count);
    }

    @Override
    public void completed(
            AuthenticationOutcome outcome,
            boolean credentialUpgradePending,
            long executionNanos) {
        Objects.requireNonNull(outcome, "outcome");
        long bounded = Math.max(0, executionNanos);
        durationCount.increment();
        durationTotalNanos.add(bounded);
        durationMaxNanos.accumulateAndGet(bounded, Math::max);
        int index = 0;
        while (index < BUCKET_NANOS.length && bounded > BUCKET_NANOS[index]) {
            index++;
        }
        durationBuckets[index].increment();
    }

    public AuthenticationTelemetrySnapshot snapshot() {
        EnumMap<AuthenticationLimitDimension, Long> denials =
                new EnumMap<>(AuthenticationLimitDimension.class);
        admissionDenials.forEach((dimension, counter) -> denials.put(dimension, counter.sum()));
        Map<String, Long> buckets = new LinkedHashMap<>();
        for (int index = 0; index < BUCKET_NANOS.length; index++) {
            buckets.put(Long.toString(BUCKET_NANOS[index]), durationBuckets[index].sum());
        }
        buckets.put("+Inf", durationBuckets[BUCKET_NANOS.length].sum());
        return new AuthenticationTelemetrySnapshot(
                accepted.sum(),
                rejected.sum(),
                failed.sum(),
                saturated.sum(),
                upgradePending.sum(),
                denials,
                buckets,
                durationCount.sum(),
                durationTotalNanos.sum(),
                durationMaxNanos.get());
    }

    private void logAtPowerOfTwo(String message, long count) {
        if (count > 0 && (count & (count - 1)) == 0) {
            warningLog.accept(message);
        }
    }
}

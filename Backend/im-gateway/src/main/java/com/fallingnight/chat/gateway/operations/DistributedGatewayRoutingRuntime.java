package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.routing.GatewayRouteRegistrationService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Default-off owner for the distributed routing loops and their shared resources. */
public final class DistributedGatewayRoutingRuntime implements AutoCloseable {
    private final Lifecycle relay;
    private final Lifecycle lease;
    private final Lifecycle consumer;
    private final BooleanSupplier leaseValid;
    private final BooleanSupplier releaseGateway;
    private final SchedulerOwner scheduler;
    private final AutoCloseable routingAdapter;
    private final Duration shutdownTimeout;
    private boolean started;
    private boolean closed;
    private boolean resourcesClosed;

    public DistributedGatewayRoutingRuntime(ConversationEventRelayLoop relay,
            GatewayRouteLeaseLoop lease, GatewayLiveEventConsumerLoop consumer,
            GatewayRouteRegistrationService registration,
            ScheduledExecutorService scheduler, AutoCloseable routingAdapter,
            Duration shutdownTimeout) {
        this(lifecycle(relay::start, relay), lifecycle(lease::start, lease),
                lifecycle(consumer::start, consumer),
                () -> lease.snapshot().leaseValid(), registration::releaseGateway,
                scheduler(scheduler), routingAdapter, shutdownTimeout);
        Objects.requireNonNull(relay, "relay");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(scheduler, "scheduler");
    }

    DistributedGatewayRoutingRuntime(Lifecycle relay, Lifecycle lease,
            Lifecycle consumer, BooleanSupplier leaseValid,
            BooleanSupplier releaseGateway, SchedulerOwner scheduler,
            AutoCloseable routingAdapter, Duration shutdownTimeout) {
        this.relay = Objects.requireNonNull(relay, "relay");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.leaseValid = Objects.requireNonNull(leaseValid, "leaseValid");
        this.releaseGateway = Objects.requireNonNull(releaseGateway, "releaseGateway");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.routingAdapter = Objects.requireNonNull(routingAdapter, "routingAdapter");
        this.shutdownTimeout = boundedShutdownTimeout(shutdownTimeout);
    }

    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException("distributed routing runtime cannot start");
        }
        started = true;
        try {
            lease.start();
            consumer.start();
            relay.start();
        } catch (RuntimeException exception) {
            closed = true;
            RuntimeException cleanup = closeResources();
            if (cleanup != null) exception.addSuppressed(cleanup);
            throw exception;
        }
    }

    public synchronized boolean readyForTraffic() {
        return started && !closed && leaseValid.getAsBoolean();
    }

    @Override
    public synchronized void close() {
        closed = true;
        RuntimeException failure = closeResources();
        if (failure != null) throw failure;
    }

    private RuntimeException closeResources() {
        if (resourcesClosed) return null;
        resourcesClosed = true;
        RuntimeException failure = null;
        failure = close(relay, failure);
        failure = close(consumer, failure);
        failure = close(lease, failure);
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(shutdownTimeout)) {
                failure = append(failure,
                        new IllegalStateException("distributed routing scheduler did not stop"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure = append(failure,
                    new IllegalStateException("distributed routing shutdown interrupted", exception));
        }
        try {
            releaseGateway.getAsBoolean();
        } catch (RuntimeException exception) {
            failure = append(failure, exception);
        }
        failure = close(routingAdapter, failure);
        return failure;
    }

    private static RuntimeException close(AutoCloseable resource, RuntimeException failure) {
        try {
            resource.close();
            return failure;
        } catch (Exception exception) {
            RuntimeException current = exception instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("distributed routing cleanup failed", exception);
            return append(failure, current);
        }
    }

    private static RuntimeException append(RuntimeException failure, RuntimeException current) {
        if (failure == null) return current;
        failure.addSuppressed(current);
        return failure;
    }

    private static Lifecycle lifecycle(Runnable start, AutoCloseable close) {
        return new Lifecycle() {
            @Override public void start() { start.run(); }
            @Override public void close() {
                try {
                    close.close();
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new IllegalStateException("distributed routing cleanup failed", exception);
                }
            }
        };
    }

    private static SchedulerOwner scheduler(ScheduledExecutorService scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        return new SchedulerOwner() {
            @Override public void shutdownNow() { scheduler.shutdownNow(); }
            @Override public boolean awaitTermination(Duration timeout)
                    throws InterruptedException {
                return scheduler.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        };
    }

    private static Duration boundedShutdownTimeout(Duration value) {
        Objects.requireNonNull(value, "shutdownTimeout");
        if (value.compareTo(Duration.ofMillis(100)) < 0
                || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("shutdownTimeout outside reviewed range");
        }
        return value;
    }

    interface Lifecycle extends AutoCloseable {
        void start();
        @Override void close();
    }

    interface SchedulerOwner {
        void shutdownNow();
        boolean awaitTermination(Duration timeout) throws InterruptedException;
    }
}

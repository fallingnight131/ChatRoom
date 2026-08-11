package com.fallingnight.chat.gateway.operations;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Process-local readiness gate; startup is unready until all dependencies are wired. */
public final class GatewayReadiness implements BooleanSupplier {
    private final AtomicBoolean ready = new AtomicBoolean();

    @Override
    public boolean getAsBoolean() {
        return ready.get();
    }

    public void markReady() {
        ready.set(true);
    }

    public void markUnready() {
        ready.set(false);
    }
}

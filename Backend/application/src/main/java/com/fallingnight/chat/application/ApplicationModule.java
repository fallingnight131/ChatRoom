package com.fallingnight.chat.application;

/**
 * Stable in-process boundary for the modular application core.
 *
 * <p>Transport adapters depend inward on this module. Domain behavior must not
 * depend on Netty, Spring, generated wire messages, or database row types.</p>
 */
public final class ApplicationModule {
    public static final String NAME = "application";

    private ApplicationModule() {
    }
}

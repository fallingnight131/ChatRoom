package com.fallingnight.chat.gateway.transport;

/** Non-secret authentication outcomes for metrics and operational diagnostics. */
public interface AuthenticationEventSink {
    void accepted(boolean credentialUpgradePending);

    void rejected();

    void failed();

    void saturated();

    void admissionDenied(AuthenticationLimitDimension dimension);

    static AuthenticationEventSink noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final AuthenticationEventSink INSTANCE = new AuthenticationEventSink() {
            @Override
            public void accepted(boolean credentialUpgradePending) {
            }

            @Override
            public void rejected() {
            }

            @Override
            public void failed() {
            }

            @Override
            public void saturated() {
            }

            @Override
            public void admissionDenied(AuthenticationLimitDimension dimension) {
            }
        };

        private NoopHolder() {
        }
    }
}

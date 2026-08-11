package com.fallingnight.chat.gateway.transport;

/** Ephemeral pre-hash authentication admission boundary. */
public interface AuthenticationAdmissionControl {
    AuthenticationAdmissionDecision acquire(String directPeer, String presentedUsername);

    AuthenticationAdmissionDecision acquireResume(String directPeer);

    void recordSuccess(String presentedUsername);

    static AuthenticationAdmissionControl allowAll() {
        return AllowAllHolder.INSTANCE;
    }

    final class AllowAllHolder {
        private static final AuthenticationAdmissionControl INSTANCE =
                new AuthenticationAdmissionControl() {
                    @Override
                    public AuthenticationAdmissionDecision acquire(
                            String directPeer, String presentedUsername) {
                        return AuthenticationAdmissionDecision.allow();
                    }

                    @Override
                    public AuthenticationAdmissionDecision acquireResume(String directPeer) {
                        return AuthenticationAdmissionDecision.allow();
                    }

                    @Override
                    public void recordSuccess(String presentedUsername) {
                    }
                };

        private AllowAllHolder() {
        }
    }
}

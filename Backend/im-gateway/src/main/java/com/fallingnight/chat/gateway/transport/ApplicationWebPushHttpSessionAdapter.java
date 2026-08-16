package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.notification.WebPushHttpCredentialAuthenticationPort;
import com.fallingnight.chat.application.notification.WebPushHttpCredentialAuthenticationResult;
import java.time.Instant;
import java.util.Objects;

/** Maps the application credential authority to the fixed HTTP transport contract. */
public final class ApplicationWebPushHttpSessionAdapter implements WebPushHttpSessionPort {
    private final WebPushHttpCredentialAuthenticationPort credentials;

    public ApplicationWebPushHttpSessionAdapter(
            WebPushHttpCredentialAuthenticationPort credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public WebPushHttpAuthenticationResult authenticate(
            byte[] bearerToken, byte[] csrfToken, Instant observedAt) {
        WebPushHttpCredentialAuthenticationResult result = Objects.requireNonNull(
                credentials.authenticate(bearerToken, csrfToken, observedAt), "result");
        if (result instanceof WebPushHttpCredentialAuthenticationResult.Authenticated value) {
            return new WebPushHttpAuthenticationResult.Authenticated(new WebPushHttpActor(
                    value.actor().accountId(), value.actor().sessionId()));
        }
        return result == WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_CSRF
                ? WebPushHttpAuthenticationResult.Rejected.INVALID_CSRF
                : WebPushHttpAuthenticationResult.Rejected.INVALID_SESSION;
    }
}

package com.fallingnight.chat.application.notification;

import com.fallingnight.chat.application.identity.AuthenticatedDeviceActor;
import java.time.Clock;
import java.util.Objects;

/** Exact-default-off issuance bound only to a server-authenticated device session. */
public final class WebPushHttpCredentialIssueService {
    private final WebPushDeliveryPolicy policy;
    private final WebPushHttpCredentialIssuePort credentials;
    private final Clock clock;

    public WebPushHttpCredentialIssueService(WebPushDeliveryPolicy policy,
            WebPushHttpCredentialIssuePort credentials, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WebPushHttpCredentialIssueResult issue(AuthenticatedDeviceActor actor) {
        Objects.requireNonNull(actor, "actor");
        if (!policy.enabled()) return WebPushHttpCredentialIssueResult.Rejected.DISABLED;
        return credentials.issue(actor, clock.instant())
                .<WebPushHttpCredentialIssueResult>map(WebPushHttpCredentialIssueResult.Issued::new)
                .orElse(WebPushHttpCredentialIssueResult.Rejected.SESSION_UNAVAILABLE);
    }
}

package com.fallingnight.chat.application.notification;

/** Fixed issuance outcome; issued secrets remain closeable by the caller. */
public sealed interface WebPushHttpCredentialIssueResult {
    record Issued(IssuedWebPushHttpCredential credential)
            implements WebPushHttpCredentialIssueResult {
        public Issued {
            if (credential == null) throw new NullPointerException("credential");
        }
    }
    enum Rejected implements WebPushHttpCredentialIssueResult { DISABLED, SESSION_UNAVAILABLE }
}

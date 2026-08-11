package com.fallingnight.chat.application.identity;

import com.fallingnight.chat.application.security.SecretBytes;
import java.util.Objects;

/** One fresh-login attempt; closing it destroys its owned password bytes. */
public final class AuthenticateCommand implements AutoCloseable {
    private final String username;
    private final ClientDescriptor client;
    private final SecretBytes passwordUtf8;

    public AuthenticateCommand(String username, byte[] passwordUtf8, ClientDescriptor client) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        this.username = username;
        this.client = Objects.requireNonNull(client, "client");
        this.passwordUtf8 = SecretBytes.copyOf(passwordUtf8);
    }

    public String username() {
        return username;
    }

    public ClientDescriptor client() {
        return client;
    }

    SecretBytes passwordUtf8() {
        return passwordUtf8;
    }

    public boolean isClosed() {
        return passwordUtf8.isClosed();
    }

    @Override
    public void close() {
        passwordUtf8.close();
    }
}

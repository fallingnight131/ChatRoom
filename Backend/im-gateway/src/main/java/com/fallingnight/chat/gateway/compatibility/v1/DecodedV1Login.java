package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.identity.AuthenticateCommand;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.security.SecretBytes;
import java.util.Objects;

/** Validated V1 login payload that owns its temporary password bytes. */
public final class DecodedV1Login implements AutoCloseable {
    private final String username;
    private final SecretBytes passwordUtf8;

    DecodedV1Login(String username, byte[] passwordUtf8) {
        this.username = Objects.requireNonNull(username, "username");
        this.passwordUtf8 = SecretBytes.copyOf(passwordUtf8);
    }

    public String username() {
        return username;
    }

    public AuthenticateCommand toCommand(ClientDescriptor client) {
        return passwordUtf8.withCopy(password -> new AuthenticateCommand(
                username, password, client));
    }

    public boolean isClosed() {
        return passwordUtf8.isClosed();
    }

    @Override
    public void close() {
        passwordUtf8.close();
    }
}

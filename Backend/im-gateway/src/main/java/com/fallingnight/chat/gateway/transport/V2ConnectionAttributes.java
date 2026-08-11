package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.ClientDescriptor;
import io.netty.util.AttributeKey;

/** Typed server-side connection state shared by ordered gateway handlers. */
public final class V2ConnectionAttributes {
    public static final AttributeKey<ClientDescriptor> NEGOTIATED_CLIENT =
            AttributeKey.valueOf("v2.negotiatedClient");
    public static final AttributeKey<AuthenticatedConnection> AUTHENTICATED =
            AttributeKey.valueOf("v2.authenticated");
    public static final AttributeKey<String> CLIENT_PEER_ADDRESS =
            AttributeKey.valueOf("v2.clientPeerAddress");

    private V2ConnectionAttributes() {
    }
}

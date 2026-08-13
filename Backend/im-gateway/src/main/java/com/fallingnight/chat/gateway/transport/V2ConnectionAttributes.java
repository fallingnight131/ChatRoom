package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.protocol.v2.ClientCapability;
import io.netty.util.AttributeKey;
import java.util.Set;

/** Typed server-side connection state shared by ordered gateway handlers. */
public final class V2ConnectionAttributes {
    public static final AttributeKey<ClientDescriptor> NEGOTIATED_CLIENT =
            AttributeKey.valueOf("v2.negotiatedClient");
    public static final AttributeKey<AuthenticatedConnection> AUTHENTICATED =
            AttributeKey.valueOf("v2.authenticated");
    public static final AttributeKey<Set<ClientCapability>> ENABLED_CAPABILITIES =
            AttributeKey.valueOf("v2.enabledCapabilities");
    public static final AttributeKey<String> CLIENT_PEER_ADDRESS =
            AttributeKey.valueOf("v2.clientPeerAddress");
    public static final AttributeKey<com.fallingnight.chat.application.identity.ClientPlatform>
            EXPECTED_CLIENT_PLATFORM = AttributeKey.valueOf("v2.expectedClientPlatform");

    private V2ConnectionAttributes() {
    }
}

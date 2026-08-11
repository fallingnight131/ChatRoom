package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AuthenticatedIdentity;
import io.netty.util.AttributeKey;

/** Server-owned state for the inactive V1 Web compatibility pipeline. */
public final class V1ConnectionAttributes {
    public static final AttributeKey<Boolean> WEB_UPGRADE_ACCEPTED =
            AttributeKey.valueOf("v1.web-upgrade-accepted");
    public static final AttributeKey<LegacyV1AuthenticatedIdentity> AUTHENTICATED =
            AttributeKey.valueOf("v1.authenticated");

    private V1ConnectionAttributes() {
    }
}

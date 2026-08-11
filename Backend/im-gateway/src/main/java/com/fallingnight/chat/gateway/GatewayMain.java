package com.fallingnight.chat.gateway;

import com.fallingnight.chat.application.ApplicationModule;
import com.fallingnight.chat.protocol.V2Protocol;

/** Bootstrap placeholder; the next slice adds the versioned Netty transport. */
public final class GatewayMain {
    private GatewayMain() {
    }

    public static String identity() {
        return "im-gateway->" + ApplicationModule.NAME + ":v" + V2Protocol.VERSION;
    }

    public static void main(String[] args) {
        System.out.println(identity());
    }
}

package com.fallingnight.chat.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GatewayMainTest {
    @Test
    void dependsInwardOnProtocolAndApplicationModules() {
        assertEquals("im-gateway->application:v2", GatewayMain.identity());
    }
}

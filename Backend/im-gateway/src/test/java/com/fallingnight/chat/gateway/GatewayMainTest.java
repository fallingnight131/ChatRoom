package com.fallingnight.chat.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GatewayMainTest {
    @Test
    void dependsInwardOnProtocolAndApplicationModules() {
        assertEquals("im-gateway->application:v2", GatewayMain.identity());
    }

    @Test
    void rejectsCommandLineConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> GatewayMain.main(new String[] {"secret"}));
    }
}

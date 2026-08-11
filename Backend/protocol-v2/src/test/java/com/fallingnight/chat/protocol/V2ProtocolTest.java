package com.fallingnight.chat.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class V2ProtocolTest {
    @Test
    void reservesAnExplicitProtocolGeneration() {
        assertEquals(2, V2Protocol.VERSION);
    }
}

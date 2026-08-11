package com.fallingnight.chat.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ApplicationModuleTest {
    @Test
    void exposesTransportNeutralModuleIdentity() {
        assertEquals("application", ApplicationModule.NAME);
    }
}

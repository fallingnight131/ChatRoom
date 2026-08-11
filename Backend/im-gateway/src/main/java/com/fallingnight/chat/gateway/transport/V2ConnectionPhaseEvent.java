package com.fallingnight.chat.gateway.transport;

/** Internal pipeline events used to advance and cancel connection deadlines. */
enum V2ConnectionPhaseEvent {
    NEGOTIATED,
    AUTHENTICATED
}

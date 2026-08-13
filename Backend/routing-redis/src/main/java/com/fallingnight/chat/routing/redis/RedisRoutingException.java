package com.fallingnight.chat.routing.redis;

public final class RedisRoutingException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public RedisRoutingException(String message) { super(message); }
    public RedisRoutingException(String message, Throwable cause) { super(message, cause); }
}

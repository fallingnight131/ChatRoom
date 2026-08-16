package com.fallingnight.chat.application.notification;

import java.util.function.BiFunction;
import java.util.function.Function;

/** Supplies short-lived 32-byte key copies; implementations own custody and zero callbacks. */
public interface WebPushKeyCustodyPort {
    <T> T withActiveEncryptionKey(BiFunction<String, byte[], T> action);

    <T> T withEncryptionKey(String keyId, Function<byte[], T> action);

    <T> T withEndpointLookupKey(Function<byte[], T> action);
}

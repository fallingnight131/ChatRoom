package com.fallingnight.chat.application.messaging;

@FunctionalInterface
public interface MessageSearchPort {
    MessageSearchResult search(MessageSearchQuery query);
}

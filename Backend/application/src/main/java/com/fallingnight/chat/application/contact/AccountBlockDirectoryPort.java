package com.fallingnight.chat.application.contact;

@FunctionalInterface
public interface AccountBlockDirectoryPort {
    AccountBlockDirectoryResult list(AccountBlockDirectoryQuery query);
}

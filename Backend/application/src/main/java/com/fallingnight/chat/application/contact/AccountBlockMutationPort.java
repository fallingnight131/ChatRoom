package com.fallingnight.chat.application.contact;

@FunctionalInterface
public interface AccountBlockMutationPort {
    AccountBlockResult apply(AccountBlockMutation mutation);
}

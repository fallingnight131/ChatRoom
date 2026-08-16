package com.fallingnight.chat.application.contact;

import java.util.Objects;

/** Authorized outgoing-block page or a privacy-safe actor denial. */
public sealed interface AccountBlockDirectoryResult {
    record Found(AccountBlockDirectoryPage page) implements AccountBlockDirectoryResult {
        public Found {
            Objects.requireNonNull(page, "page");
        }
    }

    enum Rejected implements AccountBlockDirectoryResult {
        NOT_AUTHORIZED
    }
}

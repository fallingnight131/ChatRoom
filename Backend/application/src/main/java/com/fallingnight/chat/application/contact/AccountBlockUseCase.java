package com.fallingnight.chat.application.contact;

import java.util.UUID;

/** Authenticated application boundary for one account-block desired-state mutation. */
public interface AccountBlockUseCase {
    AccountBlockResult apply(UUID authenticatedAccountId, AccountBlockIntent intent);
}

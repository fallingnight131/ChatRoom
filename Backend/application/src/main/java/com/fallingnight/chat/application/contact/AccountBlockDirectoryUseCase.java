package com.fallingnight.chat.application.contact;

import java.util.UUID;

public interface AccountBlockDirectoryUseCase {
    AccountBlockDirectoryResult list(
            UUID authenticatedAccountId, AccountBlockDirectoryRequest request);
}

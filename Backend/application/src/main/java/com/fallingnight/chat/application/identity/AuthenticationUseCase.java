package com.fallingnight.chat.application.identity;

/** Inward use-case boundary invoked by transport adapters. */
@FunctionalInterface
public interface AuthenticationUseCase {
    AuthenticationResult authenticate(AuthenticateCommand command);
}

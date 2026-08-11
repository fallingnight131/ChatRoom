package com.fallingnight.chat.application.identity;

/** Inward transport-independent session-resume boundary. */
@FunctionalInterface
public interface SessionResumeUseCase {
    AuthenticationResult resume(ResumeSessionCommand command);
}

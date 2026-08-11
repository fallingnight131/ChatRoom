package com.fallingnight.chat.application.identity;

import java.time.Clock;
import java.util.Objects;

/** Coordinates generic session resume while persistence owns atomic rotation. */
public final class SessionResumeService implements SessionResumeUseCase {
    private final SessionResumePort sessions;
    private final Clock clock;

    public SessionResumeService(SessionResumePort sessions, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AuthenticationResult resume(ResumeSessionCommand command) {
        Objects.requireNonNull(command, "command");
        try (command) {
            return sessions.resumeAndRotate(
                            command.sessionId(),
                            command.resumeToken(),
                            command.client(),
                            clock.instant())
                    .<AuthenticationResult>map(
                            session -> new AuthenticationResult.Established(session, false))
                    .orElse(AuthenticationResult.Rejected.INSTANCE);
        }
    }
}

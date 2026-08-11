package com.fallingnight.chat.application.messaging;

/** Persists one message and allocates its conversation sequence atomically. */
public interface MessageSubmissionPort {
    MessageSubmissionResult submit(MessageSubmission submission);
}

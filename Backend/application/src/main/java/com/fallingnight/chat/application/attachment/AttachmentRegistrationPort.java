package com.fallingnight.chat.application.attachment;

/** Reserves durable metadata only; upload authorization is a separate boundary. */
public interface AttachmentRegistrationPort {
    AttachmentRegistrationResult register(AttachmentRegistration registration);
}

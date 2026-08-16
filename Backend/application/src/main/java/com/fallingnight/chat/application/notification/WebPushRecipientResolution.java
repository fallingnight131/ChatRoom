package com.fallingnight.chat.application.notification;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete bounded policy projection or explicit saturation; never a truncated page. */
public sealed interface WebPushRecipientResolution {
    int MAX_RECIPIENTS = 1_000;

    record Complete(List<WebPushRecipient> recipients)
            implements WebPushRecipientResolution {
        public Complete {
            recipients = List.copyOf(Objects.requireNonNull(recipients, "recipients"));
            if (recipients.size() > MAX_RECIPIENTS) {
                throw new IllegalArgumentException("too many Web Push recipients");
            }
            Set<java.util.UUID> unique = new HashSet<>();
            WebPushRecipient previous = null;
            for (WebPushRecipient recipient : recipients) {
                Objects.requireNonNull(recipient, "recipient");
                if (!unique.add(recipient.accountId())
                        || (previous != null
                        && previous.accountId().toString().compareTo(
                                recipient.accountId().toString()) >= 0)) {
                    throw new IllegalArgumentException(
                            "Web Push recipients must be unique and ordered");
                }
                previous = recipient;
            }
        }
    }

    enum Saturated implements WebPushRecipientResolution { INSTANCE }
}

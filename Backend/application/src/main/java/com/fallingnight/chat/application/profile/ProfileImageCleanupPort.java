package com.fallingnight.chat.application.profile;

import java.time.Instant;
import java.util.List;

public interface ProfileImageCleanupPort {
    List<ProfileImageCleanupClaim> claim(Instant requestedBefore, Instant staleBefore,
            Instant claimedAt, int limit);
    boolean release(ProfileImageCleanupClaim claim);
    boolean confirmDeleted(ProfileImageCleanupClaim claim, Instant confirmedAt);
}

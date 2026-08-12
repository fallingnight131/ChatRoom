package com.fallingnight.chat.persistence.postgres.migration;

/** Raw accepted V1 friendship pair used to reject contradictory pending requests. */
public record V1ExistingFriendPair(long firstUserId, long secondUserId) {}

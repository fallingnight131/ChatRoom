package com.fallingnight.chat.persistence.postgres.migration;

/** Composite V1 room administrator identity. */
public record V1RoomAdministrator(long legacyRoomId, long legacyUserId) {}

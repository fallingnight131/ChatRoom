package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.Objects;

public sealed interface LegacyV1RoomSearchResult {
    record Found(List<LegacyV1RoomSearchRoom> rooms) implements LegacyV1RoomSearchResult {
        public Found { rooms = List.copyOf(Objects.requireNonNull(rooms, "rooms")); }
    }
    enum Rejected implements LegacyV1RoomSearchResult { INSTANCE }
}

package com.fallingnight.chat.gateway.operations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Selects only reviewed resident-memory providers for the current server host. */
public final class ResidentMemorySources {
    private ResidentMemorySources() {}

    public static Optional<ResidentMemorySource> forCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path status = Path.of("/proc/self/status");
        if (os.contains("linux") && Files.isRegularFile(status)
                && Files.isReadable(status)) {
            return Optional.of(new LinuxProcResidentMemorySource(status));
        }
        return Optional.empty();
    }
}

package com.fallingnight.chat.gateway.operations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict Linux {@code /proc/self/status} VmRSS reader. */
public final class LinuxProcResidentMemorySource implements ResidentMemorySource {
    static final long MAX_STATUS_BYTES = 64 * 1024;
    private static final Pattern VM_RSS = Pattern.compile(
            "^VmRSS:[ \\t]+([0-9]+)[ \\t]+kB[ \\t]*$");
    private final Path status;

    public LinuxProcResidentMemorySource() {
        this(Path.of("/proc/self/status"));
    }

    LinuxProcResidentMemorySource(Path status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    @Override
    public long readResidentBytes() throws IOException {
        long size = Files.size(status);
        if (size > MAX_STATUS_BYTES) {
            throw new IOException("proc status exceeds the bounded input size");
        }
        byte[] value;
        try (var input = Files.newInputStream(status)) {
            value = input.readNBytes(Math.toIntExact(MAX_STATUS_BYTES + 1));
        }
        if (value.length > MAX_STATUS_BYTES) {
            throw new IOException("proc status exceeds the bounded input size");
        }
        return parse(new String(value, StandardCharsets.US_ASCII));
    }

    static long parse(String status) throws IOException {
        Long residentBytes = null;
        for (String line : status.split("\\R", -1)) {
            Matcher matcher = VM_RSS.matcher(line);
            if (!matcher.matches()) continue;
            if (residentBytes != null) {
                throw new IOException("proc status contains duplicate VmRSS");
            }
            try {
                residentBytes = Math.multiplyExact(
                        Long.parseLong(matcher.group(1)), 1024L);
            } catch (NumberFormatException | ArithmeticException exception) {
                throw new IOException("proc VmRSS exceeds the supported range", exception);
            }
            if (residentBytes < 1) {
                throw new IOException("proc VmRSS must be positive");
            }
        }
        if (residentBytes == null) {
            throw new IOException("proc status does not contain a strict VmRSS value");
        }
        return residentBytes;
    }
}

package com.fallingnight.chat.identity.crypto;

import com.fallingnight.chat.application.notification.WebPushKeyCustodyPort;
import com.fallingnight.chat.application.security.SecretBytes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Owns Web Push encryption and lookup keys loaded from protected mounted files. */
public final class FileWebPushKeyCustody implements WebPushKeyCustodyPort, AutoCloseable {
    private static final int KEY_BYTES = 32;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Set<PosixFilePermission> FORBIDDEN_PERMISSIONS = Set.of(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<OpenOption> READ_NO_FOLLOW = Set.of(
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private final String activeKeyId;
    private final Map<String, SecretBytes> encryptionKeys;
    private final SecretBytes endpointLookupKey;
    private boolean closed;

    private FileWebPushKeyCustody(
            String activeKeyId,
            Map<String, SecretBytes> encryptionKeys,
            SecretBytes endpointLookupKey) {
        this.activeKeyId = activeKeyId;
        this.encryptionKeys = encryptionKeys;
        this.endpointLookupKey = endpointLookupKey;
    }

    public static FileWebPushKeyCustody load(
            String activeKeyId, Map<String, Path> encryptionKeyFiles, Path lookupKeyFile) {
        requireKeyId(activeKeyId);
        Objects.requireNonNull(encryptionKeyFiles, "encryptionKeyFiles");
        Objects.requireNonNull(lookupKeyFile, "lookupKeyFile");
        if (encryptionKeyFiles.isEmpty() || !encryptionKeyFiles.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("active Web Push encryption key is unavailable");
        }

        Map<String, SecretBytes> loaded = new LinkedHashMap<>();
        SecretBytes lookup = null;
        byte[] lookupBytes = null;
        try {
            for (Map.Entry<String, Path> entry : encryptionKeyFiles.entrySet()) {
                String keyId = requireKeyId(entry.getKey());
                byte[] bytes = readKey(entry.getValue());
                try {
                    rejectDuplicate(bytes, loaded);
                    loaded.put(keyId, SecretBytes.copyOf(bytes));
                } finally {
                    clear(bytes);
                }
            }
            lookupBytes = readKey(lookupKeyFile);
            rejectDuplicate(lookupBytes, loaded);
            lookup = SecretBytes.copyOf(lookupBytes);
            return new FileWebPushKeyCustody(activeKeyId, loaded, lookup);
        } catch (RuntimeException exception) {
            loaded.values().forEach(SecretBytes::close);
            if (lookup != null) lookup.close();
            throw exception;
        } finally {
            clear(lookupBytes);
        }
    }

    @Override
    public synchronized <T> T withActiveEncryptionKey(BiFunction<String, byte[], T> action) {
        Objects.requireNonNull(action, "action");
        return key(activeKeyId).withCopy(value -> action.apply(activeKeyId, value));
    }

    @Override
    public synchronized <T> T withEncryptionKey(String keyId, Function<byte[], T> action) {
        Objects.requireNonNull(action, "action");
        return key(requireKeyId(keyId)).withCopy(action);
    }

    @Override
    public synchronized <T> T withEndpointLookupKey(Function<byte[], T> action) {
        Objects.requireNonNull(action, "action");
        ensureOpen();
        return endpointLookupKey.withCopy(action);
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            encryptionKeys.values().forEach(SecretBytes::close);
            endpointLookupKey.close();
            closed = true;
        }
    }

    private SecretBytes key(String keyId) {
        ensureOpen();
        SecretBytes key = encryptionKeys.get(keyId);
        if (key == null) {
            throw new IllegalArgumentException("unknown Web Push encryption key ID");
        }
        return key;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Web Push key custody is closed");
    }

    private static String requireKeyId(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("invalid Web Push encryption key ID");
        }
        return keyId;
    }

    private static byte[] readKey(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.size() != KEY_BYTES) {
                throw new IllegalArgumentException(
                        "Web Push key file must be a regular 32-byte file");
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix == null) {
                throw new IllegalArgumentException(
                        "Web Push key files require POSIX permission enforcement");
            }
            Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || permissions.stream().anyMatch(FORBIDDEN_PERMISSIONS::contains)) {
                throw new IllegalArgumentException(
                        "Web Push key file must be owner-readable and inaccessible to group/others");
            }
            byte[] value = new byte[KEY_BYTES];
            try (SeekableByteChannel channel = Files.newByteChannel(path, READ_NO_FOLLOW)) {
                ByteBuffer destination = ByteBuffer.wrap(value);
                while (destination.hasRemaining() && channel.read(destination) >= 0) { }
                if (destination.hasRemaining() || channel.read(ByteBuffer.allocate(1)) != -1) {
                    clear(value);
                    throw new IllegalArgumentException(
                            "Web Push key file must contain exactly 32 bytes");
                }
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot read protected Web Push key file", exception);
        }
    }

    private static void rejectDuplicate(byte[] candidate, Map<String, SecretBytes> loaded) {
        for (SecretBytes existing : loaded.values()) {
            boolean duplicate = existing.withCopy(
                    value -> MessageDigest.isEqual(candidate, value));
            if (duplicate) {
                throw new IllegalArgumentException(
                        "Web Push keys must be cryptographically distinct");
            }
        }
    }

    private static void clear(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }
}

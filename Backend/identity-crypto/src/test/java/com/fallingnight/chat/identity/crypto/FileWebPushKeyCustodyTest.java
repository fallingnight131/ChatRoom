package com.fallingnight.chat.identity.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileWebPushKeyCustodyTest {
    @TempDir Path temp;

    @Test
    void loadsActiveOldAndIndependentLookupKeysAndClearsCallbackCopies() throws Exception {
        Path oldKey = keyFile("old.key", 1);
        Path activeKey = keyFile("active.key", 2);
        Path lookupKey = keyFile("lookup.key", 3);
        Map<String, Path> ring = new LinkedHashMap<>();
        ring.put("enc-v1", oldKey);
        ring.put("enc-v2", activeKey);

        FileWebPushKeyCustody custody = FileWebPushKeyCustody.load(
                "enc-v2", ring, lookupKey);
        byte[] leakedCopy = custody.withActiveEncryptionKey((keyId, key) -> {
            assertEquals("enc-v2", keyId);
            assertArrayEquals(bytes(2), key);
            return key;
        });
        assertArrayEquals(new byte[32], leakedCopy);
        custody.withEncryptionKey("enc-v1", key -> {
            assertArrayEquals(bytes(1), key);
            return null;
        });
        custody.withEndpointLookupKey(key -> {
            assertArrayEquals(bytes(3), key);
            return null;
        });

        custody.close();
        assertTrue(custody.isClosed());
        assertThrows(IllegalStateException.class,
                () -> custody.withEndpointLookupKey(key -> null));
    }

    @Test
    void rejectsMissingActiveKeyInvalidIdsAndDuplicatePurposes() throws Exception {
        Path first = keyFile("first.key", 1);
        Path same = keyFile("same.key", 1);
        Path lookup = keyFile("lookup.key", 2);
        assertThrows(IllegalArgumentException.class,
                () -> FileWebPushKeyCustody.load("missing", Map.of("enc-v1", first), lookup));
        assertThrows(IllegalArgumentException.class,
                () -> FileWebPushKeyCustody.load("../bad", Map.of("../bad", first), lookup));
        assertThrows(IllegalArgumentException.class,
                () -> FileWebPushKeyCustody.load("enc-v1", Map.of("enc-v1", first), same));
    }

    @Test
    void rejectsWrongSizeBroadPermissionsAndSymbolicLinks() throws Exception {
        Path lookup = keyFile("lookup.key", 3);
        Path shortKey = temp.resolve("short.key");
        Files.write(shortKey, new byte[31]);
        protect(shortKey);
        assertThrows(IllegalArgumentException.class,
                () -> FileWebPushKeyCustody.load(
                        "enc-v1", Map.of("enc-v1", shortKey), lookup));

        Path broad = keyFile("broad.key", 4);
        Files.setPosixFilePermissions(broad, PosixFilePermissions.fromString("rw-r-----"));
        assertThrows(IllegalArgumentException.class,
                () -> FileWebPushKeyCustody.load(
                        "enc-v1", Map.of("enc-v1", broad), lookup));

        Path target = keyFile("target.key", 5);
        Path link = temp.resolve("linked.key");
        Files.createSymbolicLink(link, target);
        assertThrows(IllegalArgumentException.class,
                () -> FileWebPushKeyCustody.load(
                        "enc-v1", Map.of("enc-v1", link), lookup));
    }

    private Path keyFile(String name, int value) throws Exception {
        Path path = temp.resolve(name);
        Files.write(path, bytes(value));
        protect(path);
        return path;
    }

    private static void protect(Path path) throws Exception {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private static byte[] bytes(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}

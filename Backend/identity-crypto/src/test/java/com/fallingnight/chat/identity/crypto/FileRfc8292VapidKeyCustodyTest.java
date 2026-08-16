package com.fallingnight.chat.identity.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileRfc8292VapidKeyCustodyTest {
    @TempDir Path temp;

    @Test
    void loadsMatchingProtectedFilesSignsAndRefusesUseAfterClose() throws Exception {
        KeyPair keys = keys();
        Path privateFile = protectedFile("vapid.pk8", keys.getPrivate().getEncoded());
        Path publicFile = protectedFile("vapid.x509", keys.getPublic().getEncoded());
        FileRfc8292VapidKeyCustody custody = FileRfc8292VapidKeyCustody.load(
                privateFile, publicFile, URI.create("mailto:push@example.com"),
                Clock.systemUTC(), Duration.ofMinutes(10));
        Rfc8292VapidSigner expected = new Rfc8292VapidSigner(keys,
                URI.create("mailto:push@example.com"), Clock.systemUTC(), Duration.ofMinutes(10));
        assertArrayEquals(expected.publicKeyCopy(), custody.publicKeyCopy());
        try (Rfc8292VapidAuthorization authorization =
                     custody.sign(URI.create("https://push.example/path"))) {
            boolean boundedAuthorization = authorization.withAsciiCopy(value -> value.length > 100);
            assertTrue(boundedAuthorization);
        }
        custody.close();
        assertTrue(custody.isClosed());
        assertThrows(IllegalStateException.class,
                () -> custody.sign(URI.create("https://push.example/path")));
    }

    @Test
    void rejectsMismatchedBroadOversizedAndLinkedFiles() throws Exception {
        KeyPair first = keys(); KeyPair second = keys();
        Path privateFile = protectedFile("first.pk8", first.getPrivate().getEncoded());
        Path otherPublic = protectedFile("other.x509", second.getPublic().getEncoded());
        assertThrows(IllegalArgumentException.class, () -> FileRfc8292VapidKeyCustody.load(
                privateFile, otherPublic, URI.create("mailto:push@example.com"),
                Clock.systemUTC(), Duration.ofMinutes(10)));

        Path broad = protectedFile("broad.x509", first.getPublic().getEncoded());
        Files.setPosixFilePermissions(broad, PosixFilePermissions.fromString("rw-r-----"));
        assertThrows(IllegalArgumentException.class, () -> FileRfc8292VapidKeyCustody.load(
                privateFile, broad, URI.create("mailto:push@example.com"),
                Clock.systemUTC(), Duration.ofMinutes(10)));

        Path oversized = protectedFile("oversized.pk8", new byte[1_025]);
        assertThrows(IllegalArgumentException.class, () -> FileRfc8292VapidKeyCustody.load(
                oversized, otherPublic, URI.create("mailto:push@example.com"),
                Clock.systemUTC(), Duration.ofMinutes(10)));

        Path link = temp.resolve("linked.pk8"); Files.createSymbolicLink(link, privateFile);
        assertThrows(IllegalArgumentException.class, () -> FileRfc8292VapidKeyCustody.load(
                link, otherPublic, URI.create("mailto:push@example.com"),
                Clock.systemUTC(), Duration.ofMinutes(10)));
    }

    private Path protectedFile(String name, byte[] bytes) throws Exception {
        Path path = temp.resolve(name); Files.write(path, bytes);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        return path;
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}

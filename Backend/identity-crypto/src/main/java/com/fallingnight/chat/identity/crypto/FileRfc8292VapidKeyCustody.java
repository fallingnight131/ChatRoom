package com.fallingnight.chat.identity.crypto;

import java.io.IOException;
import java.net.URI;
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
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Strict mounted-file custody for one RFC 8292 P-256 signing identity. */
public final class FileRfc8292VapidKeyCustody implements AutoCloseable {
    private static final int MAX_KEY_FILE_BYTES = 1_024;
    private static final Set<PosixFilePermission> FORBIDDEN_PERMISSIONS = Set.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<OpenOption> READ_NO_FOLLOW = Set.of(
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private final Rfc8292VapidSigner signer;

    private FileRfc8292VapidKeyCustody(Rfc8292VapidSigner signer) {
        this.signer = signer;
    }

    public static FileRfc8292VapidKeyCustody load(
            Path privatePkcs8File,
            Path publicX509File,
            URI subject,
            Clock clock,
            Duration lifetime) {
        byte[] privateEncoded = readKey(privatePkcs8File);
        byte[] publicEncoded = readKey(publicX509File);
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateEncoded));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicEncoded));
            requireMatchingPair(privateKey, publicKey);
            return new FileRfc8292VapidKeyCustody(new Rfc8292VapidSigner(
                    new KeyPair(publicKey, privateKey), subject, clock, lifetime));
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("invalid protected VAPID signing key pair", exception);
        } finally {
            Arrays.fill(privateEncoded, (byte) 0);
            Arrays.fill(publicEncoded, (byte) 0);
        }
    }

    public synchronized Rfc8292VapidAuthorization sign(URI pushEndpoint) {
        return signer.sign(pushEndpoint);
    }

    public synchronized byte[] publicKeyCopy() {
        return signer.publicKeyCopy();
    }

    public synchronized boolean isClosed() {
        return signer.isClosed();
    }

    @Override
    public synchronized void close() {
        signer.close();
    }

    @Override
    public String toString() {
        return "FileRfc8292VapidKeyCustody[key=REDACTED]";
    }

    private static byte[] readKey(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.size() < 1 || attributes.size() > MAX_KEY_FILE_BYTES) {
                throw new IllegalArgumentException("VAPID key must be a bounded regular file");
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix == null) {
                throw new IllegalArgumentException("VAPID key files require POSIX permission enforcement");
            }
            Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || permissions.stream().anyMatch(FORBIDDEN_PERMISSIONS::contains)) {
                throw new IllegalArgumentException(
                        "VAPID key file must be owner-readable and inaccessible to group/others");
            }
            byte[] result = new byte[(int) attributes.size()];
            try (SeekableByteChannel channel = Files.newByteChannel(path, READ_NO_FOLLOW)) {
                ByteBuffer destination = ByteBuffer.wrap(result);
                while (destination.hasRemaining() && channel.read(destination) >= 0) { }
                if (destination.hasRemaining() || channel.read(ByteBuffer.allocate(1)) != -1) {
                    Arrays.fill(result, (byte) 0);
                    throw new IllegalArgumentException("VAPID key file changed while being read");
                }
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot read protected VAPID key file", exception);
        }
    }

    private static void requireMatchingPair(PrivateKey privateKey, PublicKey publicKey)
            throws GeneralSecurityException {
        byte[] challenge = "chatroom-vapid-key-pair-check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey); signer.update(challenge);
        byte[] signature = signer.sign();
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey); verifier.update(challenge);
            if (!verifier.verify(signature)) {
                throw new IllegalArgumentException("VAPID public and private keys do not match");
            }
        } finally {
            Arrays.fill(challenge, (byte) 0);
            Arrays.fill(signature, (byte) 0);
        }
    }
}

package com.fallingnight.chat.storage.s3;

import com.fallingnight.chat.application.profile.CanonicalProfileImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;
import javax.imageio.ImageIO;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/** Explicit operator command; never invoked by the gateway or ordinary checks. */
public final class S3ProfileImageCapabilityProbeMain {
    static final String CONFIRMATION = "CREATE_READ_AND_DELETE_TEST_OBJECT";
    private S3ProfileImageCapabilityProbeMain() { }

    public static void main(String[] arguments) {
        if (arguments.length != 0) {
            System.err.println("profile-image object-store capability probe: FAIL "
                    + "(no arguments accepted)");
            System.exit(2);
        }
        int status = execute(System.getenv(), System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int execute(Map<String, String> environment,
            PrintStream output, PrintStream errors) {
        try {
            requireConfirmation(environment); requireCredentialProvider(environment);
            S3AttachmentStorageConfig config =
                    S3AttachmentStorageConfig.fromEnvironment(environment);
            try (DefaultCredentialsProvider credentials =
                            DefaultCredentialsProvider.builder().build();
                    S3AttachmentObjectStoreRuntime runtime =
                            S3AttachmentObjectStoreRuntime.open(
                                    config, credentials, Clock.systemUTC())) {
                SecureRandom random = new SecureRandom();
                var probe = new S3ProfileImageCapabilityProbe(
                        runtime.profileImageWriter(), runtime.profileImageReader(),
                        runtime.profileImageDeleter(), () -> randomImage(random));
                var report = probe.run();
                output.printf("profile-image object-store capability probe: PASS "
                                + "put=%s retry=%s read=%s cleanup=%s%n",
                        report.firstPutVerified(), report.retryVerified(),
                        report.readVerified(), report.cleanupVerified());
            }
            return 0;
        } catch (RuntimeException exception) {
            errors.println("profile-image object-store capability probe: FAIL ("
                    + safeMessage(exception) + ")");
            return 1;
        }
    }

    private static CanonicalProfileImage randomImage(SecureRandom random) {
        try {
            BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output))
                throw new IllegalStateException("PNG writer unavailable");
            byte[] bytes = output.toByteArray();
            return new CanonicalProfileImage(bytes, image.getWidth(), image.getHeight(),
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("profile-image probe PNG generation failed");
        }
    }

    private static void requireConfirmation(Map<String, String> environment) {
        if (!CONFIRMATION.equals(environment.get("CHATROOM_PROFILE_IMAGE_S3_PROBE_CONFIRM")))
            throw new IllegalArgumentException(
                    "explicit profile-image create/read/delete confirmation is required");
    }
    private static void requireCredentialProvider(Map<String, String> environment) {
        if (!"default-chain".equals(environment.get(
                "CHATROOM_PROFILE_IMAGE_S3_PROBE_CREDENTIAL_PROVIDER")))
            throw new IllegalArgumentException(
                    "explicit profile-image default credential-chain selection is required");
    }
    private static String safeMessage(RuntimeException exception) {
        if (exception instanceof ProfileImageObjectStoreCapabilityProbeException)
            return exception.getMessage();
        if (exception instanceof IllegalArgumentException) {
            String message = exception.getMessage();
            if (message != null && (message.startsWith("missing required environment value:")
                    || message.equals("invalid attachment S3 configuration")
                    || message.equals("explicit profile-image create/read/delete confirmation is required")
                    || message.equals("explicit profile-image default credential-chain selection is required")))
                return message;
        }
        return "provider setup or probe execution failed without printable detail";
    }
}

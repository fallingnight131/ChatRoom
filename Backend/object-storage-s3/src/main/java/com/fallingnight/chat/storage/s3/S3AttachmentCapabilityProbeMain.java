package com.fallingnight.chat.storage.s3;

import java.io.PrintStream;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/** Explicit operator command; never invoked by the gateway or ordinary checks. */
public final class S3AttachmentCapabilityProbeMain {
    static final String CONFIRMATION = "CREATE_AND_DELETE_TEST_OBJECT";
    private static final int PAYLOAD_BYTES = 256;

    private S3AttachmentCapabilityProbeMain() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 0) {
            System.err.println("attachment object-store capability probe: FAIL (no arguments accepted)");
            System.exit(2);
        }
        int status = execute(System.getenv(), System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int execute(
            Map<String, String> environment, PrintStream output, PrintStream errors) {
        try {
            requireConfirmation(environment);
            URI webOrigin = webOrigin(environment);
            S3AttachmentStorageConfig config =
                    S3AttachmentStorageConfig.fromEnvironment(environment);
            requireCredentialProvider(environment);
            SecureRandom random = new SecureRandom();
            try (DefaultCredentialsProvider credentials =
                            DefaultCredentialsProvider.builder().build();
                    S3AttachmentObjectStoreRuntime runtime =
                            S3AttachmentObjectStoreRuntime.open(
                                    config, credentials, Clock.systemUTC())) {
                S3AttachmentCapabilityProbe probe = new S3AttachmentCapabilityProbe(
                        runtime,
                        runtime,
                        JdkS3AttachmentProbeHttpClient.create(),
                        Clock.systemUTC(),
                        UUID::randomUUID,
                        () -> randomPayload(random));
                S3AttachmentCapabilityProbe.CapabilityReport report =
                        probe.run(webOrigin);
                output.printf(
                        "attachment object-store capability probe: PASS "
                                + "cors=%s put=%s replay=%s checksum=%s cleanup=true%n",
                        report.corsAccepted(),
                        report.firstPutAccepted(),
                        report.replayRejected(),
                        report.checksumVerified());
            }
            return 0;
        } catch (RuntimeException exception) {
            errors.println("attachment object-store capability probe: FAIL ("
                    + safeMessage(exception) + ")");
            return 1;
        }
    }

    private static byte[] randomPayload(SecureRandom random) {
        byte[] payload = new byte[PAYLOAD_BYTES];
        random.nextBytes(payload);
        return payload;
    }

    private static void requireConfirmation(Map<String, String> environment) {
        if (!CONFIRMATION.equals(
                environment.get("CHATROOM_ATTACHMENT_S3_PROBE_CONFIRM"))) {
            throw new IllegalArgumentException(
                    "explicit create-and-delete confirmation is required");
        }
    }

    private static void requireCredentialProvider(Map<String, String> environment) {
        if (!"default-chain".equals(
                environment.get("CHATROOM_ATTACHMENT_S3_PROBE_CREDENTIAL_PROVIDER"))) {
            throw new IllegalArgumentException(
                    "explicit default credential-chain selection is required");
        }
    }

    private static URI webOrigin(Map<String, String> environment) {
        String value = environment.get("CHATROOM_ATTACHMENT_S3_PROBE_WEB_ORIGIN");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("probe Web origin is required");
        }
        try {
            URI origin = URI.create(value);
            S3AttachmentCapabilityProbe.requireWebOrigin(origin);
            return origin;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("probe Web origin is invalid");
        }
    }

    private static String safeMessage(RuntimeException exception) {
        if (exception instanceof AttachmentObjectStoreCapabilityProbeException) {
            return exception.getMessage();
        }
        if (exception instanceof IllegalArgumentException) {
            String message = exception.getMessage();
            if (message != null && (message.startsWith("missing required environment value:")
                    || message.equals("invalid attachment S3 configuration")
                    || message.equals("explicit create-and-delete confirmation is required")
                    || message.equals("explicit default credential-chain selection is required")
                    || message.equals("probe Web origin is required")
                    || message.equals("probe Web origin is invalid"))) {
                return message;
            }
        }
        return "provider setup or probe execution failed without printable detail";
    }
}

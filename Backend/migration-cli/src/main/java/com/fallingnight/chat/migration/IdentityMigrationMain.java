package com.fallingnight.chat.migration;

import com.fallingnight.chat.persistence.postgres.PostgresMigrator;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1IdentityImporter;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1ConversationImporter;
import com.fallingnight.chat.persistence.postgres.migration.V1ConversationImportInputVerifier;
import com.fallingnight.chat.persistence.postgres.migration.V1ConversationImportPlan;
import com.fallingnight.chat.persistence.postgres.migration.V1ConversationImportReport;
import com.fallingnight.chat.persistence.postgres.migration.V1SqliteConversationSource;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityBackupProofFile;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportInputVerifier;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportPlan;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportReport;
import com.fallingnight.chat.persistence.postgres.migration.V1SqliteIdentityBackup;
import com.fallingnight.chat.persistence.postgres.migration.V1SqliteIdentitySource;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1IdentityBackup;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1IdentityImportInput;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1ConversationImportInput;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.postgresql.ds.PGSimpleDataSource;

/** Offline operator entry point for the inactive M3 V1 identity migration slice. */
public final class IdentityMigrationMain {
    private static final String URL = "CHATROOM_MIGRATION_POSTGRES_URL";
    private static final String USER = "CHATROOM_MIGRATION_POSTGRES_USER";
    private static final String PASSWORD = "CHATROOM_MIGRATION_POSTGRES_PASSWORD";

    private IdentityMigrationMain() {}

    public static void main(String[] args) {
        int status = run(args, System.getenv(), System.out, System.err, Clock.systemUTC());
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            PrintStream output,
            PrintStream error,
            Clock clock) {
        Objects.requireNonNull(args, "args");
        try {
            if (args.length == 4 && "backup".equals(args[0])) {
                return backup(args, output, clock);
            }
            if (args.length == 2 && "preview".equals(args[0])) {
                return preview(args, environment, output);
            }
            if (args.length == 3 && "verify-backup".equals(args[0])) {
                return verifyBackup(args, output);
            }
            if (args.length == 5 && "verify-final".equals(args[0])) {
                return verifyFinal(args, output);
            }
            if (args.length == 5 && "apply".equals(args[0])) {
                return apply(args, environment, output);
            }
            if (args.length == 2 && "conversation-preview".equals(args[0])) {
                return conversationPreview(args, environment, output);
            }
            if (args.length == 5 && "conversation-verify-final".equals(args[0])) {
                return conversationVerifyFinal(args, output);
            }
            if (args.length == 5 && "conversation-apply".equals(args[0])) {
                return conversationApply(args, environment, output);
            }
            usage(error);
            return 64;
        } catch (RuntimeException exception) {
            error.println("status=FAILED");
            error.println("reason=migration_operation_failed");
            return 70;
        }
    }

    private static int backup(String[] args, PrintStream output, Clock clock) {
        Path source = Path.of(args[1]);
        Path backup = Path.of(args[2]);
        Path proofFile = Path.of(args[3]);
        if (java.nio.file.Files.exists(proofFile)) {
            throw new IllegalArgumentException("proof destination exists");
        }
        VerifiedV1IdentityBackup proof = new V1SqliteIdentityBackup(clock)
                .createVerified(source, backup);
        try {
            new V1IdentityBackupProofFile().writeNew(proofFile, proof);
        } catch (RuntimeException exception) {
            try {
                java.nio.file.Files.deleteIfExists(backup);
            } catch (java.io.IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        output.println("status=BACKUP_VERIFIED");
        output.println("source_fingerprint_sha256=" + proof.sourceFingerprintSha256());
        output.println("identity_rows=" + proof.identityRows());
        return 0;
    }

    private static int preview(
            String[] args,
            Map<String, String> environment,
            PrintStream output) {
        V1IdentityImportPlan plan = new V1SqliteIdentitySource(Path.of(args[1])).readPlan();
        if (!plan.readyToCompareWithTarget()) {
            output.println("status=BLOCKED");
            output.println("source_fingerprint_sha256=" + plan.sourceFingerprintSha256());
            output.println("source_issues=" + plan.issues().size());
            return 2;
        }
        V1IdentityImportReport report = importer(environment).preview(plan);
        printReport(output, report, report.readyToApply() ? "READY" : "BLOCKED");
        return report.readyToApply() ? 0 : 2;
    }

    private static int verifyBackup(String[] args, PrintStream output) {
        Path backup = Path.of(args[1]);
        VerifiedV1IdentityBackup proof = new V1IdentityBackupProofFile()
                .read(Path.of(args[2]));
        var verified = new V1IdentityImportInputVerifier()
                .verify(backup, backup, proof);
        output.println("status=BACKUP_VERIFIED");
        output.println("source_fingerprint_sha256="
                + verified.plan().sourceFingerprintSha256());
        output.println("identity_rows=" + verified.plan().sourceRows());
        return 0;
    }

    private static int apply(
            String[] args,
            Map<String, String> environment,
            PrintStream output) {
        var input = verifiedFinalInput(args[1], args[2], args[3], args[4]);
        V1IdentityImportReport report = importer(environment).apply(input);
        printReport(output, report, "APPLIED");
        return 0;
    }

    private static int verifyFinal(String[] args, PrintStream output) {
        var input = verifiedFinalInput(args[1], args[2], args[3], args[4]);
        output.println("status=FINAL_INPUT_VERIFIED");
        output.println("source_fingerprint_sha256="
                + input.plan().sourceFingerprintSha256());
        output.println("identity_rows=" + input.plan().sourceRows());
        return 0;
    }

    private static VerifiedV1IdentityImportInput verifiedFinalInput(
            String source, String backup, String proofPath, String expectedFingerprint) {
        if (!expectedFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid confirmation fingerprint");
        }
        VerifiedV1IdentityBackup proof = new V1IdentityBackupProofFile()
                .read(Path.of(proofPath));
        if (!expectedFingerprint.equals(proof.sourceFingerprintSha256())) {
            throw new IllegalArgumentException("confirmation fingerprint mismatch");
        }
        return new V1IdentityImportInputVerifier()
                .verify(Path.of(source), Path.of(backup), proof);
    }

    private static int conversationPreview(
            String[] args, Map<String, String> environment, PrintStream output) {
        V1ConversationImportPlan plan =
                new V1SqliteConversationSource(Path.of(args[1])).readPlan();
        if (!plan.readyToCompareWithTarget()) {
            output.println("status=BLOCKED");
            output.println("conversation_fingerprint_sha256="
                    + plan.sourceFingerprintSha256());
            output.println("source_issues=" + plan.issues().size());
            return 2;
        }
        V1ConversationImportReport report = conversationImporter(environment).preview(plan);
        printConversationReport(output, report, report.readyToApply() ? "READY" : "BLOCKED");
        return report.readyToApply() ? 0 : 2;
    }

    private static int conversationVerifyFinal(String[] args, PrintStream output) {
        VerifiedV1ConversationImportInput input = verifiedConversationInput(
                args[1], args[2], args[3], args[4]);
        output.println("status=CONVERSATION_FINAL_INPUT_VERIFIED");
        output.println("conversation_fingerprint_sha256="
                + input.plan().sourceFingerprintSha256());
        output.println("source_conversations=" + input.plan().conversations().size());
        output.println("source_memberships=" + input.plan().memberships().size());
        return 0;
    }

    private static int conversationApply(
            String[] args, Map<String, String> environment, PrintStream output) {
        VerifiedV1ConversationImportInput input = verifiedConversationInput(
                args[1], args[2], args[3], args[4]);
        V1ConversationImportReport report = conversationImporter(environment).apply(input);
        printConversationReport(output, report, "APPLIED");
        return 0;
    }

    private static VerifiedV1ConversationImportInput verifiedConversationInput(
            String source, String backup, String proofPath, String expectedFingerprint) {
        if (!expectedFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid conversation confirmation fingerprint");
        }
        VerifiedV1IdentityBackup proof = new V1IdentityBackupProofFile()
                .read(Path.of(proofPath));
        VerifiedV1ConversationImportInput input = new V1ConversationImportInputVerifier()
                .verify(Path.of(source), Path.of(backup), proof);
        if (!expectedFingerprint.equals(input.plan().sourceFingerprintSha256())) {
            throw new IllegalArgumentException("conversation confirmation fingerprint mismatch");
        }
        return input;
    }

    private static PostgresV1IdentityImporter importer(Map<String, String> environment) {
        return new PostgresV1IdentityImporter(dataSource(environment));
    }

    private static PostgresV1ConversationImporter conversationImporter(
            Map<String, String> environment) {
        return new PostgresV1ConversationImporter(dataSource(environment));
    }

    private static PGSimpleDataSource dataSource(Map<String, String> environment) {
        String url = required(environment, URL);
        String user = required(environment, USER);
        String password = environment.getOrDefault(PASSWORD, "");
        new PostgresMigrator(url, user, password).validate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("required migration environment is missing");
        }
        return value;
    }

    private static void printReport(
            PrintStream output,
            V1IdentityImportReport report,
            String status) {
        output.println("status=" + status);
        output.println("source_fingerprint_sha256=" + report.sourceFingerprintSha256());
        output.println("source_rows=" + report.sourceRows());
        output.println("insertable_rows=" + report.insertableRows());
        output.println("already_imported_rows=" + report.alreadyImportedRows());
        output.println("inserted_rows=" + report.insertedRows());
        output.println("unexpected_target_rows=" + report.unexpectedTargetRows());
        output.println("issues=" + report.issues().size());
        report.issues().forEach(issue -> output.println(
                "issue=" + issue.legacyId() + ":" + issue.code()));
        if (report.importRunId() != null) {
            output.println("import_run_id=" + report.importRunId());
        }
    }

    private static void printConversationReport(
            PrintStream output, V1ConversationImportReport report, String status) {
        output.println("status=" + status);
        output.println("conversation_fingerprint_sha256=" + report.sourceFingerprintSha256());
        output.println("source_conversations=" + report.sourceConversations());
        output.println("source_memberships=" + report.sourceMemberships());
        output.println("insertable_conversations=" + report.insertableConversations());
        output.println("already_imported_conversations=" + report.alreadyImportedConversations());
        output.println("insertable_memberships=" + report.insertableMemberships());
        output.println("already_imported_memberships=" + report.alreadyImportedMemberships());
        output.println("issues=" + report.issues().size());
        report.issues().forEach(issue -> output.println(
                "issue=" + issue.kind() + ":" + issue.legacyId() + ":" + issue.code()));
        if (report.importRunId() != null) {
            output.println("import_run_id=" + report.importRunId());
        }
    }

    private static void usage(PrintStream error) {
        error.println("usage:");
        error.println("  backup <v1-source.db> <new-backup.db> <new-proof.properties>");
        error.println("  verify-backup <restored-backup.db> <proof.properties>");
        error.println("  preview <v1-source.db>");
        error.println("  verify-final <v1-source.db> <backup.db> <proof.properties> <fingerprint>");
        error.println("  apply <v1-source.db> <backup.db> <proof.properties> <fingerprint>");
        error.println("  conversation-preview <v1-source.db>");
        error.println("  conversation-verify-final <v1-source.db> <backup.db> "
                + "<proof.properties> <conversation-fingerprint>");
        error.println("  conversation-apply <v1-source.db> <backup.db> "
                + "<proof.properties> <conversation-fingerprint>");
    }
}

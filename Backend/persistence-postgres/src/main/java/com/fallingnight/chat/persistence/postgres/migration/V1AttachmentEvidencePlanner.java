package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fail-closed reconciliation of a V1 source graph with independently sealed objects. */
public final class V1AttachmentEvidencePlanner {
    private static final String UNAVAILABLE_REASON = "legacy-v1-file-cleared";

    public V1AttachmentImportPlan plan(
            V1AttachmentSourcePlan source,
            V1AttachmentObjectEvidenceBundle bundle) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(bundle, "bundle");
        List<V1AttachmentImportIssue> issues = new ArrayList<>();
        if (!source.readyForObjectEvidence()) {
            issues.add(issue(null, 0, "SOURCE_GRAPH_NOT_READY",
                    "V1 attachment source graph has blocking issues"));
        }
        if (!source.sourceFingerprintSha256().equals(bundle.sourceFingerprintSha256())) {
            issues.add(issue(null, 0, "SOURCE_FINGERPRINT_MISMATCH",
                    "object evidence was produced for a different V1 source graph"));
        }

        List<V1AttachmentObjectEvidence> evidence = new ArrayList<>(bundle.objects());
        evidence.sort(evidenceOrder());
        Map<FileKey, V1AttachmentObjectEvidence> evidenceByFile = new HashMap<>();
        Set<FileKey> duplicateEvidence = new HashSet<>();
        for (V1AttachmentObjectEvidence row : evidence) {
            FileKey key = new FileKey(row.legacyKind(), row.legacyFileId());
            if (evidenceByFile.putIfAbsent(key, row) != null) {
                duplicateEvidence.add(key);
                issues.add(issue(row.legacyKind(), row.legacyFileId(),
                        "DUPLICATE_OBJECT_EVIDENCE",
                        "object evidence identity is duplicated"));
            }
        }

        Set<FileKey> sourceKeys = new HashSet<>();
        List<PlannedV1AttachmentImport> planned = new ArrayList<>();
        for (PlannedV1AttachmentSource attachment : source.attachments()) {
            FileKey key = new FileKey(attachment.legacyKind(), attachment.legacyFileId());
            sourceKeys.add(key);
            if (duplicateEvidence.contains(key)) continue;
            V1AttachmentObjectEvidence object = evidenceByFile.get(key);
            int before = issues.size();
            if (attachment.cleared()) {
                if (object != null) {
                    issues.add(issue(key.kind(), key.id(), "EVIDENCE_FOR_CLEARED_FILE",
                            "cleared V1 history must not carry target object evidence"));
                }
                if (issues.size() == before) {
                    planned.add(unavailable(attachment));
                }
                continue;
            }
            if (object == null) {
                issues.add(issue(key.kind(), key.id(), "MISSING_OBJECT_EVIDENCE",
                        "active V1 file requires independently verified object evidence"));
                continue;
            }
            validateObject(attachment, object, issues);
            if (issues.size() == before) {
                planned.add(ready(attachment, object));
            }
        }
        for (V1AttachmentObjectEvidence row : evidence) {
            FileKey key = new FileKey(row.legacyKind(), row.legacyFileId());
            if (!sourceKeys.contains(key)) {
                issues.add(issue(key.kind(), key.id(), "UNKNOWN_OBJECT_EVIDENCE",
                        "object evidence has no V1 attachment source row"));
            }
        }
        planned.sort(Comparator.comparing(
                        (PlannedV1AttachmentImport row) -> row.source().legacyKind())
                .thenComparingLong(row -> row.source().legacyFileId()));
        issues.sort(Comparator.comparing(V1AttachmentImportIssue::legacyKind,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(V1AttachmentImportIssue::legacyFileId)
                .thenComparing(V1AttachmentImportIssue::code));
        return new V1AttachmentImportPlan(source.sourceFingerprintSha256(),
                fingerprint(bundle), source.sourceFiles(), evidence.size(), planned, issues);
    }

    private static void validateObject(PlannedV1AttachmentSource source,
            V1AttachmentObjectEvidence object, List<V1AttachmentImportIssue> issues) {
        String expectedKey = "attachments/" + source.attachmentId();
        if (!expectedKey.equals(object.objectKey())) {
            issues.add(issue(source.legacyKind(), source.legacyFileId(),
                    "OBJECT_KEY_MISMATCH", "target object key is not the canonical server key"));
        }
        if (object.byteSize() != source.byteSize()) {
            issues.add(issue(source.legacyKind(), source.legacyFileId(),
                    "OBJECT_SIZE_MISMATCH", "sealed object size differs from V1 metadata"));
        }
        if (!validMediaType(object.mediaType())) {
            issues.add(issue(source.legacyKind(), source.legacyFileId(),
                    "INVALID_OBJECT_MEDIA_TYPE", "verified object media type is invalid"));
        }
        if (object.contentSha256() == null || object.contentSha256().length != 32) {
            issues.add(issue(source.legacyKind(), source.legacyFileId(),
                    "INVALID_OBJECT_SHA256", "verified object SHA-256 must contain 32 bytes"));
        }
        if (object.sealedAt() == null || object.sealedAt().isBefore(source.fileCreatedAt())) {
            issues.add(issue(source.legacyKind(), source.legacyFileId(),
                    "INVALID_OBJECT_SEALED_AT",
                    "sealed object time must not precede V1 file creation"));
        }
    }

    private static boolean validMediaType(String value) {
        return value != null && !value.isBlank()
                && value.getBytes(StandardCharsets.UTF_8).length <= 127
                && value.indexOf('/') > 0
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static PlannedV1AttachmentImport ready(
            PlannedV1AttachmentSource source, V1AttachmentObjectEvidence object) {
        return new PlannedV1AttachmentImport(source,
                java.util.Optional.of(object.objectKey()),
                java.util.Optional.of(object.mediaType()),
                java.util.Optional.of(object.contentSha256()),
                java.util.Optional.of(object.sealedAt()), java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    private static PlannedV1AttachmentImport unavailable(PlannedV1AttachmentSource source) {
        return new PlannedV1AttachmentImport(source, java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.of(source.clearedAt()),
                java.util.Optional.of(UNAVAILABLE_REASON));
    }

    private static String fingerprint(V1AttachmentObjectEvidenceBundle bundle) {
        List<V1AttachmentObjectEvidence> rows = new ArrayList<>(bundle.objects());
        rows.sort(evidenceOrder());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream data = new DataOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                write(data, bundle.sourceFingerprintSha256());
                data.writeInt(rows.size());
                for (V1AttachmentObjectEvidence row : rows) {
                    write(data, row.legacyKind() == null ? null : row.legacyKind().name());
                    data.writeLong(row.legacyFileId());
                    write(data, row.objectKey()); write(data, row.mediaType());
                    data.writeLong(row.byteSize());
                    byte[] hash = row.contentSha256();
                    if (hash == null) data.writeInt(-1);
                    else { data.writeInt(hash.length); data.write(hash); }
                    write(data, row.sealedAt() == null ? null : row.sealedAt().toString());
                }
                return HexFormat.of().formatHex(digest.digest());
            }
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("V1 attachment evidence fingerprint failed", exception);
        }
    }

    private static void write(DataOutputStream data, String value) throws IOException {
        if (value == null) { data.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length); data.write(bytes);
    }

    private static Comparator<V1AttachmentObjectEvidence> evidenceOrder() {
        return Comparator.comparing(V1AttachmentObjectEvidence::legacyKind,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingLong(V1AttachmentObjectEvidence::legacyFileId)
                .thenComparing(row -> Objects.toString(row.objectKey(), ""));
    }

    private static V1AttachmentImportIssue issue(
            LegacyV1ConversationKind kind, long fileId, String code, String message) {
        return new V1AttachmentImportIssue(kind, fileId, code, message);
    }

    private record FileKey(LegacyV1ConversationKind kind, long id) { }
}

package com.fallingnight.chat.migration.profile;

import com.fallingnight.chat.application.profile.CanonicalProfileImage;
import com.fallingnight.chat.application.profile.LegacyV1AvatarUpload;
import com.fallingnight.chat.application.profile.ProfileImageObjectEvidence;
import com.fallingnight.chat.application.profile.ProfileImageObjectWritePort;
import com.fallingnight.chat.persistence.postgres.migration.ProviderVerifiedV1ProfileImageImportInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded create-only upload pass over an independently verified export. */
public final class V1ProfileImageObjectUploader {
    private final ProfileImageObjectWritePort writer;

    public V1ProfileImageObjectUploader(ProfileImageObjectWritePort writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public V1ProfileImageObjectUploadReport upload(VerifiedV1ProfileImageExport export) {
        Objects.requireNonNull(export, "export");
        Map<String, VerifiedV1ProfileImageExport.Entry> unique = new LinkedHashMap<>();
        for (VerifiedV1ProfileImageExport.Entry entry : export.entries()) if (entry.present())
            unique.putIfAbsent(entry.object().objectKey(), entry);
        List<ProfileImageObjectEvidence> confirmed = new ArrayList<>(unique.size());
        int created = 0;
        for (VerifiedV1ProfileImageExport.Entry entry : unique.values()) {
            byte[] bytes = readExact(export.directory(), entry.object());
            try {
                CanonicalProfileImage image = new CanonicalProfileImage(bytes,
                        entry.width(), entry.height(), entry.object().contentSha256());
                var result = writer.storeIfAbsent(image);
                if (!exact(entry.object(), result.evidence()))
                    throw new V1ProfileImageExportException(
                            "provider returned mismatched profile image evidence");
                confirmed.add(result.evidence());
                if (result.created()) created++;
            } finally { Arrays.fill(bytes, (byte) 0); }
        }
        var input = ProviderVerifiedV1ProfileImageImportInput.confirm(
                export.importPlan(), confirmed);
        return new V1ProfileImageObjectUploadReport(
                input, unique.size(), created, unique.size() - created);
    }

    private static byte[] readExact(Path root, ProfileImageObjectEvidence evidence) {
        Path object = root.resolve("objects").resolve(evidence.objectKey()).normalize();
        if (!object.startsWith(root.resolve("objects")))
            throw new V1ProfileImageExportException("profile image object path escaped export");
        try {
            BasicFileAttributes attributes = Files.readAttributes(object,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.size() != evidence.byteSize())
                throw new V1ProfileImageExportException(
                        "profile image object changed before provider upload");
            byte[] bytes;
            try (var input = Files.newInputStream(object)) {
                bytes = input.readNBytes(LegacyV1AvatarUpload.MAX_BYTES + 1);
                if (bytes.length != evidence.byteSize() || input.read() >= 0) {
                    Arrays.fill(bytes, (byte) 0);
                    throw new V1ProfileImageExportException(
                            "profile image object changed during provider upload");
                }
            }
            if (!MessageDigest.isEqual(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes), evidence.contentSha256())) {
                Arrays.fill(bytes, (byte) 0);
                throw new V1ProfileImageExportException(
                        "profile image object hash changed before provider upload");
            }
            return bytes;
        } catch (V1ProfileImageExportException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new V1ProfileImageExportException(
                    "profile image object cannot be prepared for provider upload", exception);
        }
    }

    private static boolean exact(ProfileImageObjectEvidence left,
            ProfileImageObjectEvidence right) {
        return left.objectKey().equals(right.objectKey())
                && left.byteSize() == right.byteSize()
                && left.mediaType().equals(right.mediaType())
                && MessageDigest.isEqual(left.contentSha256(), right.contentSha256());
    }
}

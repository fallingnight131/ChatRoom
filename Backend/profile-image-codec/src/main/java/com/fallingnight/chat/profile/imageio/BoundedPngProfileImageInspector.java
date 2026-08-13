package com.fallingnight.chat.profile.imageio;

import com.fallingnight.chat.application.profile.*;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/** In-memory PNG-only decoder that bounds pixels and strips metadata by re-encoding. */
public final class BoundedPngProfileImageInspector implements ProfileImageInspectionPort {
    private static final long MAX_PIXELS =
            (long) CanonicalProfileImage.MAX_DIMENSION * CanonicalProfileImage.MAX_DIMENSION;

    @Override public Optional<CanonicalProfileImage> inspect(LegacyV1AvatarUpload upload) {
        if (upload == null) return Optional.empty();
        try { return upload.withCopy(this::inspectBytes); }
        catch (RuntimeException exception) { return Optional.empty(); }
    }

    private Optional<CanonicalProfileImage> inspectBytes(byte[] source) {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(source))) {
            if (input == null) return Optional.empty();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return Optional.empty();
            ImageReader reader = readers.next();
            try {
                if (!"png".equalsIgnoreCase(reader.getFormatName())) return Optional.empty();
                reader.setInput(input, false, true);
                if (reader.getNumImages(true) != 1) return Optional.empty();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!validDimensions(width, height)) return Optional.empty();
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height)
                    return Optional.empty();
                BufferedImage canonical = new BufferedImage(width, height,
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = canonical.createGraphics();
                try {
                    graphics.setComposite(java.awt.AlphaComposite.Src);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.drawImage(decoded, 0, 0, null);
                } finally { graphics.dispose(); }
                byte[] encoded = encodePng(canonical);
                if (encoded.length == 0 || encoded.length > LegacyV1AvatarUpload.MAX_BYTES)
                    return Optional.empty();
                return Optional.of(new CanonicalProfileImage(
                        encoded, width, height, sha256(encoded)));
            } finally { reader.dispose(); }
        } catch (Exception exception) { return Optional.empty(); }
    }

    private static byte[] encodePng(BufferedImage image) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IllegalStateException("PNG writer unavailable");
        ImageWriter writer = writers.next(); ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            writer.write(null, new javax.imageio.IIOImage(image, null, null),
                    writer.getDefaultWriteParam());
            stream.flush();
        } finally { writer.dispose(); }
        return output.toByteArray();
    }

    private static boolean validDimensions(int width, int height) {
        return width > 0 && width <= CanonicalProfileImage.MAX_DIMENSION
                && height > 0 && height <= CanonicalProfileImage.MAX_DIMENSION
                && (long) width * height <= MAX_PIXELS;
    }
    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}

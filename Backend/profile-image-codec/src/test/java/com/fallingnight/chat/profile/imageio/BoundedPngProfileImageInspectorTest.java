package com.fallingnight.chat.profile.imageio;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.profile.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class BoundedPngProfileImageInspectorTest {
    private final BoundedPngProfileImageInspector inspector =
            new BoundedPngProfileImageInspector();

    @Test void reencodesDeterministicallyAndPreservesPixels() throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x80ff0000); source.setRGB(1, 0, 0xff00ff00);
        source.setRGB(0, 1, 0xff0000ff); source.setRGB(1, 1, 0x00000000);
        byte[] input = png(source);
        CanonicalProfileImage first = inspect(input), second = inspect(input);
        assertEquals(2, first.width()); assertEquals(2, first.height());
        assertArrayEquals(first.pngBytes(), second.pngBytes());
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(first.pngBytes()));
        assertEquals(source.getRGB(0, 0), decoded.getRGB(0, 0));
        assertEquals(source.getRGB(1, 1), decoded.getRGB(1, 1));
    }

    @Test void dropsAncillaryTextMetadataDuringCanonicalReencode() throws Exception {
        byte[] clean = png(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        byte[] marker = "secret-profile-metadata".getBytes(StandardCharsets.US_ASCII);
        byte[] withText = insertTextChunk(clean, marker);
        assertTrue(indexOf(withText, marker) >= 0);
        CanonicalProfileImage canonical = inspect(withText);
        assertEquals(-1, indexOf(canonical.pngBytes(), marker));
    }

    @Test void rejectsNonPngMalformedAndHeaderPixelBombBeforeDecode() throws Exception {
        assertEmpty("not an image".getBytes(StandardCharsets.UTF_8));
        assertEmpty(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        assertEmpty(jpeg.toByteArray());

        byte[] oversized = png(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        writeInt(oversized, 16, 4096); writeInt(oversized, 20, 4096);
        rewriteCrc(oversized, 12, 13);
        assertEmpty(oversized);
    }

    private CanonicalProfileImage inspect(byte[] bytes) {
        try (LegacyV1AvatarUpload upload = LegacyV1AvatarUpload.copyOf(bytes)) {
            return inspector.inspect(upload).orElseThrow();
        }
    }
    private void assertEmpty(byte[] bytes) {
        try (LegacyV1AvatarUpload upload = LegacyV1AvatarUpload.copyOf(bytes)) {
            assertTrue(inspector.inspect(upload).isEmpty());
        }
    }
    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output)); return output.toByteArray();
    }
    private static byte[] insertTextChunk(byte[] png, byte[] marker) {
        byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
        byte[] chunk = new byte[12 + marker.length];
        writeInt(chunk, 0, marker.length); System.arraycopy(type, 0, chunk, 4, 4);
        System.arraycopy(marker, 0, chunk, 8, marker.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(type); crc.update(marker);
        writeInt(chunk, 8 + marker.length, (int) crc.getValue());
        byte[] result = new byte[png.length + chunk.length];
        int iendOffset = png.length - 12;
        System.arraycopy(png, 0, result, 0, iendOffset);
        System.arraycopy(chunk, 0, result, iendOffset, chunk.length);
        System.arraycopy(png, iendOffset, result, iendOffset + chunk.length, 12);
        return result;
    }
    private static int indexOf(byte[] source, byte[] target) {
        outer: for (int i = 0; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) if (source[i + j] != target[j]) continue outer;
            return i;
        } return -1;
    }
    private static void rewriteCrc(byte[] bytes, int typeOffset, int dataLength) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, typeOffset, 4 + dataLength);
        writeInt(bytes, typeOffset + 4 + dataLength, (int) crc.getValue());
    }
    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24); bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8); bytes[offset + 3] = (byte) value;
    }
}

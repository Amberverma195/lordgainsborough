package com.lordgainsborough.restaurant.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class MenuImageValidatorTest {

    private MenuImageValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MenuImageValidator();
    }

    private byte[] createTestImageBytes(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        return baos.toByteArray();
    }

    private byte[] createTestWebpBytes() {
        // RIFF header (4) + length (4) + WEBP (4) + VP8_ (4) + chunk length (4) + payload (8) = 28 bytes
        byte[] webp = new byte[28];
        // "RIFF"
        webp[0] = 'R'; webp[1] = 'I'; webp[2] = 'F'; webp[3] = 'F';
        // Size: 20 (28 - 8)
        webp[4] = 20; webp[5] = 0; webp[6] = 0; webp[7] = 0;
        // "WEBP"
        webp[8] = 'W'; webp[9] = 'E'; webp[10] = 'B'; webp[11] = 'P';
        // "VP8 "
        webp[12] = 'V'; webp[13] = 'P'; webp[14] = '8'; webp[15] = ' ';
        // Chunk size: 8
        webp[16] = 8; webp[17] = 0; webp[18] = 0; webp[19] = 0;
        // Keyframe signature bytes
        webp[20] = (byte) 0x9D; webp[21] = 0x01; webp[22] = 0x2A;
        return webp;
    }

    @Test
    void testValidJpegImage() throws IOException {
        byte[] jpegBytes = createTestImageBytes("jpg", 10, 10);
        MockMultipartFile file = new MockMultipartFile("file", "dish.jpg", "image/jpeg", jpegBytes);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertTrue(result.isValid());
        assertEquals(MenuImageValidator.ImageFormat.JPEG, result.getFormat());
        assertEquals("jpg", result.getFormat().getExtension());
    }

    @Test
    void testValidJpegWithJpegExtension() throws IOException {
        byte[] jpegBytes = createTestImageBytes("jpg", 10, 10);
        MockMultipartFile file = new MockMultipartFile("file", "dish.jpeg", "image/jpeg", jpegBytes);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertTrue(result.isValid());
        assertEquals(MenuImageValidator.ImageFormat.JPEG, result.getFormat());
    }

    @Test
    void testValidPngImage() throws IOException {
        byte[] pngBytes = createTestImageBytes("png", 10, 10);
        MockMultipartFile file = new MockMultipartFile("file", "dish.png", "image/png", pngBytes);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertTrue(result.isValid());
        assertEquals(MenuImageValidator.ImageFormat.PNG, result.getFormat());
        assertEquals("png", result.getFormat().getExtension());
    }

    @Test
    void testValidGifImage() throws IOException {
        byte[] gifBytes = createTestImageBytes("gif", 10, 10);
        MockMultipartFile file = new MockMultipartFile("file", "dish.gif", "image/gif", gifBytes);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertTrue(result.isValid());
        assertEquals(MenuImageValidator.ImageFormat.GIF, result.getFormat());
        assertEquals("gif", result.getFormat().getExtension());
    }

    @Test
    void testValidWebpImage() {
        byte[] webpBytes = createTestWebpBytes();
        MockMultipartFile file = new MockMultipartFile("file", "dish.webp", "image/webp", webpBytes);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertTrue(result.isValid());
        assertEquals(MenuImageValidator.ImageFormat.WEBP, result.getFormat());
        assertEquals("webp", result.getFormat().getExtension());
    }

    @Test
    void testRejectNullOrEmptyFile() {
        MenuImageValidator.ValidationResult nullResult = validator.validate(null);
        assertFalse(nullResult.isValid());
        assertTrue(nullResult.getErrorMessage().contains("empty or missing"));

        MockMultipartFile emptyFile = new MockMultipartFile("file", "dish.png", "image/png", new byte[0]);
        MenuImageValidator.ValidationResult emptyResult = validator.validate(emptyFile);
        assertFalse(emptyResult.isValid());
        assertTrue(emptyResult.getErrorMessage().contains("empty or missing"));
    }

    @Test
    void testRejectExceedingSizeLimit() {
        // 5MB + 1 byte
        byte[] largeBytes = new byte[(int) (MenuImageValidator.MAX_FILE_SIZE + 1)];
        MockMultipartFile file = new MockMultipartFile("file", "large.png", "image/png", largeBytes);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("exceeds 5MB limit"));
    }

    @Test
    void testRejectMissingFilename() throws IOException {
        byte[] pngBytes = createTestImageBytes("png", 10, 10);
        MockMultipartFile file = new MockMultipartFile("file", "", "image/png", pngBytes);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("Filename is required"));
    }

    @Test
    void testRejectForbiddenExtension() throws IOException {
        byte[] pngBytes = createTestImageBytes("png", 10, 10);

        String[] forbidden = {"dish.exe", "dish.svg", "dish.php", "dish.sh", "dish.html", "dish.js", "dish"};
        for (String name : forbidden) {
            MockMultipartFile file = new MockMultipartFile("file", name, "application/octet-stream", pngBytes);
            MenuImageValidator.ValidationResult result = validator.validate(file);
            assertFalse(result.isValid(), "Should reject: " + name);
            assertTrue(result.getErrorMessage().contains("Only JPEG, PNG, WebP, and GIF") ||
                       result.getErrorMessage().contains("Only JPEG"));
        }
    }

    @Test
    void testRejectFakeImage() {
        // Text file named as .jpg
        byte[] fakeBytes = "<html><body><script>alert(1)</script></body></html>".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "malicious.jpg", "image/jpeg", fakeBytes);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("unrecognized image format"));
    }

    @Test
    void testRejectExtensionMismatch() throws IOException {
        // PNG bytes uploaded with .jpg extension
        byte[] pngBytes = createTestImageBytes("png", 10, 10);
        MockMultipartFile file = new MockMultipartFile("file", "sneaky.jpg", "image/jpeg", pngBytes);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("File extension does not match"));
    }

    @Test
    void testRejectCorruptedJpeg() {
        // Has JPEG start bytes FF D8 FF followed by arbitrary garbage
        byte[] corrupted = new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0x00,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x10, 0x11, 0x12
        };
        MockMultipartFile file = new MockMultipartFile("file", "corrupt.jpg", "image/jpeg", corrupted);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("Corrupted"));
    }

    @Test
    void testRejectCorruptedWebPStructure() {
        // WebP with truncated payload/chunk
        byte[] corruptWebp = new byte[18];
        corruptWebp[0] = 'R'; corruptWebp[1] = 'I'; corruptWebp[2] = 'F'; corruptWebp[3] = 'F';
        corruptWebp[4] = 100; // Claims size 100 but only 18 bytes
        corruptWebp[8] = 'W'; corruptWebp[9] = 'E'; corruptWebp[10] = 'B'; corruptWebp[11] = 'P';
        corruptWebp[12] = 'V'; corruptWebp[13] = 'P'; corruptWebp[14] = '8'; corruptWebp[15] = ' ';

        MockMultipartFile file = new MockMultipartFile("file", "corrupt.webp", "image/webp", corruptWebp);

        MenuImageValidator.ValidationResult result = validator.validate(file);

        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("invalid WebP image structure"));
    }

    @Test
    void testExtractExtensionEdgeCases() {
        assertEquals("png", MenuImageValidator.extractExtension("image.png"));
        assertEquals("jpg", MenuImageValidator.extractExtension("/path/to/file.jpg"));
        assertEquals("webp", MenuImageValidator.extractExtension("C:\\Users\\admin\\file.WEBP"));
        assertEquals("png", MenuImageValidator.extractExtension("nested.name.with.dots.PNG"));
        assertEquals("", MenuImageValidator.extractExtension("no_extension"));
        assertEquals("", MenuImageValidator.extractExtension("dot_at_end."));
        assertEquals("", MenuImageValidator.extractExtension(null));
        assertEquals("", MenuImageValidator.extractExtension(""));
    }
}

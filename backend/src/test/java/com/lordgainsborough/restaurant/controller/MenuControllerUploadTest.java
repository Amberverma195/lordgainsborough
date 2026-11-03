package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.service.MenuImageValidator;
import com.lordgainsborough.restaurant.service.PostgresCatalogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

class MenuControllerUploadTest {

    private PostgresCatalogStore catalogStore;
    private MenuImageValidator imageValidator;
    private MenuController controller;
    private Path testUploadsDir;

    @BeforeEach
    void setUp() throws IOException {
        catalogStore = Mockito.mock(PostgresCatalogStore.class);
        imageValidator = new MenuImageValidator();
        controller = new MenuController(catalogStore, imageValidator);

        testUploadsDir = Paths.get("uploads/menu").toAbsolutePath().normalize();
        Files.createDirectories(testUploadsDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up created files in uploads/menu if any
        if (Files.exists(testUploadsDir)) {
            try (var stream = Files.walk(testUploadsDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .filter(p -> !p.equals(testUploadsDir))
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    private byte[] createTestImageBytes(String format) throws IOException {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        return baos.toByteArray();
    }

    @Test
    void testUploadValidPngGeneratesSafeServerControlledFilename() throws IOException {
        byte[] pngBytes = createTestImageBytes("png");
        MockMultipartFile file = new MockMultipartFile("file", "original_user_photo.png", "image/png", pngBytes);

        ResponseEntity<String> response = controller.uploadImage(file);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().startsWith("/uploads/menu/"));
        assertTrue(response.getBody().endsWith(".png"));

        // Crucial security check: Original filename must NOT be in the generated path
        assertFalse(response.getBody().contains("original_user_photo"));

        // Verify the file exists on disk
        String serverFileName = response.getBody().replace("/uploads/menu/", "");
        Path savedPath = testUploadsDir.resolve(serverFileName);
        assertTrue(Files.exists(savedPath));
        assertTrue(Files.size(savedPath) > 0);
    }

    @Test
    void testUploadPathTraversalAttemptIsNeutralized() throws IOException {
        byte[] pngBytes = createTestImageBytes("png");
        // Attacker attempts path traversal via filename
        MockMultipartFile file = new MockMultipartFile("file", "../../../../evil.png", "image/png", pngBytes);

        ResponseEntity<String> response = controller.uploadImage(file);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // Safe server-controlled filename generated, traversal neutralized
        assertFalse(response.getBody().contains(".."));
        assertFalse(response.getBody().contains("evil"));

        String serverFileName = response.getBody().replace("/uploads/menu/", "");
        Path savedPath = testUploadsDir.resolve(serverFileName);
        assertTrue(Files.exists(savedPath));
        assertTrue(savedPath.startsWith(testUploadsDir));
    }

    @Test
    void testUploadRejectsFakeImage() {
        byte[] maliciousBytes = "<?php echo 'hacked'; ?>".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "shell.php.png", "image/png", maliciousBytes);

        ResponseEntity<String> response = controller.uploadImage(file);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("unrecognized image format"));
    }

    @Test
    void testUploadRejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        ResponseEntity<String> response = controller.uploadImage(file);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("empty or missing"));
    }

    @Test
    void testUploadRejectsDisallowedExtension() throws IOException {
        byte[] pngBytes = createTestImageBytes("png");
        MockMultipartFile file = new MockMultipartFile("file", "script.sh", "application/x-sh", pngBytes);

        ResponseEntity<String> response = controller.uploadImage(file);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Only JPEG, PNG, WebP, and GIF images are allowed."));
    }
}

package com.lordgainsborough.restaurant.service;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Component
public class MenuImageValidator {

    public static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    public static final int MAX_DIMENSION = 8192; // 8K maximum resolution

    public static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif"
    );

    public enum ImageFormat {
        JPEG("jpg"),
        PNG("png"),
        WEBP("webp"),
        GIF("gif");

        private final String extension;

        ImageFormat(String extension) {
            this.extension = extension;
        }

        public String getExtension() {
            return extension;
        }
    }

    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final ImageFormat format;

        private ValidationResult(boolean valid, String errorMessage, ImageFormat format) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.format = format;
        }

        public static ValidationResult ok(ImageFormat format) {
            return new ValidationResult(true, null, format);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message, null);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public ImageFormat getFormat() {
            return format;
        }
    }

    public ValidationResult validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            return ValidationResult.error("File is empty or missing.");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return ValidationResult.error("File size exceeds 5MB limit.");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return ValidationResult.error("Filename is required.");
        }

        String ext = extractExtension(originalFilename);
        if (ext.isEmpty() || !ALLOWED_EXTENSIONS.contains(ext)) {
            return ValidationResult.error("Only JPEG, PNG, WebP, and GIF images are allowed.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return ValidationResult.error("Unable to read uploaded file.");
        }

        if (bytes == null || bytes.length < 12) {
            return ValidationResult.error("File is too small to be a valid image.");
        }

        ImageFormat detectedFormat = detectFormatFromMagicBytes(bytes);
        if (detectedFormat == null) {
            return ValidationResult.error("Invalid image content: unrecognized image format.");
        }

        if (!isExtensionCompatible(ext, detectedFormat)) {
            return ValidationResult.error("File extension does not match the detected image format.");
        }

        // Deep validation of image integrity
        if (detectedFormat == ImageFormat.JPEG || detectedFormat == ImageFormat.PNG || detectedFormat == ImageFormat.GIF) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    return ValidationResult.error("Corrupted or unreadable image content.");
                }
                if (image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
                    return ValidationResult.error("Image dimensions exceed maximum supported resolution (8192x8192).");
                }
            } catch (Exception e) {
                return ValidationResult.error("Corrupted image content: " + e.getMessage());
            }
        } else if (detectedFormat == ImageFormat.WEBP) {
            if (!validateWebPStructure(bytes)) {
                return ValidationResult.error("Corrupted or invalid WebP image structure.");
            }
        }

        return ValidationResult.ok(detectedFormat);
    }

    public static boolean isExtensionCompatible(String extension, ImageFormat format) {
        if (extension == null || format == null) return false;
        String clean = extension.toLowerCase(Locale.ROOT);
        return switch (format) {
            case JPEG -> "jpg".equals(clean) || "jpeg".equals(clean);
            case PNG -> "png".equals(clean);
            case WEBP -> "webp".equals(clean);
            case GIF -> "gif".equals(clean);
        };
    }

    public static ImageFormat detectFormatFromMagicBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }

        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return ImageFormat.JPEG;
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50 &&
            (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47 &&
            (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A &&
            (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A) {
            return ImageFormat.PNG;
        }

        // GIF: "GIF87a" or "GIF89a"
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8' &&
            (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') {
            return ImageFormat.GIF;
        }

        // WebP: RIFF .... WEBP
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' &&
            bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return ImageFormat.WEBP;
        }

        return null;
    }

    private static boolean validateWebPStructure(byte[] bytes) {
        if (bytes.length < 20) {
            return false;
        }

        long riffSize = (bytes[4] & 0xFFL)
                | ((bytes[5] & 0xFFL) << 8)
                | ((bytes[6] & 0xFFL) << 16)
                | ((bytes[7] & 0xFFL) << 24);

        if (riffSize < 12 || riffSize + 8 > bytes.length) {
            return false;
        }

        // Check chunk header: "VP8 ", "VP8L", or "VP8X"
        String chunk = new String(bytes, 12, 4, StandardCharsets.US_ASCII);
        if (!"VP8 ".equals(chunk) && !"VP8L".equals(chunk) && !"VP8X".equals(chunk)) {
            return false;
        }

        long chunkSize = (bytes[16] & 0xFFL)
                | ((bytes[17] & 0xFFL) << 8)
                | ((bytes[18] & 0xFFL) << 16)
                | ((bytes[19] & 0xFFL) << 24);

        return chunkSize > 0 && (20 + chunkSize <= bytes.length + 1); // +1 allows optional padding byte
    }

    public static String extractExtension(String filename) {
        if (filename == null) return "";
        int lastSlash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String cleanName = lastSlash >= 0 ? filename.substring(lastSlash + 1) : filename;
        int dot = cleanName.lastIndexOf('.');
        if (dot < 0 || dot == cleanName.length() - 1) {
            return "";
        }
        return cleanName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

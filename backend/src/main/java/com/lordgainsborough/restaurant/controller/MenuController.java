package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.MenuItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/menu")
public class MenuController {

    @Autowired
    private com.lordgainsborough.restaurant.service.PostgresCatalogStore catalog;

    @Autowired(required = false)
    private com.lordgainsborough.restaurant.service.MenuImageValidator imageValidator = new com.lordgainsborough.restaurant.service.MenuImageValidator();

    public MenuController() {
    }

    public MenuController(com.lordgainsborough.restaurant.service.PostgresCatalogStore catalog,
                          com.lordgainsborough.restaurant.service.MenuImageValidator imageValidator) {
        this.catalog = catalog;
        this.imageValidator = imageValidator;
    }

    @GetMapping
    public List<MenuItem> getAllMenuItems() {
        return catalog.menu();
    }

    @GetMapping("/category/{category}")
    public List<MenuItem> getMenuItemsByCategory(@PathVariable String category) {
        return catalog.menuByCategory(category);
    }

    @GetMapping("/most-loved")
    public List<MenuItem> getMostLovedMenuItems() {
        return catalog.menu().stream()
                .filter(MenuItem::isMostLoved)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<MenuItem> getMenuItemById(@PathVariable String id) {
        return catalog.menuItem(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public MenuItem createMenuItem(@RequestBody MenuItem menuItem) {
        if (menuItem.isAvailable() == false && menuItem.getName() != null) {
            // default to available if not explicitly set
        }
        return catalog.saveMenuItem(menuItem);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MenuItem> updateMenuItem(@PathVariable String id, @RequestBody MenuItem menuItem) {
        return catalog.menuItem(id)
            .map(existing -> {
                existing.setName(menuItem.getName());
                existing.setDescription(menuItem.getDescription());
                existing.setPrice(menuItem.getPrice());
                existing.setCategory(menuItem.getCategory());
                existing.setImage(menuItem.getImage());
                existing.setAvailable(menuItem.isAvailable());
                return ResponseEntity.ok(catalog.saveMenuItem(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/availability")
    public ResponseEntity<MenuItem> toggleAvailability(@PathVariable String id) {
        return catalog.menuItem(id)
            .map(existing -> {
                existing.setAvailable(!existing.isAvailable());
                return ResponseEntity.ok(catalog.saveMenuItem(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/most-loved")
    public ResponseEntity<MenuItem> toggleMostLoved(@PathVariable String id) {
        return catalog.menuItem(id)
            .map(existing -> {
                existing.setMostLoved(!existing.isMostLoved());
                return ResponseEntity.ok(catalog.saveMenuItem(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMenuItem(@PathVariable String id) {
        if (catalog.menuItemExists(id)) {
            catalog.deleteMenuItem(id);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/upload-image")
    public ResponseEntity<String> uploadImage(@RequestParam("file") MultipartFile file) {
        if (imageValidator == null) {
            imageValidator = new com.lordgainsborough.restaurant.service.MenuImageValidator();
        }

        com.lordgainsborough.restaurant.service.MenuImageValidator.ValidationResult validation = imageValidator.validate(file);
        if (!validation.isValid()) {
            return ResponseEntity.badRequest().body(validation.getErrorMessage());
        }

        try {
            String safeFileName = UUID.randomUUID().toString() + "." + validation.getFormat().getExtension();
            Path uploadDir = Paths.get("uploads/menu").toAbsolutePath().normalize();
            Files.createDirectories(uploadDir);

            Path filePath = uploadDir.resolve(safeFileName).normalize();
            if (!filePath.startsWith(uploadDir)) {
                return ResponseEntity.badRequest().body("Invalid upload path.");
            }

            Files.write(filePath, file.getBytes());
            return ResponseEntity.ok("/uploads/menu/" + safeFileName);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Failed to upload image");
        }
    }
}

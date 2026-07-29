package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.repository.MenuItemRepository;
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
    private MenuItemRepository menuItemRepository;

    @GetMapping
    public List<MenuItem> getAllMenuItems() {
        return menuItemRepository.findAll();
    }

    @GetMapping("/category/{category}")
    public List<MenuItem> getMenuItemsByCategory(@PathVariable String category) {
        return menuItemRepository.findByCategory(category);
    }

    @GetMapping("/most-loved")
    public List<MenuItem> getMostLovedMenuItems() {
        return menuItemRepository.findAll().stream()
                .filter(MenuItem::isMostLoved)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<MenuItem> getMenuItemById(@PathVariable String id) {
        return menuItemRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public MenuItem createMenuItem(@RequestBody MenuItem menuItem) {
        if (menuItem.isAvailable() == false && menuItem.getName() != null) {
            // default to available if not explicitly set
        }
        return menuItemRepository.save(menuItem);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MenuItem> updateMenuItem(@PathVariable String id, @RequestBody MenuItem menuItem) {
        return menuItemRepository.findById(id)
            .map(existing -> {
                existing.setName(menuItem.getName());
                existing.setDescription(menuItem.getDescription());
                existing.setPrice(menuItem.getPrice());
                existing.setCategory(menuItem.getCategory());
                existing.setImage(menuItem.getImage());
                existing.setAvailable(menuItem.isAvailable());
                return ResponseEntity.ok(menuItemRepository.save(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/availability")
    public ResponseEntity<MenuItem> toggleAvailability(@PathVariable String id) {
        return menuItemRepository.findById(id)
            .map(existing -> {
                existing.setAvailable(!existing.isAvailable());
                return ResponseEntity.ok(menuItemRepository.save(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}/most-loved")
    public ResponseEntity<MenuItem> toggleMostLoved(@PathVariable String id) {
        return menuItemRepository.findById(id)
            .map(existing -> {
                existing.setMostLoved(!existing.isMostLoved());
                return ResponseEntity.ok(menuItemRepository.save(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMenuItem(@PathVariable String id) {
        if (menuItemRepository.existsById(id)) {
            menuItemRepository.deleteById(id);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/upload-image")
    public ResponseEntity<String> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            String fileName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            Path uploadDir = Paths.get("uploads/menu");
            Files.createDirectories(uploadDir);
            Path filePath = uploadDir.resolve(fileName);
            Files.write(filePath, file.getBytes());
            return ResponseEntity.ok("/uploads/menu/" + fileName);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("Failed to upload image");
        }
    }
}

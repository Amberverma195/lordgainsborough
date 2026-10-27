package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.Category;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    @Autowired
    private com.lordgainsborough.restaurant.service.PostgresCatalogStore catalog;

    @GetMapping
    public List<Category> getAllCategories() {
        return catalog.categories();
    }

    @PostMapping
    public Category createCategory(@RequestBody Category category) {
        return catalog.saveCategory(category);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Category> updateCategory(@PathVariable String id, @RequestBody Category category) {
        return catalog.category(id)
            .map(existing -> {
                existing.setName(category.getName());
                existing.setDescription(category.getDescription());
                existing.setDisplayOrder(category.getDisplayOrder());
                return ResponseEntity.ok(catalog.saveCategory(existing));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable String id) {
        if (catalog.categoryExists(id)) {
            catalog.deleteCategory(id);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
}

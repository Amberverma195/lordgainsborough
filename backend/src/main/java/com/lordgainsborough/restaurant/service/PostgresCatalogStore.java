package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.*;
import com.lordgainsborough.restaurant.persistence.jpa.entity.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** Maps the catalog and settings HTTP snapshots to the single PostgreSQL dataset. */
@Service
@Transactional
public class PostgresCatalogStore {
    private final JpaMenuItemRepository menu;
    private final JpaCategoryRepository categories;
    private final JpaStoreSettingsRepository settings;
    public PostgresCatalogStore(JpaMenuItemRepository menu, JpaCategoryRepository categories,
            JpaStoreSettingsRepository settings) {
        this.menu = menu; this.categories = categories; this.settings = settings;
    }
    @Transactional(readOnly=true) public List<MenuItem> menu() { return menu.findAll().stream().map(this::menuSnapshot).toList(); }
    @Transactional(readOnly=true) public List<MenuItem> menuByCategory(String name) { return menu.findByCategory(name).stream().map(this::menuSnapshot).toList(); }
    @Transactional(readOnly=true) public Optional<MenuItem> menuItem(String id) { return menu.findById(id).map(this::menuSnapshot); }
    @Transactional(readOnly=true) public boolean menuItemExists(String id) { return menu.existsById(id); }
    public void deleteMenuItem(String id) { menu.deleteById(id); }
    public MenuItem saveMenuItem(MenuItem value) {
        JpaMenuItem entity = new JpaMenuItem(); BeanUtils.copyProperties(value, entity);
        entity.setPrice(BigDecimal.valueOf(value.getPrice()));
        if (entity.getId()==null || entity.getId().isBlank()) entity.setId(UUID.randomUUID().toString());
        return menuSnapshot(menu.saveAndFlush(entity));
    }
    private MenuItem menuSnapshot(JpaMenuItem entity) {
        MenuItem value = new MenuItem(); BeanUtils.copyProperties(entity, value);
        value.setPrice(entity.getPrice().doubleValue()); return value;
    }
    @Transactional(readOnly=true) public List<Category> categories() { return categories.findAll().stream().map(this::categorySnapshot).toList(); }
    @Transactional(readOnly=true) public Optional<Category> category(String id) { return categories.findById(id).map(this::categorySnapshot); }
    @Transactional(readOnly=true) public boolean categoryExists(String id) { return categories.existsById(id); }
    public void deleteCategory(String id) { categories.deleteById(id); }
    public Category saveCategory(Category value) {
        JpaCategory entity = new JpaCategory(); BeanUtils.copyProperties(value, entity);
        if (entity.getId()==null || entity.getId().isBlank()) entity.setId(UUID.randomUUID().toString());
        return categorySnapshot(categories.saveAndFlush(entity));
    }
    private Category categorySnapshot(JpaCategory entity) { Category value = new Category(); BeanUtils.copyProperties(entity, value); return value; }
    @Transactional(readOnly=true) public Optional<StoreSettings> settings() { return settings.findById(StoreSettings.DEFAULT_ID).map(this::settingsSnapshot); }
    public StoreSettings saveSettings(StoreSettings value) {
        JpaStoreSettings entity = new JpaStoreSettings(); BeanUtils.copyProperties(value, entity);
        entity.setId(StoreSettings.DEFAULT_ID); entity.setTaxRate(BigDecimal.valueOf(value.getTaxRate()));
        return settingsSnapshot(settings.saveAndFlush(entity));
    }
    private StoreSettings settingsSnapshot(JpaStoreSettings entity) {
        StoreSettings value = new StoreSettings(); BeanUtils.copyProperties(entity, value);
        value.setTaxRate(entity.getTaxRate().doubleValue()); return value;
    }
}

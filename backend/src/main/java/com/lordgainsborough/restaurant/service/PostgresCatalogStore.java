package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.config.CatalogSeedSpecifications;
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

    private static final Map<String, Integer> CANONICAL_SEED_INDEX = buildCanonicalSeedIndex();
    private static final Map<String, Integer> CANONICAL_CATEGORY_ORDER = buildCanonicalCategoryOrder();

    private static Map<String, Integer> buildCanonicalCategoryOrder() {
        Map<String, Integer> map = new HashMap<>();
        for (CatalogSeedSpecifications.CategorySpec cat : CatalogSeedSpecifications.categories()) {
            map.put(cat.name().trim().toLowerCase(Locale.ROOT), cat.displayOrder());
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Integer> buildCanonicalSeedIndex() {
        Map<String, Integer> map = new HashMap<>();
        List<CatalogSeedSpecifications.MenuSpec> catalog = CatalogSeedSpecifications.menuCatalog();
        for (int i = 0; i < catalog.size(); i++) {
            CatalogSeedSpecifications.MenuSpec spec = catalog.get(i);
            map.putIfAbsent(spec.name().trim().toLowerCase(Locale.ROOT), i);
            if (spec.aliases() != null) {
                for (String alias : spec.aliases()) {
                    if (alias != null && !alias.isBlank()) {
                        map.putIfAbsent(alias.trim().toLowerCase(Locale.ROOT), i);
                    }
                }
            }
        }
        return Collections.unmodifiableMap(map);
    }

    public PostgresCatalogStore(JpaMenuItemRepository menu, JpaCategoryRepository categories,
            JpaStoreSettingsRepository settings) {
        this.menu = menu; this.categories = categories; this.settings = settings;
    }

    private Map<String, Integer> getCategoryOrderMap() {
        Map<String, Integer> map = new HashMap<>(CANONICAL_CATEGORY_ORDER);
        try {
            List<JpaCategory> catEntities = categories.findAllByOrderByDisplayOrderAsc();
            for (JpaCategory cat : catEntities) {
                if (cat.getName() != null) {
                    map.put(cat.getName().trim().toLowerCase(Locale.ROOT), cat.getDisplayOrder());
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private Comparator<JpaMenuItem> menuItemComparator(Map<String, Integer> categoryOrderMap) {
        return (a, b) -> {
            // 1. Category display order
            int catOrderA = categoryOrderMap.getOrDefault(
                a.getCategory() == null ? "" : a.getCategory().trim().toLowerCase(Locale.ROOT), 9999);
            int catOrderB = categoryOrderMap.getOrDefault(
                b.getCategory() == null ? "" : b.getCategory().trim().toLowerCase(Locale.ROOT), 9999);
            if (catOrderA != catOrderB) {
                return Integer.compare(catOrderA, catOrderB);
            }

            // 2. Canonical seed index within category
            int seedA = CANONICAL_SEED_INDEX.getOrDefault(
                a.getName() == null ? "" : a.getName().trim().toLowerCase(Locale.ROOT), 9999);
            int seedB = CANONICAL_SEED_INDEX.getOrDefault(
                b.getName() == null ? "" : b.getName().trim().toLowerCase(Locale.ROOT), 9999);
            if (seedA != seedB) {
                return Integer.compare(seedA, seedB);
            }

            // 3. Name (case-insensitive)
            String nameA = a.getName() == null ? "" : a.getName().trim();
            String nameB = b.getName() == null ? "" : b.getName().trim();
            int nameCompare = nameA.compareToIgnoreCase(nameB);
            if (nameCompare != 0) {
                return nameCompare;
            }

            // 4. Deterministic tie-breaker ID
            String idA = a.getId() == null ? "" : a.getId();
            String idB = b.getId() == null ? "" : b.getId();
            return idA.compareTo(idB);
        };
    }

    @Transactional(readOnly=true)
    public List<MenuItem> menu() {
        Map<String, Integer> categoryOrder = getCategoryOrderMap();
        return menu.findAll().stream()
                .sorted(menuItemComparator(categoryOrder))
                .map(this::menuSnapshot)
                .toList();
    }

    @Transactional(readOnly=true)
    public List<MenuItem> menuByCategory(String name) {
        Map<String, Integer> categoryOrder = getCategoryOrderMap();
        return menu.findByCategory(name).stream()
                .sorted(menuItemComparator(categoryOrder))
                .map(this::menuSnapshot)
                .toList();
    }

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
    @Transactional(readOnly=true)
    public List<Category> categories() {
        return categories.findAllByOrderByDisplayOrderAsc().stream().map(this::categorySnapshot).toList();
    }
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

package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.model.Category;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.repository.CatalogMigrationRepository;
import com.lordgainsborough.restaurant.repository.CategoryRepository;
import com.lordgainsborough.restaurant.repository.MenuItemRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataInitializerTest {

    private final DataInitializer initializer = new DataInitializer();

    @Test
    void completedMigrationDoesNotOverwriteLaterAdminChanges() throws Exception {
        MenuItemRepository menuRepository = mock(MenuItemRepository.class);
        CategoryRepository categoryRepository = mock(CategoryRepository.class);
        CatalogMigrationRepository migrationRepository = mock(CatalogMigrationRepository.class);
        when(migrationRepository.existsById(DataInitializer.NEW_MENU_MIGRATION)).thenReturn(true);

        initializer.initDatabase(menuRepository, categoryRepository, migrationRepository).run();

        verify(menuRepository, never()).save(argThat(item -> true));
        verify(categoryRepository, never()).save(argThat(category -> true));
    }

    @Test
    void menuSyncUpdatesAliasesWithoutReplacingOperationalFields() {
        MenuItemRepository repository = mock(MenuItemRepository.class);
        MenuItem existing = new MenuItem(
            "existing-id",
            "Garlic Bread and Cheese",
            "old description",
            9.99,
            "Appetizers",
            "https://images.example/garlic.jpg",
            false
        );
        existing.setMostLoved(true);

        when(repository.findFirstByNameIgnoreCase("Garlic Bread and Cheese"))
            .thenReturn(Optional.of(existing));

        initializer.syncMenu(repository);

        assertEquals("existing-id", existing.getId());
        assertEquals("Cheesy Garlic Bread", existing.getName());
        assertEquals(13.99, existing.getPrice());
        assertEquals("https://images.example/garlic.jpg", existing.getImage());
        assertFalse(existing.isAvailable());
        assertTrue(existing.isMostLoved());
        verify(repository).save(existing);
        verify(repository, never()).deleteAll();
    }

    @Test
    void menuSyncAddsNewDocumentItemsAndRetainsLegacyItems() {
        MenuItemRepository repository = mock(MenuItemRepository.class);

        initializer.syncMenu(repository);

        verify(repository, atLeastOnce()).save(argThat(item ->
            item.getId() == null
                && item.getName().equals("Greek Melt")
                && item.getCategory().equals("Gainsborough Melts")
                && item.getPrice().equals(20.99)
        ));
        verify(repository, atLeastOnce()).save(argThat(item ->
            item.getName().equals("Chicken Finger Dinner (Kids)")
                && item.getCategory().equals("Kids Menu")
        ));
        verify(repository, never()).deleteAll();
    }

    @Test
    void categorySyncAddsNewCategoriesWithoutDeletingExistingOnes() {
        CategoryRepository repository = mock(CategoryRepository.class);
        Category appetizers = new Category("category-id", "Appetizers", "old", 99);
        when(repository.findByName("Appetizers")).thenReturn(appetizers);

        initializer.syncCategories(repository);

        assertEquals("category-id", appetizers.getId());
        assertEquals(1, appetizers.getDisplayOrder());
        verify(repository).save(argThat(category ->
            category.getName().equals("A Taste of Greece")
                && category.getDisplayOrder() == 14
        ));
        verify(repository, never()).deleteAll();
    }
}

package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCategory;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaMenuItem;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCategoryRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaMenuItemRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaStoreSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresCatalogStoreSortingTest {

    @Mock
    private JpaMenuItemRepository menuRepo;

    @Mock
    private JpaCategoryRepository categoryRepo;

    @Mock
    private JpaStoreSettingsRepository settingsRepo;

    @Test
    @DisplayName("menu() returns items in deterministic order regardless of availability status")
    void menuReturnsDeterministicOrderRegardlessOfAvailability() {
        PostgresCatalogStore store = new PostgresCatalogStore(menuRepo, categoryRepo, settingsRepo);

        JpaCategory appCat = new JpaCategory();
        appCat.setId("cat-1");
        appCat.setName("Appetizers");
        appCat.setDisplayOrder(1);

        JpaCategory pizzaCat = new JpaCategory();
        pizzaCat.setId("cat-2");
        pizzaCat.setName("Pizzas");
        pizzaCat.setDisplayOrder(2);

        when(categoryRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(appCat, pizzaCat));

        // Create items in arbitrary/scrambled order (as PostgreSQL might return after updates)
        JpaMenuItem pizza = new JpaMenuItem("id-pizza", "Cheese Pizza (10\")", "Mozzarella and cheddar",
                BigDecimal.valueOf(17.00), "Pizzas", "/img/pizza.jpg", true, false);
        JpaMenuItem stick = new JpaMenuItem("id-stick", "Mozzarella Sticks", "6 pcs",
                BigDecimal.valueOf(14.99), "Appetizers", "/img/sticks.jpg", true, false);
        JpaMenuItem bread = new JpaMenuItem("id-bread", "Cheesy Garlic Bread", "Garlic butter",
                BigDecimal.valueOf(13.99), "Appetizers", "/img/bread.jpg", true, false);

        when(menuRepo.findAll()).thenReturn(List.of(pizza, stick, bread));

        List<MenuItem> resultBefore = store.menu();
        assertThat(resultBefore).extracting(MenuItem::getId)
                .containsExactly("id-bread", "id-stick", "id-pizza");

        // Now toggle availability on the middle item (Mozzarella Sticks) to false
        stick.setAvailable(false);
        // And even if PostgreSQL returned stick at the very end of the heap:
        when(menuRepo.findAll()).thenReturn(List.of(bread, pizza, stick));

        List<MenuItem> resultAfter = store.menu();
        assertThat(resultAfter).extracting(MenuItem::getId)
                .containsExactly("id-bread", "id-stick", "id-pizza");
        assertThat(resultAfter.get(1).isAvailable()).isFalse();
    }
}

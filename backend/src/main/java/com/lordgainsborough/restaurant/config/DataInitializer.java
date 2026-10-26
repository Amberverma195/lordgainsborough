package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.config.CatalogSeedSpecifications.CategorySpec;
import com.lordgainsborough.restaurant.config.CatalogSeedSpecifications.MenuSpec;
import com.lordgainsborough.restaurant.model.Category;
import com.lordgainsborough.restaurant.model.CatalogMigration;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.repository.CatalogMigrationRepository;
import com.lordgainsborough.restaurant.repository.CategoryRepository;
import com.lordgainsborough.restaurant.repository.MenuItemRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

@Configuration
public class DataInitializer {

    static final String NEW_MENU_MIGRATION = CatalogSeedSpecifications.MIGRATION_ID;

    @Bean
    CommandLineRunner initDatabase(
        MenuItemRepository menuRepo,
        CategoryRepository categoryRepo,
        CatalogMigrationRepository migrationRepo
    ) {
        return args -> {
            if (migrationRepo.existsById(NEW_MENU_MIGRATION)) {
                return;
            }

            syncCategories(categoryRepo);
            syncMenu(menuRepo);
            migrationRepo.save(new CatalogMigration(NEW_MENU_MIGRATION, Instant.now()));
            System.out.println("MongoDB menu catalog synchronized without deleting existing records.");
        };
    }

    void syncCategories(CategoryRepository repository) {
        for (CategorySpec spec : CatalogSeedSpecifications.categories()) {
            Category existing = repository.findByName(spec.name());
            if (existing == null) {
                repository.save(new Category(null, spec.name(), spec.description(), spec.displayOrder()));
            } else {
                existing.setDescription(spec.description());
                existing.setDisplayOrder(spec.displayOrder());
                repository.save(existing);
            }
        }
    }

    void syncMenu(MenuItemRepository repository) {
        for (MenuSpec spec : CatalogSeedSpecifications.menuCatalog()) {
            Optional<MenuItem> match = repository.findFirstByNameIgnoreCase(spec.name());
            if (match.isEmpty()) {
                match = Arrays.stream(spec.aliases())
                    .map(repository::findFirstByNameIgnoreCase)
                    .flatMap(Optional::stream)
                    .findFirst();
            }

            if (match.isPresent()) {
                MenuItem item = match.get();
                item.setName(spec.name());
                item.setDescription(spec.description());
                item.setPrice(spec.price());
                item.setCategory(spec.category());
                repository.save(item);
            } else {
                repository.save(new MenuItem(null, spec.name(), spec.description(), spec.price(), spec.category(), "", true));
            }
        }
    }
}

package com.lordgainsborough.restaurant.config;

import com.lordgainsborough.restaurant.model.Category;
import com.lordgainsborough.restaurant.model.MenuItem;
import com.lordgainsborough.restaurant.repository.CategoryRepository;
import com.lordgainsborough.restaurant.repository.MenuItemRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initDatabase(MenuItemRepository menuRepo, CategoryRepository categoryRepo) {
        return args -> {
            // Clear existing data and re-seed
            categoryRepo.deleteAll();
            menuRepo.deleteAll();

            // Seed categories
            categoryRepo.saveAll(Arrays.asList(
                new Category(null, "Appetizers", "", 1),
                new Category(null, "Pizzas", "", 2),
                new Category(null, "Salads", "", 3),
                new Category(null, "Sandwiches", "", 4),
                new Category(null, "Homemade Burgers", "", 5),
                new Category(null, "Classics", "", 6),
                new Category(null, "Pasta", "", 7),
                new Category(null, "Panzerotti", "", 8),
                new Category(null, "Kids Menu", "", 9),
                new Category(null, "Beverages", "", 10),
                new Category(null, "Hot Sandwiches", "", 11)
            ));
            System.out.println("MongoDB seeded with categories.");

            // Seed menu items
            menuRepo.saveAll(Arrays.asList(
                // --- Appetizers ---
                new MenuItem(null, "Chicken Wings", "", 15.99, "Appetizers", "", true),
                new MenuItem(null, "Nacho Deluxe (Meal Size)", "", 34.99, "Appetizers", "", true),
                new MenuItem(null, "Greek Fries", "", 14.99, "Appetizers", "", true),
                new MenuItem(null, "Fresh-Cut Fries (Small)", "", 8.99, "Appetizers", "", true),
                new MenuItem(null, "Poutine", "", 12.99, "Appetizers", "", true),
                new MenuItem(null, "Garlic Bread and Cheese", "", 13.99, "Appetizers", "", true),
                new MenuItem(null, "Onion Rings", "", 8.99, "Appetizers", "", true),
                new MenuItem(null, "Battered Mushroom Caps", "", 10.99, "Appetizers", "", true),
                new MenuItem(null, "Deep Fried Pickle Spears", "", 10.99, "Appetizers", "", true),
                new MenuItem(null, "Jalapeno Poppers", "", 11.99, "Appetizers", "", true),
                new MenuItem(null, "Chicken Fingers (4 pcs)", "", 12.99, "Appetizers", "", true),
                new MenuItem(null, "Mozzarella Sticks (6 pcs)", "", 13.99, "Appetizers", "", true),
                new MenuItem(null, "Homemade Potato Skins (4 pcs)", "", 14.99, "Appetizers", "", true),
                new MenuItem(null, "Bruschetta", "", 13.99, "Appetizers", "", true),
                new MenuItem(null, "Sweet Potato Fries", "", 8.99, "Appetizers", "", true),
                new MenuItem(null, "Butter Chicken Poutine", "", 13.99, "Appetizers", "", true),
                new MenuItem(null, "Cheddar Cheese Cubes", "", 11.99, "Appetizers", "", true),

                // --- Pizzas ---
                new MenuItem(null, "Canadian Pizza (Small 10\")", "", 21.99, "Pizzas", "", true),
                new MenuItem(null, "Meat Lovers Pizza (Small 10\")", "", 21.99, "Pizzas", "", true),
                new MenuItem(null, "Deluxe Pizza (Small 10\")", "", 21.99, "Pizzas", "", true),
                new MenuItem(null, "Build Your Own Pizza (Small 10\")", "", 15.99, "Pizzas", "", true),
                new MenuItem(null, "Pepperoni Pizza (Small 10\")", "", 17.99, "Pizzas", "", true),
                new MenuItem(null, "Hawaiian Pizza (Small 10\")", "", 21.99, "Pizzas", "", true),
                new MenuItem(null, "Cheese Pizza (Small 10\")", "", 14.99, "Pizzas", "", true),
                new MenuItem(null, "BBQ Chicken Pizza (Small 10\")", "", 21.99, "Pizzas", "", true),
                new MenuItem(null, "Vegetarian Pizza (Small 10\")", "", 21.99, "Pizzas", "", true),
                new MenuItem(null, "Greek Pizza (Small 10\")", "", 21.99, "Pizzas", "", true),
                new MenuItem(null, "Butter Chicken Pizza (Small 10\")", "", 21.99, "Pizzas", "", true),

                // --- Salads ---
                new MenuItem(null, "Greek Salad (Small)", "", 11.99, "Salads", "", true),
                new MenuItem(null, "Caesar Salad (Small)", "", 10.99, "Salads", "", true),
                new MenuItem(null, "Horiatiki (Village) Greek Salad (Small)", "", 11.99, "Salads", "", true),
                new MenuItem(null, "House Salad (Small)", "", 9.99, "Salads", "", true),
                new MenuItem(null, "Julienne Salad", "", 16.99, "Salads", "", true),
                new MenuItem(null, "Buffalo Chicken Salad", "", 16.99, "Salads", "", true),
                new MenuItem(null, "Meatless Julienne Salad", "", 14.99, "Salads", "", true),

                // --- Sandwiches ---
                new MenuItem(null, "Triple Decker Club House Sandwich", "", 17.99, "Sandwiches", "", true),
                new MenuItem(null, "Beef Dip Au Jus Sandwich", "", 16.99, "Sandwiches", "", true),
                new MenuItem(null, "Turkey Sandwich", "", 9.99, "Sandwiches", "", true),
                new MenuItem(null, "Grilled Cheese with Bacon Sandwich", "", 9.99, "Sandwiches", "", true),
                new MenuItem(null, "Reuben on Rye Sandwich", "", 11.99, "Sandwiches", "", true),
                new MenuItem(null, "Corned Beef on Rye Sandwich", "", 9.99, "Sandwiches", "", true),
                new MenuItem(null, "BLT Sandwich", "", 9.99, "Sandwiches", "", true),
                new MenuItem(null, "Roast Beef Sandwich", "", 9.99, "Sandwiches", "", true),
                new MenuItem(null, "Western Sandwich", "", 9.99, "Sandwiches", "", true),

                // --- Homemade Burgers ---
                new MenuItem(null, "Banquet Burger", "", 12.99, "Homemade Burgers", "", true),
                new MenuItem(null, "Cheeseburger", "", 10.99, "Homemade Burgers", "", true),
                new MenuItem(null, "Bacon Cheeseburger", "", 11.99, "Homemade Burgers", "", true),
                new MenuItem(null, "Hamburger", "", 9.99, "Homemade Burgers", "", true),
                new MenuItem(null, "Greek Burger", "", 12.99, "Homemade Burgers", "", true),

                // --- Classics ---
                new MenuItem(null, "Chicken Souvlaki", "", 10.99, "Classics", "", true),
                new MenuItem(null, "Homemade Fish and Chips", "", 17.99, "Classics", "", true),
                new MenuItem(null, "Gyros", "", 10.99, "Classics", "", true),
                new MenuItem(null, "Chicken Finger Dinner", "", 16.99, "Classics", "", true),
                new MenuItem(null, "Two Marinated Chicken Skewers with Greek Salad, Fries and Tzatziki", "", 20.99, "Classics", "", true),
                new MenuItem(null, "Fish Burger", "", 10.99, "Classics", "", true),
                new MenuItem(null, "Patty Melt", "", 16.99, "Classics", "", true),
                new MenuItem(null, "Chicken Burger", "", 10.99, "Classics", "", true),
                new MenuItem(null, "Liver and Onion", "", 18.99, "Classics", "", true),

                // --- Pasta ---
                new MenuItem(null, "Chicken Parmesan with Spaghetti", "", 20.99, "Pasta", "", true),
                new MenuItem(null, "Spaghetti with Homemade Meat Sauce", "", 17.99, "Pasta", "", true),
                new MenuItem(null, "Veal Parmesan with Spaghetti", "", 20.99, "Pasta", "", true),

                // --- Panzerotti ---
                new MenuItem(null, "Build Your Own Panzerotti", "", 16.99, "Panzerotti", "", true),
                new MenuItem(null, "Deluxe Panzerotti", "", 22.99, "Panzerotti", "", true),
                new MenuItem(null, "Canadian Panzerotti", "", 22.99, "Panzerotti", "", true),
                new MenuItem(null, "Meat Lover Panzerotti", "", 24.99, "Panzerotti", "", true),

                // --- Kids Menu ---
                new MenuItem(null, "Chicken Finger Dinner (Kids)", "", 9.99, "Kids Menu", "", true),
                new MenuItem(null, "Spaghetti with Homemade Meat Sauce (Kids)", "", 10.99, "Kids Menu", "", true),
                new MenuItem(null, "Hamburger (Kids)", "", 9.99, "Kids Menu", "", true),
                new MenuItem(null, "Fish (1 pc) and Chips (Kids)", "", 10.99, "Kids Menu", "", true),
                new MenuItem(null, "Grilled Cheese (Kids)", "", 9.99, "Kids Menu", "", true),
                new MenuItem(null, "Garlic Bread and Cheese (Kids)", "", 8.99, "Kids Menu", "", true),

                // --- Beverages ---
                new MenuItem(null, "Chocolate Milk", "", 2.99, "Beverages", "", true),
                new MenuItem(null, "Lemonade", "", 2.99, "Beverages", "", true),
                new MenuItem(null, "Canned Drink", "", 2.99, "Beverages", "", true),
                new MenuItem(null, "Coffee", "", 2.99, "Beverages", "", true),
                new MenuItem(null, "Juice", "", 2.99, "Beverages", "", true),
                new MenuItem(null, "Milk", "", 2.99, "Beverages", "", true),
                new MenuItem(null, "Tea", "", 2.99, "Beverages", "", true),

                // --- Hot Sandwiches ---
                new MenuItem(null, "Hot Turkey Sandwich", "", 18.99, "Hot Sandwiches", "", true),
                new MenuItem(null, "Hot Beef Sandwich", "", 18.99, "Hot Sandwiches", "", true),
                new MenuItem(null, "Hot Hamburger Sandwich", "", 18.99, "Hot Sandwiches", "", true)
            ));
            System.out.println("MongoDB seeded with " + menuRepo.count() + " menu items.");
        };
    }
}

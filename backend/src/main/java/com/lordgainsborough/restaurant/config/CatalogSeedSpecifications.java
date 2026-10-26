package com.lordgainsborough.restaurant.config;

import java.util.List;

/**
 * Shared single source of truth for restaurant catalog seed specifications.
 * <p>
 * Used identically by both MongoDB {@link DataInitializer} and PostgreSQL
 * {@link PostgresCatalogSynchronizationService} to guarantee deterministic,
 * non-divergent catalog initialization across persistence tiers.
 * </p>
 */
public final class CatalogSeedSpecifications {

    public static final String MIGRATION_ID = "2026-08-lord-gains-menu";

    public record CategorySpec(String name, String description, int displayOrder) {}

    public record MenuSpec(String name, String description, double price, String category, String[] aliases) {}

    private CatalogSeedSpecifications() {}

    public static List<CategorySpec> categories() {
        return List.of(
            category("Appetizers", "", 1),
            category("Pizzas", "Loaded corner to corner with fresh dough and 100% Canadian mozzarella. No half-and-half pizzas or substitutions.", 2),
            category("Salads", "Add grilled chicken to any salad for $6.00.", 3),
            category("Sandwiches", "Add cheese $2.50, rye $1.00, or gluten-free toast $2.00. Upgrade to deluxe $2.50 or poutine $4.00.", 4),
            category("Homemade Burgers", "Topped with tomato, onion, relish, and mustard. Add cheese $2.50; upgrade to deluxe $2.50 or poutine $4.00.", 5),
            category("Classics", "Upgrade to deluxe for $2.50, add cheese for $2.50, or upgrade fries to poutine for $4.00.", 6),
            category("Pasta", "Served with a side house salad and garlic toast. Add mushrooms for $1.00.", 7),
            category("Panzerotti", "Built like a football and served with homemade meat sauce. No substitutions.", 8),
            category("Kids Menu", "", 9),
            category("Beverages", "", 10),
            category("Hot Sandwiches", "Served with homemade gravy, fries or mashed potato, and vegetables or house salad.", 11),
            category("Sides", "Swap included fries or house salad for onion rings or sweet potato fries for $3.00.", 12),
            category("Gainsborough Melts", "Served with fries or house salad. Add mushrooms, peppers, or jalapenos for $1.00 each.", 13),
            category("A Taste of Greece", "", 14)
        );
    }

    public static List<MenuSpec> menuCatalog() {
        String pizzaExtras = " Add wings for $1 each (minimum 10), a large Caesar or house salad for $10, or large fries for $10.";
        String buildYourOwn = "Includes pizza sauce and mozzarella. Vegetable or cheese toppings: 10-inch $2.50, 12-inch $3.50, 16-inch $4.50; pineapple: $3/$4/$5. Pepperoni, ham, sausage, bacon, or ground beef: $3.50/$4.50/$5.50. Chicken or gyro: $4.50/$5.50/$6.50. Gluten-free 10-inch crust: add $4." + pizzaExtras;

        return List.of(
            // Appetizers
            item("Cheesy Garlic Bread", "Garlic butter, cheddar, and mozzarella melted over a toasted hoagie roll. Add bacon for $3.00.", 13.99, "Appetizers", "Garlic Bread and Cheese"),
            item("Jalapeno Poppers", "Breaded jalapeno poppers fried until golden.", 14.99, "Appetizers"),
            item("Mozzarella Sticks", "Six breaded mozzarella sticks, deep-fried until golden.", 14.99, "Appetizers", "Mozzarella Sticks (6 pcs)"),
            item("Pickle Spears", "Breaded pickle spears fried until crisp.", 11.99, "Appetizers", "Deep Fried Pickle Spears"),
            item("Battered Mushroom Caps", "Jumbo mushroom caps deep-fried until golden and served with sour cream.", 12.99, "Appetizers"),
            item("Homemade Potato Skins", "Four house-made potato skins topped with cheddar, mozzarella, and bacon, baked and served with sour cream.", 14.99, "Appetizers", "Homemade Potato Skins (4 pcs)"),
            item("Greek Fries", "Home-cut fries with tomato, tzatziki, feta, olive oil, garlic, oregano, and paprika. Add gyro meat for $4.00.", 14.99, "Appetizers"),
            item("Sweet Potato Fries", "Crispy sweet potato fries.", 9.99, "Appetizers"),
            item("Onion Rings", "Crispy battered onion rings.", 9.99, "Appetizers"),
            item("Home-Cut Fries (Small)", "Small order of house-cut fries.", 8.99, "Appetizers", "Fresh-Cut Fries (Small)"),
            item("Home-Cut Fries (Large)", "Large order of house-cut fries.", 12.99, "Appetizers"),
            item("Chicken Wings", "One pound tossed in Honey Garlic, Mild, Medium, Frank's RedHot, Lemon & Oregano, Hot Honey, or Fire & Ice.", 15.99, "Appetizers"),
            item("Chicken Fingers", "Four breaded white-meat chicken fingers with plum, BBQ, or honey garlic sauce.", 12.99, "Appetizers", "Chicken Fingers (4 pcs)"),
            item("Bruschetta", "Toasted hoagie bun with olive oil, garlic, tomatoes, green peppers, onions, mozzarella, and feta.", 13.99, "Appetizers"),
            item("Loaded Meat & Cheese Fries", "Home-cut fries with homemade meat sauce, cheddar, and mozzarella.", 14.99, "Appetizers"),
            item("Poutine", "Home-cut fries, cheese curds, and gravy. Add seasoned beef for $4.99.", 12.99, "Appetizers"),
            item("Butter Chicken Poutine", "Home-cut fries with cheese curds, butter chicken sauce, chicken tenders, and optional red onions.", 16.99, "Appetizers"),
            item("Nachos Deluxe (Family Size)", "Tortilla chips with cheese, tomatoes, green peppers, onions, ground beef, and bacon; served with salsa and sour cream. Add jalapenos or hot peppers for $1.", 35.99, "Appetizers", "Nacho Deluxe (Meal Size)"),
            item("Cheddar Cheese Cubes", "Breaded cheddar cheese cubes fried until golden.", 11.99, "Appetizers"),

            // Sides
            item("Side Gravy", "A side of homemade gravy.", 2.00, "Sides"),
            item("Side Tzatziki", "A side of tzatziki.", 2.00, "Sides"),
            item("Side Mayo", "A side of mayonnaise.", 1.50, "Sides"),

            // Salads
            item("House Salad (Small)", "Lettuce, tomatoes, and cucumber with Italian, Ranch, French, Balsamic, or Thousand Island dressing.", 10.99, "Salads"),
            item("House Salad (Large)", "Lettuce, tomatoes, and cucumber with Italian, Ranch, French, Balsamic, or Thousand Island dressing.", 13.99, "Salads"),
            item("Greek Salad (Small)", "Lettuce, tomatoes, onions, cucumber, Kalamata olives, and feta.", 13.99, "Salads"),
            item("Greek Salad (Large)", "Lettuce, tomatoes, onions, cucumber, Kalamata olives, and feta.", 17.99, "Salads"),
            item("Horiatiki (Village) Greek Salad (Small)", "Green peppers, tomatoes, onions, cucumber, Kalamata olives, and feta tossed in pure Greek olive oil.", 13.99, "Salads"),
            item("Horiatiki (Village) Greek Salad (Large)", "Green peppers, tomatoes, onions, cucumber, Kalamata olives, and feta tossed in pure Greek olive oil.", 17.99, "Salads"),
            item("Caesar Salad (Small)", "Romaine, creamy Caesar dressing, croutons, real bacon, and Parmesan.", 12.99, "Salads"),
            item("Caesar Salad (Large)", "Romaine, creamy Caesar dressing, croutons, real bacon, and Parmesan.", 16.99, "Salads"),
            item("Julienne Salad", "Lettuce, tomato, cucumber, julienned ham and turkey, mozzarella, and cheddar.", 18.99, "Salads"),
            item("Meatless Julienne Salad", "Lettuce, tomato, cucumber, mozzarella, and cheddar.", 16.99, "Salads"),
            item("Buffalo Chicken Salad", "Lettuce, tomato, cucumber, cheddar, and croutons with breaded chicken, Frank's RedHot, and ranch.", 17.99, "Salads"),

            // Sandwiches
            item("BLT", "Bacon, lettuce, tomato, and mayo. With fries or house salad: $15.99.", 9.99, "Sandwiches", "BLT Sandwich"),
            item("Corned Beef on Rye", "Corned beef on toasted rye with mustard and side pickles. With fries or house salad: $16.99.", 10.99, "Sandwiches", "Corned Beef on Rye Sandwich"),
            item("Western Sandwich", "Classic western sandwich. With fries or house salad: $15.99.", 10.99, "Sandwiches"),
            item("Grilled Cheese with Bacon", "Grilled cheese with bacon. With fries or house salad: $15.99.", 10.99, "Sandwiches", "Grilled Cheese with Bacon Sandwich"),
            item("Turkey Sandwich", "Turkey sandwich. With fries or house salad: $16.99.", 11.99, "Sandwiches"),
            item("Triple Decker Clubhouse", "Bacon, lettuce, tomato, turkey, cheese, and mayo; served with fries or house salad.", 18.99, "Sandwiches", "Triple Decker Club House Sandwich"),
            item("Patty Melt", "Homemade beef patty, grilled onions, and Swiss cheese on grilled rye; served with fries or house salad.", 17.99, "Sandwiches"),
            item("Reuben on Rye", "Corned beef, sauerkraut, Swiss cheese, and mustard on grilled rye; served with fries or house salad.", 17.99, "Sandwiches", "Reuben on Rye Sandwich"),
            item("Beef Dip Au Jus", "Shaved roast beef on a toasted hoagie with au jus; served with fries or house salad.", 18.99, "Sandwiches", "Beef Dip Au Jus Sandwich"),
            item("Roast Beef Sandwich", "Roast beef sandwich.", 9.99, "Sandwiches"),

            // Gainsborough Melts
            item("Classic Melt", "Seasoned steak or grilled chicken with sauteed onions, green peppers, and your choice of cheese on a toasted hoagie.", 20.99, "Gainsborough Melts"),
            item("Mushroom Melt", "Seasoned steak or grilled chicken with sauteed mushrooms, onions, and melted cheese on a toasted hoagie.", 20.99, "Gainsborough Melts"),
            item("Greek Melt", "Seasoned steak or grilled chicken with mozzarella, feta, tomato, red onion, and tzatziki on a toasted hoagie.", 20.99, "Gainsborough Melts"),

            // Hot Sandwiches
            item("Hot Turkey (White Meat)", "White-meat turkey served hot with homemade gravy.", 19.99, "Hot Sandwiches", "Hot Turkey Sandwich"),
            item("Hot Beef", "Tender beef served hot with homemade gravy.", 19.99, "Hot Sandwiches", "Hot Beef Sandwich"),
            item("Hot Hamburger", "Homemade hamburger served hot with homemade gravy.", 19.99, "Hot Sandwiches", "Hot Hamburger Sandwich"),

            // Pasta
            item("Spaghetti with Homemade Meat Sauce", "Spaghetti topped with homemade meat sauce.", 18.99, "Pasta"),
            item("Chicken Parmesan with Spaghetti", "Breaded chicken Parmesan served with spaghetti and homemade meat sauce.", 21.99, "Pasta"),
            item("Veal Parmesan with Spaghetti", "Breaded veal Parmesan served with spaghetti and homemade meat sauce.", 21.99, "Pasta"),

            // Panzerotti
            item("Canadian Panzerotti", "Bacon, pepperoni, mushroom, and cheese.", 24.99, "Panzerotti"),
            item("Deluxe Panzerotti", "Ham, pepperoni, mushroom, and cheese.", 24.99, "Panzerotti"),
            item("Meat Lover Panzerotti", "Ham, pepperoni, bacon, sausage, and cheese.", 26.99, "Panzerotti"),
            item("Build Your Own Panzo", "Includes cheese. Add vegetables for $2.00 each or meat for $3.00 each.", 20.99, "Panzerotti", "Build Your Own Panzerotti"),

            // A Taste of Greece
            item("Gyros", "Gyro meat in pita with lettuce, tomato, onion, and tzatziki. With fries or Greek salad: $18.99.", 12.99, "A Taste of Greece"),
            item("Chicken Souvlaki", "Marinated chicken in pita with lettuce, tomato, onion, and tzatziki. With fries or Greek salad: $18.99.", 12.99, "A Taste of Greece"),
            item("Two Chicken Souvlaki Skewers", "Served with Greek salad, fries, and tzatziki.", 21.99, "A Taste of Greece", "Two Marinated Chicken Skewers with Greek Salad, Fries and Tzatziki"),
            item("Gyro Plate", "Shaved gyro meat served with Greek salad, fries, and tzatziki.", 21.99, "A Taste of Greece"),

            // Homemade Burgers
            item("Hamburger", "Topped with tomato, onion, relish, and mustard. With fries or salad: $17.99.", 11.99, "Homemade Burgers"),
            item("Cheeseburger", "Topped with cheese, tomato, onion, relish, and mustard. With fries or salad: $17.99.", 12.99, "Homemade Burgers"),
            item("Bacon Cheeseburger", "Topped with bacon, cheese, tomato, onion, relish, and mustard. With fries or salad: $17.99.", 13.99, "Homemade Burgers"),
            item("Greek Burger", "Feta, red onion, tomato, and tzatziki. With fries or salad: $17.99.", 12.99, "Homemade Burgers"),
            item("Swiss Burger", "Swiss cheese and mushrooms. With fries or salad: $17.99.", 12.99, "Homemade Burgers"),
            item("Banquet Burger", "Bacon, cheese, and sauteed mushrooms. With fries or salad: $17.99.", 13.99, "Homemade Burgers"),

            // Classics
            item("Liver & Onions", "Two pieces of baby beef liver with mashed potatoes and house salad.", 19.99, "Classics", "Liver and Onion"),
            item("Homemade Fish & Chips", "Two pieces of hand-battered white fish with fresh-cut fries, coleslaw, and tartar sauce.", 19.99, "Classics", "Homemade Fish and Chips"),
            item("Chicken Finger Dinner", "Four breaded white-meat chicken fillets with fries or house salad and plum or honey garlic sauce.", 17.99, "Classics"),
            item("Chicken Bacon Ranch Burger", "Breaded chicken breast, bacon, cheddar, lettuce, tomato, and ranch on a toasted bun; served with fries or salad.", 18.99, "Classics"),
            item("Veal or Chicken Parmesan on a Bun", "Breaded chicken or veal with marinara, mozzarella, and Parmesan; served with fries or salad.", 18.99, "Classics"),
            item("Fish Burger", "Breaded fish on a bun.", 10.99, "Classics"),
            item("Chicken Burger", "Breaded chicken on a bun.", 10.99, "Classics"),

            // Loaded pizzas: each size is independently orderable
            pizza("Cheese", "A golden blend of mozzarella and cheddar.", 17.00, 20.00, 25.00, "Cheese Pizza (Small 10\")"),
            pizza("Pepperoni", "Double-stacked pepperoni layered edge to edge.", 20.00, 23.00, 29.00, "Pepperoni Pizza (Small 10\")"),
            pizza("Meat Lovers", "Pepperoni, bacon, sausage, and ham.", 25.00, 30.00, 40.00, "Meat Lovers Pizza (Small 10\")"),
            pizza("Canadian", "Pepperoni, bacon, and mushrooms.", 24.00, 29.00, 39.00, "Canadian Pizza (Small 10\")"),
            pizza("Deluxe", "Pepperoni, ham, mushrooms, and green peppers.", 24.00, 29.00, 39.00, "Deluxe Pizza (Small 10\")"),
            pizza("Hawaiian", "Ham and pineapple.", 24.00, 29.00, 39.00, "Hawaiian Pizza (Small 10\")"),
            pizza("Vegetarian", "Tomatoes, mushrooms, onions, and green peppers.", 24.00, 29.00, 38.00, "Vegetarian Pizza (Small 10\")"),
            pizza("Greek", "Green peppers, onion, tomato, Kalamata olives, feta, and mozzarella.", 25.00, 30.00, 40.00, "Greek Pizza (Small 10\")"),
            pizza("BBQ Chicken", "Grilled chicken, onions, peppers, and Forty Creek BBQ sauce.", 24.00, 29.00, 39.00, "BBQ Chicken Pizza (Small 10\")"),
            pizza("Butter Chicken", "Green pepper, onion, grilled chicken, and butter chicken sauce; jalapeno or banana peppers optional.", 24.00, 29.00, 39.00, "Butter Chicken Pizza (Small 10\")"),
            item("Build Your Own Pizza (10\")", buildYourOwn, 19.00, "Pizzas", "Build Your Own Pizza (Small 10\")"),
            item("Build Your Own Pizza (12\")", buildYourOwn, 21.00, "Pizzas"),
            item("Build Your Own Pizza (16\")", buildYourOwn, 29.00, "Pizzas"),

            // Existing items not listed in the new document remain in the catalog
            item("Chicken Finger Dinner (Kids)", "", 9.99, "Kids Menu"),
            item("Spaghetti with Homemade Meat Sauce (Kids)", "", 10.99, "Kids Menu"),
            item("Hamburger (Kids)", "", 9.99, "Kids Menu"),
            item("Fish (1 pc) and Chips (Kids)", "", 10.99, "Kids Menu"),
            item("Grilled Cheese (Kids)", "", 9.99, "Kids Menu"),
            item("Garlic Bread and Cheese (Kids)", "", 8.99, "Kids Menu"),
            item("Chocolate Milk", "", 2.99, "Beverages"),
            item("Lemonade", "", 2.99, "Beverages"),
            item("Canned Drink", "", 2.99, "Beverages"),
            item("Coffee", "", 2.99, "Beverages"),
            item("Juice", "", 2.99, "Beverages"),
            item("Milk", "", 2.99, "Beverages"),
            item("Tea", "", 2.99, "Beverages")
        ).stream().flatMap(List::stream).toList();
    }

    private static List<MenuSpec> pizza(String name, String description, double ten, double twelve, double sixteen, String oldTenInchName) {
        return List.of(
            itemSpec(name + " Pizza (10\")", description, ten, "Pizzas", oldTenInchName),
            itemSpec(name + " Pizza (12\")", description, twelve, "Pizzas"),
            itemSpec(name + " Pizza (16\")", description, sixteen, "Pizzas")
        );
    }

    private static List<MenuSpec> item(String name, String description, double price, String category, String... aliases) {
        return List.of(itemSpec(name, description, price, category, aliases));
    }

    private static MenuSpec itemSpec(String name, String description, double price, String category, String... aliases) {
        return new MenuSpec(name, description, price, category, aliases);
    }

    private static CategorySpec category(String name, String description, int displayOrder) {
        return new CategorySpec(name, description, displayOrder);
    }
}

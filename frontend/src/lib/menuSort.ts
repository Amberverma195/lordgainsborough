export interface SortableMenuItem {
  id?: string;
  name: string;
  description?: string;
  category?: string;
  price?: number;
  available?: boolean;
  mostLoved?: boolean;
}

export interface SortableCategory {
  id?: string;
  name: string;
  displayOrder?: number;
}

/**
 * Canonical category display orders matching restaurant catalog specification.
 */
export const CANONICAL_CATEGORY_ORDER: Record<string, number> = {
  appetizers: 1,
  pizzas: 2,
  salads: 3,
  sandwiches: 4,
  'homemade burgers': 5,
  classics: 6,
  pasta: 7,
  panzerotti: 8,
  'kids menu': 9,
  beverages: 10,
  'hot sandwiches': 11,
  sides: 12,
  'gainsborough melts': 13,
  'a taste of greece': 14,
};

/**
 * Canonical seed item catalog names in specification order.
 * Items defined in the catalog receive a fixed index to maintain consistent ordering.
 */
export const CANONICAL_SEED_ITEMS: string[] = [
  // Appetizers
  'Cheesy Garlic Bread',
  'Jalapeno Poppers',
  'Mozzarella Sticks',
  'Pickle Spears',
  'Battered Mushroom Caps',
  'Homemade Potato Skins',
  'Greek Fries',
  'Sweet Potato Fries',
  'Onion Rings',
  'Home-Cut Fries (Small)',
  'Home-Cut Fries (Large)',
  'Chicken Wings',
  'Chicken Fingers',
  'Bruschetta',
  'Loaded Meat & Cheese Fries',
  'Poutine',
  'Butter Chicken Poutine',
  'Nachos Deluxe (Family Size)',
  'Cheddar Cheese Cubes',
  // Sides
  'Side Gravy',
  'Side Tzatziki',
  'Side Mayo',
  // Salads
  'House Salad (Small)',
  'House Salad (Large)',
  'Greek Salad (Small)',
  'Greek Salad (Large)',
  'Horiatiki (Village) Greek Salad (Small)',
  'Horiatiki (Village) Greek Salad (Large)',
  'Caesar Salad (Small)',
  'Caesar Salad (Large)',
  'Julienne Salad',
  'Meatless Julienne Salad',
  'Buffalo Chicken Salad',
  // Sandwiches
  'BLT',
  'Corned Beef on Rye',
  'Western Sandwich',
  'Grilled Cheese with Bacon',
  'Turkey Sandwich',
  'Triple Decker Clubhouse',
  'Patty Melt',
  'Reuben on Rye',
  'Beef Dip Au Jus',
  'Roast Beef Sandwich',
  // Gainsborough Melts
  'Classic Melt',
  'Mushroom Melt',
  'Greek Melt',
  // Hot Sandwiches
  'Hot Turkey (White Meat)',
  'Hot Beef',
  'Hot Hamburger',
  // Pasta
  'Spaghetti with Homemade Meat Sauce',
  'Chicken Parmesan with Spaghetti',
  'Veal Parmesan with Spaghetti',
  // Panzerotti
  'Canadian Panzerotti',
  'Deluxe Panzerotti',
  'Meat Lover Panzerotti',
  'Build Your Own Panzo',
  // A Taste of Greece
  'Gyros',
  'Chicken Souvlaki',
  'Two Chicken Souvlaki Skewers',
  'Gyro Plate',
  // Homemade Burgers
  'Hamburger',
  'Cheeseburger',
  'Bacon Cheeseburger',
  'Greek Burger',
  'Swiss Burger',
  'Banquet Burger',
  // Classics
  'Liver & Onions',
  'Homemade Fish & Chips',
  'Chicken Finger Dinner',
  'Chicken Bacon Ranch Burger',
  'Veal or Chicken Parmesan on a Bun',
  'Fish Burger',
  'Chicken Burger',
  // Loaded Pizzas (10", 12", 16")
  'Cheese Pizza (10")',
  'Cheese Pizza (12")',
  'Cheese Pizza (16")',
  'Pepperoni Pizza (10")',
  'Pepperoni Pizza (12")',
  'Pepperoni Pizza (16")',
  'Meat Lovers Pizza (10")',
  'Meat Lovers Pizza (12")',
  'Meat Lovers Pizza (16")',
  'Canadian Pizza (10")',
  'Canadian Pizza (12")',
  'Canadian Pizza (16")',
  'Deluxe Pizza (10")',
  'Deluxe Pizza (12")',
  'Deluxe Pizza (16")',
  'Hawaiian Pizza (10")',
  'Hawaiian Pizza (12")',
  'Hawaiian Pizza (16")',
  'Vegetarian Pizza (10")',
  'Vegetarian Pizza (12")',
  'Vegetarian Pizza (16")',
  'Greek Pizza (10")',
  'Greek Pizza (12")',
  'Greek Pizza (16")',
  'BBQ Chicken Pizza (10")',
  'BBQ Chicken Pizza (12")',
  'BBQ Chicken Pizza (16")',
  'Butter Chicken Pizza (10")',
  'Butter Chicken Pizza (12")',
  'Butter Chicken Pizza (16")',
  'Build Your Own Pizza (10")',
  'Build Your Own Pizza (12")',
  'Build Your Own Pizza (16")',
  // Kids Menu
  'Chicken Finger Dinner (Kids)',
  'Spaghetti with Homemade Meat Sauce (Kids)',
  'Hamburger (Kids)',
  'Fish (1 pc) and Chips (Kids)',
  'Grilled Cheese (Kids)',
  'Garlic Bread and Cheese (Kids)',
  // Beverages
  'Chocolate Milk',
  'Lemonade',
  'Canned Drink',
  'Coffee',
  'Juice',
  'Milk',
  'Tea',
];

const CANONICAL_SEED_MAP = new Map<string, number>();
CANONICAL_SEED_ITEMS.forEach((name, idx) => {
  CANONICAL_SEED_MAP.set(name.trim().toLowerCase(), idx);
});

// Also register common aliases
const ALIASES: Record<string, string> = {
  'garlic bread and cheese': 'Cheesy Garlic Bread',
  'mozzarella sticks (6 pcs)': 'Mozzarella Sticks',
  'deep fried pickle spears': 'Pickle Spears',
  'homemade potato skins (4 pcs)': 'Homemade Potato Skins',
  'fresh-cut fries (small)': 'Home-Cut Fries (Small)',
  'chicken fingers (4 pcs)': 'Chicken Fingers',
  'nacho deluxe (meal size)': 'Nachos Deluxe (Family Size)',
  'blt sandwich': 'BLT',
  'corned beef on rye sandwich': 'Corned Beef on Rye',
  'grilled cheese with bacon sandwich': 'Grilled Cheese with Bacon',
  'triple decker club house sandwich': 'Triple Decker Clubhouse',
  'reuben on rye sandwich': 'Reuben on Rye',
  'beef dip au jus sandwich': 'Beef Dip Au Jus',
  'hot turkey sandwich': 'Hot Turkey (White Meat)',
  'hot beef sandwich': 'Hot Beef',
  'hot hamburger sandwich': 'Hot Hamburger',
  'build your own panzerotti': 'Build Your Own Panzo',
  'two marinated chicken skewers with greek salad, fries and tzatziki': 'Two Chicken Souvlaki Skewers',
  'liver and onion': 'Liver & Onions',
  'homemade fish and chips': 'Homemade Fish & Chips',
  'cheese pizza (small 10")': 'Cheese Pizza (10")',
  'pepperoni pizza (small 10")': 'Pepperoni Pizza (10")',
  'meat lovers pizza (small 10")': 'Meat Lovers Pizza (10")',
  'canadian pizza (small 10")': 'Canadian Pizza (10")',
  'deluxe pizza (small 10")': 'Deluxe Pizza (10")',
  'hawaiian pizza (small 10")': 'Hawaiian Pizza (10")',
  'vegetarian pizza (small 10")': 'Vegetarian Pizza (10")',
  'greek pizza (small 10")': 'Greek Pizza (10")',
  'bbq chicken pizza (small 10")': 'BBQ Chicken Pizza (10")',
  'butter chicken pizza (small 10")': 'Butter Chicken Pizza (10")',
  'build your own pizza (small 10")': 'Build Your Own Pizza (10")',
};

Object.entries(ALIASES).forEach(([alias, target]) => {
  const targetIdx = CANONICAL_SEED_MAP.get(target.toLowerCase());
  if (targetIdx !== undefined) {
    CANONICAL_SEED_MAP.set(alias.trim().toLowerCase(), targetIdx);
  }
});

/**
 * Sorts menu items into a deterministic, stable order:
 * 1. Category display order (Appetizers=1, Pizzas=2, etc.)
 * 2. Canonical seed index within category
 * 3. Item name (case-insensitive)
 * 4. Item ID (tie-breaker)
 *
 * NOTE: Item availability is deliberately EXCLUDED from sorting.
 * An item will remain in the exact same position whether available or unavailable.
 */
export function sortMenuItems<T extends SortableMenuItem>(
  items: T[],
  categories?: SortableCategory[]
): T[] {
  if (!Array.isArray(items)) return [];

  const categoryOrderMap = new Map<string, number>();
  // Pre-fill with canonical orders
  Object.entries(CANONICAL_CATEGORY_ORDER).forEach(([cat, order]) => {
    categoryOrderMap.set(cat, order);
  });
  // Overlay provided categories if available
  if (Array.isArray(categories)) {
    categories.forEach(cat => {
      if (cat?.name && typeof cat.displayOrder === 'number') {
        categoryOrderMap.set(cat.name.trim().toLowerCase(), cat.displayOrder);
      }
    });
  }

  return [...items].sort((a, b) => {
    // 1. Category display order
    const catA = (a.category ?? '').trim().toLowerCase();
    const catB = (b.category ?? '').trim().toLowerCase();
    const orderA = categoryOrderMap.get(catA) ?? 9999;
    const orderB = categoryOrderMap.get(catB) ?? 9999;
    if (orderA !== orderB) {
      return orderA - orderB;
    }

    // 2. Canonical seed index within category
    const nameA = (a.name ?? '').trim().toLowerCase();
    const nameB = (b.name ?? '').trim().toLowerCase();
    const seedA = CANONICAL_SEED_MAP.get(nameA) ?? 9999;
    const seedB = CANONICAL_SEED_MAP.get(nameB) ?? 9999;
    if (seedA !== seedB) {
      return seedA - seedB;
    }

    // 3. Name (case-insensitive)
    const nameComp = (a.name ?? '').localeCompare(b.name ?? '', undefined, { sensitivity: 'base' });
    if (nameComp !== 0) {
      return nameComp;
    }

    // 4. Deterministic tie-breaker: ID
    return (a.id ?? '').localeCompare(b.id ?? '');
  });
}

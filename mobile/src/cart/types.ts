import { MenuItem } from '../api/parse';

export interface CartLine {
  menuItemId: string;
  name: string;
  description: string;
  unitPrice: number;
  category: string;
  image: string;
  quantity: number;
  available: boolean;
}

export interface CartMutation {
  cart: CartLine[];
  error: string | null;
}

export const MAX_ITEM_QUANTITY = 20;
export const MAX_CART_LINES = 30;
export const MAX_TOTAL_QUANTITY = 50;
export const MAX_ORDER_TOTAL = 1000;

export function lineFromMenuItem(item: MenuItem, quantity: number): CartLine {
  return {
    menuItemId: item.id,
    name: item.name,
    description: item.description,
    unitPrice: item.price,
    category: item.category,
    image: item.image,
    quantity,
    available: item.available,
  };
}

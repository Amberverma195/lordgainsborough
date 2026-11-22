import { filterDishes, planReorder } from '../discovery';
import { MenuItem, OrderLine } from '../../api/parse';
const dish = {
  id: 'a',
  name: 'Soup',
  description: 'Roasted tomato',
  category: 'Starters',
  price: 12,
  available: true,
  image: '',
  mostLoved: false,
} satisfies MenuItem;
it('combines category, description search and favourites', () => {
  expect(filterDishes([dish], 'TOMATO', 'Starters', ['a'])).toEqual([dish]);
  expect(filterDishes([dish], 'tomato', 'Desserts')).toEqual([]);
  expect(filterDishes([dish], '', 'All', [])).toEqual([]);
});
it('uses live menu prices and reports unavailable or modified dishes', () => {
  const lines = [
    {
      menuItemId: 'a',
      name: 'Soup',
      unitPrice: 9,
      quantity: 2,
      total: 18,
      notes: '',
    },
    {
      menuItemId: 'gone',
      name: 'Old dish',
      unitPrice: 1,
      quantity: 1,
      total: 1,
      notes: '',
    },
  ] satisfies OrderLine[];
  const plan = planReorder(lines, [dish]);
  expect(plan.available).toEqual([{ item: dish, quantity: 2 }]);
  expect(plan.notices).toHaveLength(2);
  expect(plan.available[0].item.price).toBe(12);
  expect(planReorder(lines, [{ ...dish, available: false }]).available).toEqual(
    [],
  );
});

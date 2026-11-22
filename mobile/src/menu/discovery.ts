import { MenuItem, OrderLine } from '../api/parse';
export function filterDishes(
  items: MenuItem[],
  search: string,
  category: string,
  favourites?: string[],
) {
  const query = search.trim().toLocaleLowerCase();
  return items.filter(
    (item) =>
      (category === 'All' || item.category === category) &&
      (!query ||
        (item.name + ' ' + item.description)
          .toLocaleLowerCase()
          .includes(query)) &&
      (!favourites || favourites.includes(item.id)),
  );
}
export function planReorder(lines: OrderLine[], menu: MenuItem[]) {
  const available: { item: MenuItem; quantity: number }[] = [];
  const notices: string[] = [];
  for (const line of lines) {
    const item = menu.find((item) => item.id === line.menuItemId);
    if (!item || !item.available) {
      notices.push(line.name + ' is unavailable or discontinued.');
      continue;
    }
    if (item.price !== line.unitPrice || item.name !== line.name)
      notices.push(item.name + ' has changed; current price will be used.');
    available.push({ item, quantity: line.quantity });
  }
  return { available, notices };
}

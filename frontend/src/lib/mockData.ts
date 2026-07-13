export interface MenuItem {
  id: string;
  name: string;
  description: string;
  price: number;
  category: string;
  image: string;
}

export const menuItems: MenuItem[] = [
  {
    id: '1',
    name: 'Truffle Burrata',
    description: 'Creamy burrata, shaved black truffle, heirloom tomatoes, and aged balsamic glaze.',
    price: 18,
    category: 'Starters',
    image: '/menu/burrata.jpg'
  },
  {
    id: '2',
    name: 'Pan-Seared Scallops',
    description: 'Hand-dived scallops, cauliflower purée, crispy pancetta, and herb oil.',
    price: 22,
    category: 'Starters',
    image: '/menu/scallops.jpg'
  },
  {
    id: '3',
    name: 'Wagyu Beef Wellington',
    description: 'Grade A5 Wagyu, mushroom duxelles, prosciutto, in a buttery puff pastry.',
    price: 65,
    category: 'Mains',
    image: '/menu/wellington.jpg'
  },
  {
    id: '4',
    name: 'Wild Mushroom Risotto',
    description: 'Arborio rice, seasonal wild mushrooms, 24-month aged Parmesan, and truffle oil.',
    price: 32,
    category: 'Mains',
    image: '/menu/risotto.jpg'
  },
  {
    id: '5',
    name: 'Gold Leaf Chocolate Melt',
    description: 'Dark chocolate fondant, 24k gold leaf, vanilla bean gelato.',
    price: 16,
    category: 'Desserts',
    image: '/menu/dessert.jpg'
  },
  {
    id: '6',
    name: 'Château Margaux 2015',
    description: 'A legendary vintage with notes of blackcurrant, violet, and graphite.',
    price: 450,
    category: 'Wines',
    image: '/menu/wine.jpg'
  }
];

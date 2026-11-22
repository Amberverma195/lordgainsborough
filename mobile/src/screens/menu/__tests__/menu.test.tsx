import React from 'react';
import {
  render,
  screen,
  fireEvent,
  waitFor,
} from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Menu from '../../../app/(tabs)/menu';
jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);
jest.mock('expo-router', () => ({ router: { push: jest.fn() } }));
jest.mock('../../../auth/AuthContext', () => ({
  useAuth: () => ({ user: null }),
}));
jest.mock('../../../cart/CartContext', () => ({
  useCart: () => ({ count: 0 }),
}));
jest.mock('../../../components/AppHeader', () => ({ AppHeader: () => null }));
jest.mock('../../../components/Screen', () => ({
  Screen: ({ children }: any) => <>{children}</>,
}));
jest.mock('../../../components/DishImage', () => ({ DishImage: () => null }));
jest.mock('../../../api/catalog', () => ({
  fetchMenu: async () => [
    {
      id: 'soup',
      name: 'Soup',
      description: 'Tomato',
      category: 'Starters',
      price: 12,
      available: true,
    },
    {
      id: 'cake',
      name: 'Cake',
      description: 'Chocolate',
      category: 'Desserts',
      price: 9,
      available: false,
    },
  ],
  fetchCategories: async () => [],
  categoryFilters: () => ['Starters', 'Desserts'],
}));
it('combines native search and category controls with an empty state', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const view = await render(
    <QueryClientProvider client={client}>
      <Menu />
    </QueryClientProvider>,
  );
  await waitFor(() => expect(screen.getByText('Soup')).toBeTruthy());
  await fireEvent.changeText(screen.getByLabelText('Search dishes'), 'tomato');
  expect(screen.queryByText('Cake')).toBeNull();
  await fireEvent.press(screen.getByText('Desserts'));
  expect(screen.getByText(/No dishes match/)).toBeTruthy();
  await fireEvent.changeText(screen.getByLabelText('Search dishes'), '');
  expect(screen.getByText('Cake')).toBeTruthy();
  expect(screen.getByText('Unavailable')).toBeTruthy();
  await view.unmount();
  client.clear();
});

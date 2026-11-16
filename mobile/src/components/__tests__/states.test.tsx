import { render, screen } from '@testing-library/react-native';

import { Price } from '../Price';
import { EmptyState } from '../States';

describe('price and empty states', () => {
  it('shows a dish price with cents', async () => {
    await render(<Price amount={12.5} />);
    expect(screen.getByText('$12.50')).toBeTruthy();
  });

  it('shows the empty cart message', async () => {
    await render(
      <EmptyState
        title="Your cart is empty"
        message="Choose a dish from the menu when you are ready to order for pickup."
      />,
    );
    expect(screen.getByText('Your cart is empty')).toBeTruthy();
    expect(screen.getByText(/Choose a dish from the menu/)).toBeTruthy();
  });
});

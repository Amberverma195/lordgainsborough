import { estimateTotals, formatMoney, roundMoney } from '../money';

describe('price display', () => {
  it('formats money with cents', () => {
    expect(formatMoney(12)).toBe('$12.00');
    expect(formatMoney(12.5)).toBe('$12.50');
    expect(formatMoney(0)).toBe('$0.00');
  });

  it('matches the backend rounding order for tax', () => {
    expect(roundMoney(10.1 * 3)).toBe(30.3);
    expect(estimateTotals([{ unitPrice: 24, quantity: 2 }], 0.13)).toEqual({
      subtotal: 48,
      tax: 6.24,
      total: 54.24,
    });
  });
});

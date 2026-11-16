export function roundMoney(value: number): number {
  return Math.round(value * 100) / 100;
}

export function formatMoney(value: number): string {
  const rounded = roundMoney(Number.isFinite(value) ? value : 0);
  const negative = rounded < 0;
  const absolute = Math.abs(rounded);
  const cents = Math.round(absolute * 100);
  const dollars = Math.floor(cents / 100);
  const remainder = String(cents % 100).padStart(2, '0');
  return `${negative ? '-' : ''}$${dollars}.${remainder}`;
}

export function estimateTotals(lines: { unitPrice: number; quantity: number }[], taxRate: number): {
  subtotal: number;
  tax: number;
  total: number;
} {
  const subtotal = roundMoney(lines.reduce((sum, line) => sum + roundMoney(line.unitPrice * line.quantity), 0));
  const tax = roundMoney(subtotal * taxRate);
  return {
    subtotal,
    tax,
    total: roundMoney(subtotal + tax),
  };
}

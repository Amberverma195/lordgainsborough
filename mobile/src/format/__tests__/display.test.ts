import { formatOrderId, formatOrderReference, formatProgressTimestamp, orderStatusLabel } from '../display';

describe('display formatters', () => {
  it('formats order reference', () => {
    expect(formatOrderReference('abc-def-123456')).toBe('ORD-123456');
  });

  it('formats order ID to last 6 alphanumeric characters in uppercase', () => {
    expect(formatOrderId('199a5d12-d8ff-45ab-a850-7cff1de7f2be')).toBe('E7F2BE');
    expect(formatOrderId('ord-xyz789')).toBe('XYZ789');
    expect(formatOrderId('')).toBe('');
  });

  it('formats order status labels accurately', () => {
    expect(orderStatusLabel('NEW')).toBe('Received');
    expect(orderStatusLabel('ACCEPTED')).toBe('In progress');
    expect(orderStatusLabel('READY')).toBe('Ready for pickup');
    expect(orderStatusLabel('PICKED_UP')).toBe('Picked up');
    expect(orderStatusLabel('NO_SHOW')).toBe('No show');
  });

  it('formats progress timestamp', () => {
    const formatted = formatProgressTimestamp('2026-11-22T15:00:00Z');
    expect(formatted).toMatch(/Nov 22|22 Nov/);
    expect(formatProgressTimestamp(null)).toBe('');
    expect(formatProgressTimestamp('invalid-date')).toBe('');
  });
});

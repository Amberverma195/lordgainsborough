import { CustomerOrderView } from '../../api/parse';
import {
  clearAllConfirmedOrders,
  clearConfirmedOrder,
  readConfirmedOrder,
  rememberConfirmedOrder,
} from '../confirmationCache';

const sampleOrder: CustomerOrderView = {
  id: 'ord-123',
  pickupDate: '2026-11-20',
  pickupTime: '12:00 PM',
  specialInstructions: 'Extra gravy',
  status: 'CONFIRMED',
  averageReadyMinutes: 25,
  items: [],
  subtotal: 35.0,
  tax: 4.55,
  total: 39.55,
  createdAt: '2026-11-16T00:00:00Z',
  reviewed: false,
  customerName: 'Customer One',
  customerEmail: 'customer.one@example.com',
  customerPhone: '555-0100',
};

describe('confirmationCache', () => {
  beforeEach(() => {
    clearAllConfirmedOrders();
  });

  it('remembers and reads confirmed order for matching customer', () => {
    rememberConfirmedOrder(sampleOrder);

    // Matching email succeeds
    const result = readConfirmedOrder('ord-123', 'customer.one@example.com');
    expect(result).toEqual(sampleOrder);

    // Case-insensitive matching succeeds
    const caseResult = readConfirmedOrder('ord-123', 'CUSTOMER.ONE@EXAMPLE.COM');
    expect(caseResult).toEqual(sampleOrder);
  });

  it('refuses to return confirmed order for a different customer', () => {
    rememberConfirmedOrder(sampleOrder);

    const different = readConfirmedOrder('ord-123', 'customer.two@example.com');
    expect(different).toBeNull();
  });

  it('clears specific order', () => {
    rememberConfirmedOrder(sampleOrder);
    clearConfirmedOrder('ord-123');
    expect(readConfirmedOrder('ord-123', 'customer.one@example.com')).toBeNull();
  });

  it('clears all confirmed orders when clearAllConfirmedOrders is called', () => {
    rememberConfirmedOrder(sampleOrder);
    clearAllConfirmedOrders();
    expect(readConfirmedOrder('ord-123')).toBeNull();
  });
});

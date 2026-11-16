import * as client from '../../api/client';
import { fetchOrderSocketTicket } from '../../api/orders';

describe('Order socket ticket fetching', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('retrieves the socket ticket from the API endpoint', async () => {
    const spy = jest.spyOn(client, 'apiRequest').mockResolvedValueOnce({
      status: 200,
      body: { ticket: 'valid-socket-ticket-123' },
      retryAfterHeader: null,
    });

    const ticket = await fetchOrderSocketTicket('order-456', 'auth-jwt', 'track-token');
    expect(ticket).toBe('valid-socket-ticket-123');
    expect(spy).toHaveBeenCalledWith('/api/orders/order-456/socket-ticket', {
      token: 'auth-jwt',
      headers: { 'X-Order-Tracking-Token': 'track-token' },
    });
  });

  it('safely returns null when the server responds without a ticket', async () => {
    jest.spyOn(client, 'apiRequest').mockResolvedValueOnce({
      status: 200,
      body: {},
      retryAfterHeader: null,
    });

    const ticket = await fetchOrderSocketTicket('order-456', 'auth-jwt');
    expect(ticket).toBeNull();
  });

  it('safely catches errors and returns null instead of throwing', async () => {
    jest.spyOn(client, 'apiRequest').mockRejectedValueOnce(new Error('Network failure'));

    const ticket = await fetchOrderSocketTicket('order-456');
    expect(ticket).toBeNull();
  });
});

import { parseErrorPayload, parseRetryAfterHeader } from '../errors';

describe('API error parsing', () => {
  it('reads a plain-text unauthorized body', () => {
    expect(parseErrorPayload(401, 'Expired or invalid token.')).toEqual({
      code: 'UNAUTHORIZED',
      message: 'Expired or invalid token.',
      retryAfterSeconds: null,
    });
  });

  it('reads a coded JSON body and prefers the body retry delay', () => {
    expect(parseErrorPayload(429, {
      code: 'RATE_LIMIT_EXCEEDED',
      message: 'Too many orders placed for this account. Please try again later.',
      retryAfterSeconds: 42,
    }, '9')).toEqual({
      code: 'RATE_LIMIT_EXCEEDED',
      message: 'Too many orders placed for this account. Please try again later.',
      retryAfterSeconds: 42,
    });
  });

  it('uses the Retry-After header when the body has no delay', () => {
    expect(parseRetryAfterHeader('15')).toBe(15);
    expect(parseErrorPayload(503, {
      code: 'PICKUP_ORDERS_PAUSED',
      message: 'Pickup orders are currently paused. Please check back later.',
    }, '15').retryAfterSeconds).toBe(15);
  });

  it('does not invent a success message for an empty failure', () => {
    const parsed = parseErrorPayload(500, null);
    expect(parsed.code).toBe('REQUEST_FAILED');
    expect(parsed.message).toContain('could not complete');
  });

  it('describes a network failure separately from a server rejection', () => {
    expect(parseErrorPayload(0, { code: 'NETWORK_ERROR', message: 'offline' })).toMatchObject({
      code: 'NETWORK_ERROR',
      message: 'offline',
    });
  });
});

import { apiRequest, onUnauthorized } from '../client';
import { accountWillChange } from '../../auth/lifecycle';
jest.mock('../runtime', () => ({ resolveApiOrigins: () => ({ api: 'https://staging.invalid' }) }));

it('does not sign out the new account when an old authenticated request returns 401', async () => {
  let finish!: (value: Response) => void;
  const previousFetch = global.fetch;
  global.fetch = jest.fn(() => new Promise<Response>(resolve => { finish = resolve; }));
  const unauthorized = jest.fn();
  const stop = onUnauthorized(unauthorized);
  try {
    const request = apiRequest('/api/orders/old', { token: 'account-a' });
    accountWillChange();
    finish({ ok: false, status: 401, text: async () => '{}', headers: { get: () => null } } as unknown as Response);
    await expect(request).rejects.toMatchObject({ status: 401 });
    expect(unauthorized).not.toHaveBeenCalled();
  } finally { stop(); global.fetch = previousFetch; }
});

import { revokeAndClearSession } from '../signOut';
import { logout } from '../../api/auth';
import { readSessionToken } from '../secureSession';
jest.mock('../../api/auth', () => ({ logout: jest.fn() }));
jest.mock('../secureSession', () => ({ readSessionToken: jest.fn() }));
beforeEach(() => jest.resetAllMocks());
it('clears local state once after server acknowledgment', async () => {
  (readSessionToken as jest.Mock).mockResolvedValue('device');
  (logout as jest.Mock).mockResolvedValue(undefined);
  const clear = jest.fn();
  await revokeAndClearSession(clear);
  expect(logout).toHaveBeenCalledWith('device');
  expect(clear).toHaveBeenCalledTimes(1);
});
it('retains the device session if server revocation fails', async () => {
  (readSessionToken as jest.Mock).mockResolvedValue('device');
  (logout as jest.Mock).mockRejectedValue(new Error('Offline'));
  const clear = jest.fn();
  await expect(revokeAndClearSession(clear)).rejects.toThrow('Offline');
  expect(clear).not.toHaveBeenCalled();
});
it('does not clear a newer session after a delayed logout response', async () => {
  (readSessionToken as jest.Mock)
    .mockResolvedValueOnce('old')
    .mockResolvedValueOnce('new');
  const clear = jest.fn();
  await revokeAndClearSession(clear);
  expect(clear).not.toHaveBeenCalled();
});

it('reports secure storage read failure without claiming sign-out', async () => {
 (readSessionToken as jest.Mock).mockRejectedValueOnce(new Error('Storage unavailable'));
 const clear=jest.fn();
 await expect(revokeAndClearSession(clear)).rejects.toThrow('Storage unavailable');
 expect(logout).not.toHaveBeenCalled(); expect(clear).not.toHaveBeenCalled();
});

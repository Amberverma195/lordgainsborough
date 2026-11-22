import React from 'react';
import { render, act, waitFor } from '@testing-library/react-native';
import { AuthProvider, useAuth, AuthContextValue } from '../AuthContext';
import { fetchCurrentUser } from '../../api/auth';
jest.mock('../../api/auth', () => ({
  fetchCurrentUser: jest.fn(),
  login: jest.fn(),
}));
jest.mock('../../api/client', () => ({ onUnauthorized: () => () => {} }));
jest.mock('../secureSession', () => ({
  readSessionToken: jest.fn(async () => 'token'),
  clearSessionToken: jest.fn(),
  clearAccountTrackingTokens: jest.fn(),
  writeSessionToken: jest.fn(),
}));
jest.mock('../deleteAccountData', () => ({
  clearDeletedAccountData: async (email: string, detach: () => void) => {
    require('../secureSession').readSessionToken.mockResolvedValue(null);
    detach();
  },
}));
jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);
let context: AuthContextValue;
function Probe() {
  const value = useAuth();
  React.useEffect(() => {
    context = value;
  }, [value]);
  return null;
}
it('a delayed refresh cannot restore a deleted account', async () => {
  const user = { token: 'token', email: 'a@example.com', name: 'Member' };
  (fetchCurrentUser as jest.Mock).mockResolvedValue(user);
  await render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );
  await waitFor(() => expect(context.status).toBe('signedIn'));
  let resolve!: (value: unknown) => void;
  (fetchCurrentUser as jest.Mock).mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done;
      }),
  );
  let pending!: Promise<void>;
  await act(async () => {
    pending = context.refresh();
  });
  await act(async () => {
    await context.finishAccountDeletion(user.email);
  });
  await act(async () => {
    resolve(user);
    await pending;
  });
  expect(context.user).toBeNull();
  expect(context.status).toBe('signedOut');
});

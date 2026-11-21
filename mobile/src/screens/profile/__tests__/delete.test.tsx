import React from 'react';
import {
  render,
  fireEvent,
  screen,
  waitFor,
} from '@testing-library/react-native';
import { deleteAccount } from '../../../api/auth';
import { ApiError } from '../../../api/errors';
import { clearDeletedAccountData } from '../../../auth/deleteAccountData';
import DeleteAccountScreen from '../../../app/profile/delete';
jest.mock('expo-router', () => ({ router: { replace: jest.fn() } }));
jest.mock('../../../api/auth', () => ({ deleteAccount: jest.fn() }));
jest.mock('../../../auth/deleteAccountData', () => ({
  clearDeletedAccountData: jest.fn(),
}));
const mockSignOut = jest.fn();
jest.mock('../../../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { token: 'jwt', email: 'member@example.com' },
    signOut: mockSignOut,
  }),
}));
jest.mock('../../../components/AppHeader', () => ({ AppHeader: () => null }));
jest.mock('../../../components/Screen', () => ({
  Screen: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
beforeEach(() => {
  jest.clearAllMocks();
});
it('requires a password and permanently deletes with JWT then clears private device data', async () => {
  (deleteAccount as jest.Mock).mockResolvedValue(undefined);
  await render(<DeleteAccountScreen />);
  await fireEvent.changeText(
    screen.getByLabelText('Current password'),
    'Secret1!',
  );
  await fireEvent.press(screen.getByText('Permanently delete my account'));
  await waitFor(() =>
    expect(clearDeletedAccountData).toHaveBeenCalledWith('member@example.com'),
  );
  expect(deleteAccount).toHaveBeenCalledWith('jwt', 'Secret1!');
  expect(mockSignOut).toHaveBeenCalled();
});
it.each([
  [403, 'REQUEST_FAILED'],
  [401, 'UNAUTHORIZED'],
  [429, 'REQUEST_FAILED'],
  [409, 'ACCOUNT_HAS_ACTIVE_ORDERS'],
])('keeps cleanup gated on successful deletion (%s)', async (status, code) => {
  (deleteAccount as jest.Mock).mockRejectedValue(
    new ApiError({ status, code, message: 'Rejected', retryAfterSeconds: 30 }),
  );
  await render(<DeleteAccountScreen />);
  await fireEvent.changeText(
    screen.getByLabelText('Current password'),
    'wrong',
  );
  await fireEvent.press(screen.getByText('Permanently delete my account'));
  await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
  expect(clearDeletedAccountData).not.toHaveBeenCalled();
});

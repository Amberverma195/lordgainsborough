import React from 'react';
import {
  render,
  screen,
  fireEvent,
  waitFor,
} from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { apiRequest } from '../../api/client';
import Reserve from '../../app/reserve';
jest.mock('@react-native-async-storage/async-storage', () =>
  require('@react-native-async-storage/async-storage/jest/async-storage-mock'),
);
jest.mock('../../api/client', () => ({ apiRequest: jest.fn() }));
jest.mock('../../components/AppHeader', () => ({ AppHeader: () => null }));
jest.mock('../../components/Screen', () => ({
  Screen: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
beforeEach(async () => {
  jest.clearAllMocks();
  await AsyncStorage.clear();
});
it('sends a pending request once and retains an interruption marker', async () => {
  (apiRequest as jest.Mock).mockResolvedValue({ body: { status: 'PENDING' } });
  await render(<Reserve />);
  await waitFor(() => expect(screen.getByLabelText('Guest name')).toBeTruthy());
  for (const [label, value] of Object.entries({
    name: 'Guest',
    email: 'guest@example.com',
    phone: '5195550100',
    date: '2026-12-01',
    time: '18:00',
  }))
    await fireEvent.changeText(
      screen.getByLabelText(
        (
          {
            name: 'Guest name',
            email: 'Email address',
            phone: 'Telephone number',
            date: 'Date YYYY-MM-DD',
            time: 'Time HH:mm (24-hour)',
          } as Record<string, string>
        )[label],
      ),
      value,
    );
  await fireEvent.press(screen.getByText('Submit reservation request'));
  await waitFor(() =>
    expect(screen.getByText(/Request received/)).toBeTruthy(),
  );
  expect(apiRequest).toHaveBeenCalledTimes(1);
  expect(await AsyncStorage.getItem('lgb.reservation.pending.v1')).toBe('sent');
});
it('blocks another submission after an interrupted request', async () => {
  await AsyncStorage.setItem('lgb.reservation.pending.v1', 'uncertain');
  await render(<Reserve />);
  await waitFor(() =>
    expect(screen.getByText(/Call to check your previous/)).toBeTruthy(),
  );
  expect(screen.queryByText('Submit reservation request')).toBeNull();
  expect(apiRequest).not.toHaveBeenCalled();
});

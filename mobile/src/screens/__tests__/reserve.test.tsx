jest.mock('@tanstack/react-query',()=>({useQuery:()=>({data:{lastSeatingMinutes:60,advanceNoticeMinutes:30,managementApproved:false},refetch:jest.fn()})}));
import * as SecureStore from 'expo-secure-store';
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
import { reservationKeys } from '../../reservations/choices';
const RESERVATION_REFERENCE_KEY = reservationKeys(null).reference;
let mockAccount: { email: string } | null = null;
jest.mock('../../auth/AuthContext', () => ({
  useAuth: () => ({ user: mockAccount }),
}));
jest.mock('../../components/ReservationPicker', () => ({
  ReservationPicker: ({ date, time, partySize, onChange }: any) => {
    const { TextInput } = require('react-native');
    return (
      <>
        <TextInput
          accessibilityLabel="Date YYYY-MM-DD"
          value={date}
          onChangeText={(value: string) =>
            onChange({ date: value, time, partySize })
          }
        />
        <TextInput
          accessibilityLabel="Time HH:mm (24-hour)"
          value={time}
          onChangeText={(value: string) =>
            onChange({ date, time: value, partySize })
          }
        />
      </>
    );
  },
}));
jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(),
}));
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
  mockAccount = null;
  (SecureStore.getItemAsync as jest.Mock).mockReset();
  (SecureStore.setItemAsync as jest.Mock).mockReset();
  await AsyncStorage.clear();
});
it('sends a pending request once and retains an interruption marker', async () => {
  (apiRequest as jest.Mock).mockResolvedValue({
    body: { id: 'request-123', status: 'PENDING' },
  });
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
  expect(screen.getByLabelText('Reservation request reference')).toBeTruthy();
  expect(SecureStore.setItemAsync).toHaveBeenCalledWith(
    RESERVATION_REFERENCE_KEY,
    expect.any(String),
  );
  expect(apiRequest).toHaveBeenCalledTimes(1);
  expect(await AsyncStorage.getItem(reservationKeys(null).pending)).toBe(
    'sent',
  );
});
it('blocks another submission after an interrupted request', async () => {
  await AsyncStorage.setItem(reservationKeys(null).pending, 'uncertain');
  await render(<Reserve />);
  await waitFor(() =>
    expect(screen.getByText(/Call to check your previous/)).toBeTruthy(),
  );
  expect(screen.queryByText('Submit reservation request')).toBeNull();
  expect(apiRequest).not.toHaveBeenCalled();
});

it('restores the reference securely without implying live booking confirmation', async () => {
  await AsyncStorage.setItem(reservationKeys(null).pending, 'sent');
  (SecureStore.getItemAsync as jest.Mock).mockResolvedValue(
    JSON.stringify({ id: 'restored-123', status: 'PENDING' }),
  );
  await render(<Reserve />);
  await waitFor(() =>
    expect(screen.getByLabelText('Reservation request reference')).toBeTruthy(),
  );
  expect(screen.getByText(/restored-123/)).toBeTruthy();
  expect(screen.getByText(/Pending confirmation at submission/)).toBeTruthy();
  expect(apiRequest).not.toHaveBeenCalled();
});
it('does not invent a reference for malformed saved data', async () => {
  (SecureStore.getItemAsync as jest.Mock).mockResolvedValue('{invalid');
  await render(<Reserve />);
  await waitFor(() =>
    expect(
      screen.getByText(/Saved request details could not be read/),
    ).toBeTruthy(),
  );
  expect(screen.queryByText('Submit reservation request')).toBeNull();
});

it.each(['storage failure', 'malformed response'])(
  'preserves duplicate protection after %s',
  async (mode) => {
    (apiRequest as jest.Mock).mockResolvedValue({
      body:
        mode === 'malformed response'
          ? { status: 'PENDING' }
          : { id: 'received-123', status: 'PENDING' },
    });
    if (mode === 'storage failure')
      (SecureStore.setItemAsync as jest.Mock).mockRejectedValue(
        new Error('locked'),
      );
    await render(<Reserve />);
    await waitFor(() =>
      expect(screen.getByLabelText('Guest name')).toBeTruthy(),
    );
    for (const [label, value] of [
      ['Guest name', 'Guest'],
      ['Email address', 'guest@example.com'],
      ['Telephone number', '5195550100'],
      ['Date YYYY-MM-DD', '2026-12-01'],
      ['Time HH:mm (24-hour)', '18:00'],
    ])
      await fireEvent.changeText(screen.getByLabelText(label), value);
    await fireEvent.press(screen.getByText('Submit reservation request'));
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.queryByText('Submit reservation request')).toBeNull();
    expect(await AsyncStorage.getItem(reservationKeys(null).pending)).toBe(
      'uncertain',
    );
    if (mode === 'storage failure')
      expect(screen.getByText(/received-123/)).toBeTruthy();
    else
      expect(
        screen.queryByLabelText('Reservation request reference'),
      ).toBeNull();
    expect(apiRequest).toHaveBeenCalledTimes(1);
  },
);

it('clears contact drafts when switching account scopes', async () => {
  mockAccount = { email: 'first@example.com' };
  const view = await render(<Reserve />);
  await waitFor(() => expect(screen.getByLabelText('Guest name')).toBeTruthy());
  await fireEvent.changeText(
    screen.getByLabelText('Guest name'),
    'First customer',
  );
  mockAccount = { email: 'second@example.com' };
  await view.rerender(<Reserve />);
  await waitFor(() =>
    expect(screen.getByLabelText('Guest name').props.value).toBe(''),
  );
  expect(SecureStore.getItemAsync).toHaveBeenCalledWith(
    reservationKeys('second@example.com').reference,
  );
});

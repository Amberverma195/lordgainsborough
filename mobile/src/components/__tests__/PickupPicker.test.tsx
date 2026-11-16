import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import React from 'react';
import { AppState, type AppStateStatus } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');

import { PickupPicker } from '../PickupPicker';

const initialMetrics = {
  frame: { x: 0, y: 0, width: 390, height: 844 },
  insets: { top: 0, left: 0, right: 0, bottom: 0 },
};

describe('PickupPicker component', () => {
  let appStateListener: ((state: AppStateStatus) => void) | null = null;

  beforeEach(() => {
    jest.clearAllMocks();
    appStateListener = null;

    jest.spyOn(AppState, 'addEventListener').mockImplementation((_, listener) => {
      appStateListener = listener as (state: AppStateStatus) => void;
      return {
        remove: jest.fn(() => {
          appStateListener = null;
        }),
      } as any;
    });
  });

  it('renders date and time fields and opens selection modal', async () => {
    const clock = new Date('2026-11-15T17:00:00Z'); // Sunday 12:00 PM
    const onChange = jest.fn();

    render(
      <SafeAreaProvider initialMetrics={initialMetrics}>
        <PickupPicker date="2026-11-15" time="1:00 PM" onChange={onChange} getNow={() => clock} />
      </SafeAreaProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText('Date')).toBeTruthy();
      expect(screen.getByText('Time')).toBeTruthy();
      expect(screen.getByText('1:00 PM')).toBeTruthy();
    });

    // Tap to choose pickup time
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Choose pickup time'));
    });

    await waitFor(() => {
      expect(screen.getByText('Pickup time')).toBeTruthy();
      expect(screen.getByText('1:30 PM')).toBeTruthy();
    });

    // Select 1:30 PM
    await act(async () => {
      fireEvent.press(screen.getByText('1:30 PM'));
    });

    expect(onChange).toHaveBeenCalledWith('2026-11-15', '1:30 PM');
  });

  it('regenerates slots and alerts when selected time slot passes on app resume', async () => {
    let clock = new Date('2026-11-15T17:00:00Z'); // 12:00 PM
    const onChange = jest.fn();

    render(
      <SafeAreaProvider initialMetrics={initialMetrics}>
        <PickupPicker
          date="2026-11-15"
          time="1:00 PM"
          onChange={onChange}
          getNow={() => clock}
        />
      </SafeAreaProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText('1:00 PM')).toBeTruthy();
    });

    // App resumes at 1:15 PM (18:15 UTC)
    clock = new Date('2026-11-15T18:15:00Z');
    await act(async () => {
      appStateListener?.('active');
    });

    await waitFor(() => {
      expect(onChange).toHaveBeenCalledWith('2026-11-15', '');
      expect(screen.getByText(/Selected pickup time \(1:00 PM\) has passed/)).toBeTruthy();
    });
  });

  it('clears date and displays notice when midnight passes and app resumes', async () => {
    let clock = new Date('2026-11-15T17:00:00Z'); // Sunday
    const onChange = jest.fn();

    render(
      <SafeAreaProvider initialMetrics={initialMetrics}>
        <PickupPicker
          date="2026-11-15"
          time="1:00 PM"
          onChange={onChange}
          getNow={() => clock}
        />
      </SafeAreaProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText('Sun, Nov 15')).toBeTruthy();
    });

    // Clock advances past midnight to Monday Nov 16 and app resumes
    clock = new Date('2026-11-16T17:00:00Z');
    await act(async () => {
      appStateListener?.('active');
    });

    await waitFor(() => {
      expect(onChange).toHaveBeenCalledWith('', '');
      expect(screen.getByText(/The selected pickup date has passed/)).toBeTruthy();
    });
  });
});

import { act, renderHook } from '@testing-library/react-native';
import { AppState, type AppStateStatus } from 'react-native';

import { usePickupSchedule } from '../usePickupSchedule';

describe('usePickupSchedule', () => {
  let appStateListener: ((state: AppStateStatus) => void) | null = null;

  beforeEach(() => {
    jest.useFakeTimers();
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

  afterEach(() => {
    jest.useRealTimers();
  });

  it('generates available dates and time slots based on initial reference time', async () => {
    // Sunday 2026-11-15 12:00 PM Toronto (17:00 UTC)
    let currentClock = new Date('2026-11-15T17:00:00Z');
    const getNow = () => currentClock;

    const { result } = await renderHook(() =>
      usePickupSchedule({
        selectedDate: '2026-11-15',
        getNow,
      }),
    );

    expect(result.current.dates[0]).toBe('2026-11-15');
    // Sunday closing is 6:00 PM; past 12:00 PM slots should be available
    expect(result.current.times).toContain('asap');
    expect(result.current.times).toContain('12:30 PM');
    expect(result.current.times).toContain('6:00 PM');
    // Past slot at 11:30 AM should not be in choices
    expect(result.current.times).not.toContain('11:30 AM');
    expect(result.current.times).not.toContain('6:30 PM');
  });

  it('regenerates available slots as time passes (periodic clock refresh)', async () => {
    // Start at 12:00 PM (17:00 UTC)
    let currentClock = new Date('2026-11-15T17:00:00Z');
    const getNow = () => currentClock;
    const onInvalidateTime = jest.fn();

    const { result } = await renderHook(() =>
      usePickupSchedule({
        selectedDate: '2026-11-15',
        selectedTime: '12:30 PM',
        refreshIntervalMs: 30_000,
        getNow,
        onInvalidateTime,
      }),
    );

    expect(result.current.times).toContain('12:30 PM');
    expect(onInvalidateTime).not.toHaveBeenCalled();

    // Advance clock to 12:45 PM (17:45 UTC) and trigger timer
    currentClock = new Date('2026-11-15T17:45:00Z');
    await act(async () => {
      jest.advanceTimersByTime(30_000);
    });

    // 12:30 PM is now in the past
    expect(result.current.times).not.toContain('12:30 PM');
    expect(result.current.times).toContain('1:00 PM');
    expect(onInvalidateTime).toHaveBeenCalledWith('12:30 PM');
  });

  it('regenerates available slots and notifies when app resumes from background', async () => {
    // Start at 12:00 PM (17:00 UTC)
    let currentClock = new Date('2026-11-15T17:00:00Z');
    const getNow = () => currentClock;
    const onInvalidateTime = jest.fn();

    const { result } = await renderHook(() =>
      usePickupSchedule({
        selectedDate: '2026-11-15',
        selectedTime: '1:00 PM',
        getNow,
        onInvalidateTime,
      }),
    );

    expect(result.current.times).toContain('1:00 PM');

    // Simulate app going to background, user coming back at 1:15 PM (18:15 UTC)
    currentClock = new Date('2026-11-15T18:15:00Z');
    expect(appStateListener).toBeTruthy();

    await act(async () => {
      appStateListener?.('active');
    });

    // Slot 1:00 PM should now be invalid and removed
    expect(result.current.times).not.toContain('1:00 PM');
    expect(result.current.times).toContain('1:30 PM');
    expect(onInvalidateTime).toHaveBeenCalledWith('1:00 PM');
  });

  it('invalidates date selection and regenerates date list when midnight passes', async () => {
    // 11:50 PM Sunday 2026-11-15 (04:50 UTC 2026-11-16)
    let currentClock = new Date('2026-11-16T04:50:00Z');
    const getNow = () => currentClock;
    const onInvalidateDate = jest.fn();

    const { result } = await renderHook(() =>
      usePickupSchedule({
        selectedDate: '2026-11-15',
        getNow,
        onInvalidateDate,
      }),
    );

    expect(result.current.dates[0]).toBe('2026-11-15');

    // Advance clock past midnight to 12:10 AM Monday 2026-11-16 (05:10 UTC)
    currentClock = new Date('2026-11-16T05:10:00Z');
    await act(async () => {
      jest.advanceTimersByTime(30_000);
    });

    // Date list now starts on 2026-11-16; 2026-11-15 is expired
    expect(result.current.dates[0]).toBe('2026-11-16');
    expect(result.current.dates).not.toContain('2026-11-15');
    expect(onInvalidateDate).toHaveBeenCalledWith('2026-11-15');
  });

  it('allows manual refresh via refresh()', async () => {
    let currentClock = new Date('2026-11-15T17:00:00Z');
    const getNow = () => currentClock;

    const { result } = await renderHook(() =>
      usePickupSchedule({
        selectedDate: '2026-11-15',
        refreshIntervalMs: 0, // disable periodic timer
        getNow,
      }),
    );

    expect(result.current.times).toContain('12:30 PM');

    currentClock = new Date('2026-11-15T17:40:00Z');
    await act(async () => {
      result.current.refresh();
    });

    expect(result.current.times).not.toContain('12:30 PM');
    expect(result.current.times).toContain('1:00 PM');
  });

  it('removes asap when restaurant closes for the day', async () => {
    // Sunday closing is 6:00 PM (23:00 UTC)
    let currentClock = new Date('2026-11-15T22:50:00Z'); // 5:50 PM
    const getNow = () => currentClock;
    const onInvalidateTime = jest.fn();

    const { result } = await renderHook(() =>
      usePickupSchedule({
        selectedDate: '2026-11-15',
        selectedTime: 'asap',
        getNow,
        onInvalidateTime,
      }),
    );

    expect(result.current.times).toContain('asap');
    expect(onInvalidateTime).not.toHaveBeenCalled();

    // Advance to 6:05 PM (23:05 UTC)
    currentClock = new Date('2026-11-15T23:05:00Z');
    await act(async () => {
      jest.advanceTimersByTime(30_000);
    });

    expect(result.current.times).not.toContain('asap');
    expect(onInvalidateTime).toHaveBeenCalledWith('asap');
  });

  it('does not expire future date slots when time passes today', async () => {
    // Sunday 12:00 PM, user selected Monday
    let currentClock = new Date('2026-11-15T17:00:00Z');
    const getNow = () => currentClock;
    const onInvalidateTime = jest.fn();

    const { result } = await renderHook(() =>
      usePickupSchedule({
        selectedDate: '2026-11-16',
        selectedTime: '11:30 AM',
        getNow,
        onInvalidateTime,
      }),
    );

    expect(result.current.times).toContain('11:30 AM');

    // Advance by 3 hours today
    currentClock = new Date('2026-11-15T20:00:00Z');
    await act(async () => {
      jest.advanceTimersByTime(30_000);
    });

    // Tomorrow's 11:30 AM slot is still valid
    expect(result.current.times).toContain('11:30 AM');
    expect(onInvalidateTime).not.toHaveBeenCalled();
  });
});

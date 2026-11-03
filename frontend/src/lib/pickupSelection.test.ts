import { describe, it, expect, beforeEach } from 'vitest';
import {
  generatePickupTimeSlots,
  parseTimeSlotToMinutes,
  isPickupDateValid,
  isPickupTimeValid,
  isTimeSlotInPast,
  loadPickupSelections,
  savePickupDate,
  savePickupTime,
  clearPickupSelections,
  PICKUP_DATE_STORAGE_KEY,
  PICKUP_TIME_STORAGE_KEY,
} from './pickupSelection';

class MockStorage {
  private store = new Map<string, string>();

  getItem(key: string): string | null {
    return this.store.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.store.set(key, value);
  }

  removeItem(key: string): void {
    this.store.delete(key);
  }

  clear(): void {
    this.store.clear();
  }
}

describe('pickupSelection', () => {
  let mockStorage: MockStorage;

  beforeEach(() => {
    mockStorage = new MockStorage();
  });

  // Reference date: 2026-11-02 12:30 PM in Toronto (EST)
  // UTC: 2026-11-02 17:30:00Z
  const refDateNoon = new Date('2026-11-02T17:30:00Z');
  // Reference date: 2026-11-02 9:30 PM in Toronto (EST) -> after 21:00
  // UTC: 2026-11-03 02:30:00Z
  const refDateLate = new Date('2026-11-03T02:30:00Z');

  describe('generatePickupTimeSlots', () => {
    it('generates 30-min intervals starting at 11:00 AM and ending at 9:00 PM', () => {
      const slots = generatePickupTimeSlots();
      expect(slots[0]).toBe('11:00 AM');
      expect(slots[1]).toBe('11:30 AM');
      expect(slots[slots.length - 1]).toBe('9:00 PM');
      expect(slots).toContain('12:00 PM');
      expect(slots).toContain('1:30 PM');
      expect(slots.length).toBe(21);
    });
  });

  describe('parseTimeSlotToMinutes', () => {
    it('parses 12-hour times correctly', () => {
      expect(parseTimeSlotToMinutes('11:00 AM')).toBe(660);
      expect(parseTimeSlotToMinutes('11:30 AM')).toBe(690);
      expect(parseTimeSlotToMinutes('12:00 PM')).toBe(720);
      expect(parseTimeSlotToMinutes('12:30 PM')).toBe(750);
      expect(parseTimeSlotToMinutes('1:00 PM')).toBe(780);
      expect(parseTimeSlotToMinutes('9:00 PM')).toBe(1260);
    });

    it('returns null for invalid time slots', () => {
      expect(parseTimeSlotToMinutes('asap')).toBeNull();
      expect(parseTimeSlotToMinutes('invalid')).toBeNull();
      expect(parseTimeSlotToMinutes('')).toBeNull();
      expect(parseTimeSlotToMinutes(null)).toBeNull();
    });
  });

  describe('isPickupDateValid', () => {
    it('accepts today and future dates within 90 days', () => {
      expect(isPickupDateValid('2026-11-02', refDateNoon)).toBe(true);
      expect(isPickupDateValid('2026-11-03', refDateNoon)).toBe(true);
      expect(isPickupDateValid('2026-12-01', refDateNoon)).toBe(true);
    });

    it('rejects past dates', () => {
      expect(isPickupDateValid('2026-11-01', refDateNoon)).toBe(false);
      expect(isPickupDateValid('2025-10-10', refDateNoon)).toBe(false);
    });

    it('rejects dates beyond 90 days', () => {
      expect(isPickupDateValid('2027-05-01', refDateNoon)).toBe(false);
    });

    it('rejects malformed or impossible dates', () => {
      expect(isPickupDateValid('not-a-date', refDateNoon)).toBe(false);
      expect(isPickupDateValid('2026-02-31', refDateNoon)).toBe(false);
      expect(isPickupDateValid('', refDateNoon)).toBe(false);
      expect(isPickupDateValid(null, refDateNoon)).toBe(false);
    });
  });

  describe('isPickupTimeValid', () => {
    it('accepts asap today during operating hours', () => {
      expect(isPickupTimeValid('2026-11-02', 'asap', refDateNoon)).toBe(true);
    });

    it('rejects asap today after closing time (9:00 PM)', () => {
      expect(isPickupTimeValid('2026-11-02', 'asap', refDateLate)).toBe(false);
    });

    it('accepts future time slots today', () => {
      // At 12:30 PM, 1:00 PM and later are valid
      expect(isPickupTimeValid('2026-11-02', '1:00 PM', refDateNoon)).toBe(true);
      expect(isPickupTimeValid('2026-11-02', '5:30 PM', refDateNoon)).toBe(true);
    });

    it('rejects past time slots today', () => {
      // At 12:30 PM, 11:30 AM is in the past
      expect(isPickupTimeValid('2026-11-02', '11:30 AM', refDateNoon)).toBe(false);
      expect(isPickupTimeValid('2026-11-02', '12:00 PM', refDateNoon)).toBe(false);
    });

    it('accepts any valid time slot for tomorrow', () => {
      expect(isPickupTimeValid('2026-11-03', '11:00 AM', refDateNoon)).toBe(true);
      expect(isPickupTimeValid('2026-11-03', '11:30 AM', refDateNoon)).toBe(true);
      expect(isPickupTimeValid('2026-11-03', '8:30 PM', refDateNoon)).toBe(true);
    });

    it('rejects out of bounds or non-30-minute intervals', () => {
      expect(isPickupTimeValid('2026-11-03', '10:30 AM', refDateNoon)).toBe(false);
      expect(isPickupTimeValid('2026-11-03', '9:30 PM', refDateNoon)).toBe(false);
      expect(isPickupTimeValid('2026-11-03', '1:15 PM', refDateNoon)).toBe(false);
      expect(isPickupTimeValid('2026-11-03', 'garbage', refDateNoon)).toBe(false);
    });
  });

  describe('isTimeSlotInPast', () => {
    it('correctly flags past slots for today', () => {
      expect(isTimeSlotInPast('2026-11-02', '11:00 AM', refDateNoon)).toBe(true);
      expect(isTimeSlotInPast('2026-11-02', '12:00 PM', refDateNoon)).toBe(true);
      expect(isTimeSlotInPast('2026-11-02', '1:00 PM', refDateNoon)).toBe(false);
      expect(isTimeSlotInPast('2026-11-02', 'asap', refDateNoon)).toBe(false);
      expect(isTimeSlotInPast('2026-11-02', 'asap', refDateLate)).toBe(true);
    });

    it('returns false for future dates', () => {
      expect(isTimeSlotInPast('2026-11-03', '11:00 AM', refDateNoon)).toBe(false);
    });
  });

  describe('loadPickupSelections', () => {
    it('preserves valid date and time from storage', () => {
      mockStorage.setItem(PICKUP_DATE_STORAGE_KEY, '2026-11-02');
      mockStorage.setItem(PICKUP_TIME_STORAGE_KEY, '2:00 PM');

      const result = loadPickupSelections(mockStorage, refDateNoon);
      expect(result.pickupDate).toBe('2026-11-02');
      expect(result.pickupTime).toBe('2:00 PM');
    });

    it('clears past date and time from storage and returns empty', () => {
      mockStorage.setItem(PICKUP_DATE_STORAGE_KEY, '2026-11-01'); // yesterday
      mockStorage.setItem(PICKUP_TIME_STORAGE_KEY, '2:00 PM');

      const result = loadPickupSelections(mockStorage, refDateNoon);
      expect(result.pickupDate).toBe('');
      expect(result.pickupTime).toBe('');
      expect(mockStorage.getItem(PICKUP_DATE_STORAGE_KEY)).toBeNull();
      expect(mockStorage.getItem(PICKUP_TIME_STORAGE_KEY)).toBeNull();
    });

    it('preserves valid date when time has expired in the past, resetting only the expired time', () => {
      mockStorage.setItem(PICKUP_DATE_STORAGE_KEY, '2026-11-02');
      mockStorage.setItem(PICKUP_TIME_STORAGE_KEY, '11:30 AM'); // past (current is 12:30 PM)

      const result = loadPickupSelections(mockStorage, refDateNoon);
      expect(result.pickupDate).toBe('2026-11-02');
      expect(result.pickupTime).toBe('');
      // Storage should keep valid date, but remove expired time
      expect(mockStorage.getItem(PICKUP_DATE_STORAGE_KEY)).toBe('2026-11-02');
      expect(mockStorage.getItem(PICKUP_TIME_STORAGE_KEY)).toBeNull();
    });

    it('clears corrupted entries', () => {
      mockStorage.setItem(PICKUP_DATE_STORAGE_KEY, 'bad-date');
      mockStorage.setItem(PICKUP_TIME_STORAGE_KEY, 'bad-time');

      const result = loadPickupSelections(mockStorage, refDateNoon);
      expect(result.pickupDate).toBe('');
      expect(result.pickupTime).toBe('');
      expect(mockStorage.getItem(PICKUP_DATE_STORAGE_KEY)).toBeNull();
      expect(mockStorage.getItem(PICKUP_TIME_STORAGE_KEY)).toBeNull();
    });
  });

  describe('savePickupDate and savePickupTime', () => {
    it('saves non-empty values and removes when empty', () => {
      savePickupDate('2026-11-05', mockStorage);
      expect(mockStorage.getItem(PICKUP_DATE_STORAGE_KEY)).toBe('2026-11-05');

      savePickupDate('', mockStorage);
      expect(mockStorage.getItem(PICKUP_DATE_STORAGE_KEY)).toBeNull();

      savePickupTime('3:30 PM', mockStorage);
      expect(mockStorage.getItem(PICKUP_TIME_STORAGE_KEY)).toBe('3:30 PM');

      savePickupTime('', mockStorage);
      expect(mockStorage.getItem(PICKUP_TIME_STORAGE_KEY)).toBeNull();
    });
  });

  describe('clearPickupSelections', () => {
    it('removes both keys from storage', () => {
      mockStorage.setItem(PICKUP_DATE_STORAGE_KEY, '2026-11-05');
      mockStorage.setItem(PICKUP_TIME_STORAGE_KEY, '3:30 PM');

      clearPickupSelections(mockStorage);
      expect(mockStorage.getItem(PICKUP_DATE_STORAGE_KEY)).toBeNull();
      expect(mockStorage.getItem(PICKUP_TIME_STORAGE_KEY)).toBeNull();
    });
  });
});

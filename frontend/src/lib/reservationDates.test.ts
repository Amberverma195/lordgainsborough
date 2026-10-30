import { describe, it, expect } from 'vitest';
import {
  getCalendarDayOfWeek,
  getRestaurantTodayDateString,
  getRestaurantNowParts,
  getOperatingHours,
  getAvailableTimesForDate,
  validateReservationDateTime,
} from './reservationDates';

describe('reservationDates utility', () => {
  describe('getCalendarDayOfWeek', () => {
    it('correctly identifies Sunday without UTC shift', () => {
      // 2026-11-01 is Sunday
      expect(getCalendarDayOfWeek('2026-11-01')).toBe(0);
    });

    it('correctly identifies Monday without UTC shift', () => {
      // 2026-11-02 is Monday
      expect(getCalendarDayOfWeek('2026-11-02')).toBe(1);
    });

    it('correctly identifies Friday and Saturday without UTC shift', () => {
      // 2026-11-06 is Friday, 2026-11-07 is Saturday
      expect(getCalendarDayOfWeek('2026-11-06')).toBe(5);
      expect(getCalendarDayOfWeek('2026-11-07')).toBe(6);
    });

    it('returns -1 for invalid or empty date strings', () => {
      expect(getCalendarDayOfWeek('')).toBe(-1);
      expect(getCalendarDayOfWeek('invalid')).toBe(-1);
      expect(getCalendarDayOfWeek('2026-13-45')).toBe(-1);
    });
  });

  describe('getOperatingHours', () => {
    it('returns Sunday hours: 10:00 to 18:00 (10 AM - 6 PM)', () => {
      const hours = getOperatingHours(0);
      expect(hours).toEqual({
        startHour: 10,
        startMinutes: 0,
        endHour: 18,
        endMinutes: 0,
      });
    });

    it('returns Friday and Saturday hours: 09:00 to 20:30 (9 AM - 8:30 PM)', () => {
      expect(getOperatingHours(5)).toEqual({
        startHour: 9,
        startMinutes: 0,
        endHour: 20,
        endMinutes: 30,
      });
      expect(getOperatingHours(6)).toEqual({
        startHour: 9,
        startMinutes: 0,
        endHour: 20,
        endMinutes: 30,
      });
    });

    it('returns Mon-Thu hours: 09:00 to 20:00 (9 AM - 8 PM)', () => {
      for (const day of [1, 2, 3, 4]) {
        expect(getOperatingHours(day)).toEqual({
          startHour: 9,
          startMinutes: 0,
          endHour: 20,
          endMinutes: 0,
        });
      }
    });
  });

  describe('Canadian timezone date handling', () => {
    it('uses London, Ontario local calendar date when UTC has rolled over to tomorrow', () => {
      // At 8:30 PM EDT (UTC-4) on Oct 30, UTC is 00:30 on Oct 31
      const eveningInOntario = new Date('2026-10-31T00:30:00Z');
      expect(getRestaurantTodayDateString(eveningInOntario)).toBe('2026-10-30');
    });

    it('extracts local hour and minute for London, Ontario', () => {
      // 2026-10-30T19:45:00-04:00 (7:45 PM EDT) = 2026-10-30T23:45:00Z
      const dateInOntario = new Date('2026-10-30T23:45:00Z');
      const parts = getRestaurantNowParts(dateInOntario);
      expect(parts.dateString).toBe('2026-10-30');
      expect(parts.hour).toBe(19);
      expect(parts.minute).toBe(45);
    });
  });

  describe('getAvailableTimesForDate', () => {
    it('generates Sunday schedule: starts at 10:00 AM, ends at 6:00 PM', () => {
      // Future Sunday: 2026-11-15
      const times = getAvailableTimesForDate('2026-11-15', new Date('2026-11-01T12:00:00Z'));
      expect(times.length).toBeGreaterThan(0);
      expect(times[0].value).toBe('10:00');
      expect(times[0].label).toBe('10:00 AM');
      expect(times[times.length - 1].value).toBe('18:00');
      expect(times[times.length - 1].label).toBe('6:00 PM');

      // Does not contain 9:00 AM or 8:00 PM
      expect(times.some(t => t.value === '09:00')).toBe(false);
      expect(times.some(t => t.value === '20:00')).toBe(false);
    });

    it('generates Monday schedule: starts at 9:00 AM, ends at 8:00 PM', () => {
      // Future Monday: 2026-11-16
      const times = getAvailableTimesForDate('2026-11-16', new Date('2026-11-01T12:00:00Z'));
      expect(times[0].value).toBe('09:00');
      expect(times[0].label).toBe('9:00 AM');
      expect(times[times.length - 1].value).toBe('20:00');
      expect(times[times.length - 1].label).toBe('8:00 PM');

      // Does not contain 8:30 PM (which is only Fri/Sat)
      expect(times.some(t => t.value === '20:30')).toBe(false);
    });

    it('generates Friday schedule: includes 8:30 PM', () => {
      // Future Friday: 2026-11-20
      const times = getAvailableTimesForDate('2026-11-20', new Date('2026-11-01T12:00:00Z'));
      expect(times[times.length - 1].value).toBe('20:30');
      expect(times[times.length - 1].label).toBe('8:30 PM');
    });

    it('filters out past time slots when booking for today', () => {
      // Suppose local time in Ontario is 2:15 PM (14:15) on Monday 2026-11-02
      // 14:15 EST (UTC-5) = 19:15 UTC
      const current = new Date('2026-11-02T19:15:00Z');
      const times = getAvailableTimesForDate('2026-11-02', current);

      // Should not have slots at or before 14:15
      expect(times.some(t => t.value === '14:00')).toBe(false);
      expect(times.some(t => t.value === '13:30')).toBe(false);
      // Next available slot should be 14:30 (2:30 PM)
      expect(times[0].value).toBe('14:30');
      expect(times[0].label).toBe('2:30 PM');
    });
  });

  describe('validateReservationDateTime', () => {
    const fixedNow = new Date('2026-11-02T15:00:00Z'); // 10:00 AM EST on Monday 2026-11-02

    it('rejects past dates', () => {
      const result = validateReservationDateTime('2026-11-01', '12:00', fixedNow);
      expect(result.valid).toBe(false);
      expect(result.reason).toContain('cannot be in the past');
    });

    it('rejects times outside business hours for the specific day of week', () => {
      // Sunday 2026-11-08 at 7:00 PM (19:00) -> closed on Sundays at 6:00 PM
      const result = validateReservationDateTime('2026-11-08', '19:00', fixedNow);
      expect(result.valid).toBe(false);
      expect(result.reason).toContain('not available on Sunday');
    });

    it('accepts valid times during open hours', () => {
      const result = validateReservationDateTime('2026-11-08', '14:00', fixedNow);
      expect(result.valid).toBe(true);
    });
  });
});

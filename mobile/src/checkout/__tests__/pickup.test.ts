import {
  generatePickupTimeSlots,
  isAsapAllowed,
  isPickupDateValid,
  isPickupTimeValid,
  restaurantNow,
} from '../pickup';

const noonToronto = new Date('2026-11-15T17:00:00Z');
const afterCloseToronto = new Date('2026-11-16T03:30:00Z');

describe('pickup scheduling', () => {
  it('uses the America/Toronto calendar, including late evening UTC dates', () => {
    expect(restaurantNow(noonToronto)).toEqual({ date: '2026-11-15', minutes: 12 * 60 });
    expect(restaurantNow(afterCloseToronto).date).toBe('2026-11-15');
    expect(restaurantNow(afterCloseToronto).minutes).toBeGreaterThan(21 * 60);
  });

  it('offers 11:00 AM through 9:00 PM on 30-minute marks', () => {
    const slots = generatePickupTimeSlots();
    expect(slots[0]).toBe('11:00 AM');
    expect(slots).toContain('12:00 PM');
    expect(slots.at(-1)).toBe('9:00 PM');
    expect(slots).not.toContain('9:30 PM');
    expect(slots).not.toContain('10:30 AM');
  });

  it('rejects past dates, impossible dates, and dates beyond 90 days', () => {
    expect(isPickupDateValid('2026-11-14', noonToronto)).toBe(false);
    expect(isPickupDateValid('2026-02-31', noonToronto)).toBe(false);
    expect(isPickupDateValid('2026-11-15', noonToronto)).toBe(true);
    expect(isPickupDateValid('2027-02-13', noonToronto)).toBe(true);
    expect(isPickupDateValid('2027-02-14', noonToronto)).toBe(false);
  });

  it('allows as soon as possible until 9:00 PM and rejects earlier slots today', () => {
    expect(isAsapAllowed('2026-11-15', noonToronto)).toBe(true);
    expect(isAsapAllowed('2026-11-15', afterCloseToronto)).toBe(false);
    expect(isPickupTimeValid('2026-11-15', '11:30 AM', noonToronto)).toBe(false);
    expect(isPickupTimeValid('2026-11-15', '12:30 PM', noonToronto)).toBe(true);
    expect(isPickupTimeValid('2026-11-16', '11:00 AM', noonToronto)).toBe(true);
  });
});

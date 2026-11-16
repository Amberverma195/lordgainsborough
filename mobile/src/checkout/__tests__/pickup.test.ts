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

  it('offers 11:00 AM through schedule closing on 30-minute marks', () => {
    const defaultSlots = generatePickupTimeSlots();
    expect(defaultSlots[0]).toBe('11:00 AM');
    expect(defaultSlots).toContain('12:00 PM');
    expect(defaultSlots.at(-1)).toBe('8:30 PM');
    expect(defaultSlots).not.toContain('9:00 PM');
    expect(defaultSlots).not.toContain('10:30 AM');

    // Sunday closes at 6:00 PM
    const sundaySlots = generatePickupTimeSlots('2026-11-15');
    expect(sundaySlots.at(-1)).toBe('6:00 PM');
    expect(sundaySlots).not.toContain('6:30 PM');

    // Friday closes at 8:30 PM
    const fridaySlots = generatePickupTimeSlots('2026-11-20');
    expect(fridaySlots.at(-1)).toBe('8:30 PM');
    expect(fridaySlots).not.toContain('9:00 PM');

    // Monday closes at 8:00 PM
    const mondaySlots = generatePickupTimeSlots('2026-11-16');
    expect(mondaySlots.at(-1)).toBe('8:00 PM');
    expect(mondaySlots).not.toContain('8:30 PM');
  });

  it('rejects past dates, impossible dates, and dates beyond 90 days', () => {
    expect(isPickupDateValid('2026-11-14', noonToronto)).toBe(false);
    expect(isPickupDateValid('2026-02-31', noonToronto)).toBe(false);
    expect(isPickupDateValid('2026-11-15', noonToronto)).toBe(true);
    expect(isPickupDateValid('2027-02-13', noonToronto)).toBe(true);
    expect(isPickupDateValid('2027-02-14', noonToronto)).toBe(false);
  });

  it('allows as soon as possible until daily closing and validates day-specific slots', () => {
    // Sunday 2026-11-15 at noon
    expect(isAsapAllowed('2026-11-15', noonToronto)).toBe(true);
    expect(isAsapAllowed('2026-11-15', afterCloseToronto)).toBe(false);
    expect(isPickupTimeValid('2026-11-15', '11:30 AM', noonToronto)).toBe(false);
    expect(isPickupTimeValid('2026-11-15', '12:30 PM', noonToronto)).toBe(true);
    expect(isPickupTimeValid('2026-11-15', '6:00 PM', noonToronto)).toBe(true);
    expect(isPickupTimeValid('2026-11-15', '6:30 PM', noonToronto)).toBe(false);

    // Monday 2026-11-16 (closes at 8:00 PM)
    expect(isPickupTimeValid('2026-11-16', '11:00 AM', noonToronto)).toBe(true);
    expect(isPickupTimeValid('2026-11-16', '8:00 PM', noonToronto)).toBe(true);
    expect(isPickupTimeValid('2026-11-16', '8:30 PM', noonToronto)).toBe(false);

    // Friday 2026-11-20 (closes at 8:30 PM)
    expect(isPickupTimeValid('2026-11-20', '8:30 PM', noonToronto)).toBe(true);
    expect(isPickupTimeValid('2026-11-20', '9:00 PM', noonToronto)).toBe(false);
  });
});

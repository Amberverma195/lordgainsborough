import {
  reservationDates,
  reservationTimes,
  reservationKeys,
} from '../choices';
it('isolates guest and account records without namespace collisions', () => {
  expect(reservationKeys('a@example.com')).not.toEqual(
    reservationKeys('b@example.com'),
  );
  expect(reservationKeys(null)).not.toEqual(reservationKeys('a@example.com'));
  expect(reservationKeys('a+b@example.com')).not.toEqual(
    reservationKeys('a_b@example.com'),
  );
});
it('offers Toronto dates and upcoming half-hour slots excluding closing', () => {
  const now = new Date('2026-11-22T00:45:00Z');
  expect(reservationDates(now)[0]).toBe('2026-11-21');
  expect(reservationTimes('2026-11-21', now)).toEqual(['20:00']);
  expect(reservationTimes('2026-11-22', now)).not.toContain('18:00');
  expect(reservationTimes('2026-11-23', now)).not.toContain('20:00');
  expect(reservationTimes('2026-11-21', now)).not.toContain('20:30');
  expect(reservationTimes('2026-11-20', now)).toEqual([]);
});

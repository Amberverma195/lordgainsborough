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
  expect(reservationTimes('2026-11-21', now)).toEqual([]);
  expect(reservationTimes('2026-11-22', now)).not.toContain('18:00');
  expect(reservationTimes('2026-11-23', now)).not.toContain('20:00');
  expect(reservationTimes('2026-11-21', now)).not.toContain('20:30');
  expect(reservationTimes('2026-11-20', now)).toEqual([]);
});

it('does not enforce unapproved seating policy rules on customer choices', () => {
  const now = new Date('2026-11-22T14:00:00Z'); // 09:00 Toronto time on Sunday 2026-11-22 (closes at 18:00)
  const unapprovedPolicy = { lastSeatingMinutes: 60, advanceNoticeMinutes: 30, managementApproved: false };
  const times = reservationTimes('2026-11-22', now, unapprovedPolicy);
  // Without approved policy, 17:30 (30m before 18:00) is allowed; 18:00 closing is excluded
  expect(times).toContain('17:30');
  expect(times).not.toContain('18:00');
});

it('enforces seating buffer and advance notice when policy is approved by management', () => {
  const now = new Date('2026-11-22T14:00:00Z'); // 09:00 Toronto time on Sunday 2026-11-22 (closes at 18:00)
  const approvedPolicy = { lastSeatingMinutes: 60, advanceNoticeMinutes: 30, managementApproved: true };
  const times = reservationTimes('2026-11-22', now, approvedPolicy);
  // With approved 60m cutoff, last seating is 17:00 (17:30 is excluded)
  expect(times).toContain('17:00');
  expect(times).not.toContain('17:30');
  expect(times).not.toContain('18:00');
});

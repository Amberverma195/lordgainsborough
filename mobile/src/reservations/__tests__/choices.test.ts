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
  const afterClose = new Date('2026-11-22T02:00:00Z'); // 21:00 on Saturday 2026-11-21 (after 20:30 close)
  expect(reservationDates(afterClose)[0]).toBe('2026-11-21');
  expect(reservationTimes('2026-11-21', afterClose)).toEqual([]);
  const sundayMorning = new Date('2026-11-22T14:00:00Z');
  expect(reservationTimes('2026-11-22', sundayMorning)).not.toContain('18:00');
  expect(reservationTimes('2026-11-23', sundayMorning)).not.toContain('20:00');
  expect(reservationTimes('2026-11-28', sundayMorning)).not.toContain('20:30');
  expect(reservationTimes('2026-11-20', sundayMorning)).toEqual([]);
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

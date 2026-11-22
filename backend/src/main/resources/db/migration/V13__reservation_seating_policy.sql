CREATE TABLE reservation_seating_policy (
 id VARCHAR(16) PRIMARY KEY CHECK(id='default'),
 last_seating_minutes INTEGER NOT NULL CHECK(last_seating_minutes BETWEEN 1 AND 480),
 advance_notice_minutes INTEGER NOT NULL CHECK(advance_notice_minutes BETWEEN 1 AND 10080),
 management_approved BOOLEAN NOT NULL DEFAULT FALSE
);
-- Proposed defaults supplied by the owner, pending restaurant management approval.
INSERT INTO reservation_seating_policy(id,last_seating_minutes,advance_notice_minutes,management_approved) VALUES ('default',60,30,FALSE);

-- DEV/STAGE ONLY. The administrator's pass change history (#85).
-- 1. Links the older seeds' suspension entries to their suspensions (pass_ledger.suspension_id, V29),
--    so the history shows their suspended days instead of a plain extension.
-- 2. Adds one pass of Katarzyna with every kind of change, used up, so it is not offered for booking.
-- IDs 29001..29020 do not overlap the other seeds. Insert-only, apart from filling in missing links.
CREATE OR REPLACE FUNCTION pg_temp.seed_history_day(day_offset INT)
RETURNS DATE LANGUAGE sql AS $$
    SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + day_offset
$$;

CREATE OR REPLACE FUNCTION pg_temp.seed_history_at(day_offset INT, local_time TIME)
RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT (pg_temp.seed_history_day(day_offset) + local_time) AT TIME ZONE 'Europe/Warsaw'
$$;

UPDATE pass_ledger SET suspension_id = '29000000-0000-0000-0000-000000000001'
WHERE id = '34000000-0000-0000-0000-000000000015' AND suspension_id IS NULL
  AND EXISTS (SELECT 1 FROM pass_suspension WHERE id = '29000000-0000-0000-0000-000000000001');

UPDATE pass_ledger SET suspension_id = '29000000-0000-0000-0000-000000010001'
WHERE id = '34000000-0000-0000-0000-000000010006' AND suspension_id IS NULL
  AND EXISTS (SELECT 1 FROM pass_suspension WHERE id = '29000000-0000-0000-0000-000000010001');

-- Katarzyna's 4-entry Pole Dance pass: assigned by Beata 30 days ago, all entries used, renewal declined.
INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by, created_at, renewal_declined_at) VALUES
    ('28000000-0000-0000-0000-000000029001', '07000000-0000-0000-0000-000000000001', '14000000-0000-0000-0000-000000000001',
     'Karnet Pole Dance — 4 wejścia (historia zmian)', 4, 0, 180.00, 'ACTIVE',
     pg_temp.seed_history_day(-30), pg_temp.seed_history_day(10),
     '03000000-0000-0000-0000-000000000010', pg_temp.seed_history_at(-30, '09:00'), pg_temp.seed_history_at(-1, '20:15'))
ON CONFLICT DO NOTHING;

WITH slots (slot, day_offset, hour, title, status) AS (VALUES
    (29001, -27, TIME '18:00', 'Pole dance — podstawy (historia karnetu)', 'COMPLETED'),
    (29002, -20, TIME '18:00', 'Pole dance — figury (historia karnetu)', 'COMPLETED'),
    (29003, -16, TIME '17:00', 'Pole dance — odrabianie (historia karnetu)', 'COMPLETED'),
    (29004, -13, TIME '18:00', 'Pole dance — figury (historia karnetu)', 'COMPLETED'),
    (29005,  -3, TIME '18:00', 'Pole dance — podstawy (historia karnetu)', 'COMPLETED'),
    (29006,   2, TIME '18:00', 'Pole dance — kombinacje (historia karnetu)', 'SCHEDULED')
)
INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at, capacity, visibility, status, created_by)
SELECT ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, 'CLASS',
       '10000000-0000-0000-0000-000000000001', title, pg_temp.seed_history_at(day_offset, hour),
       pg_temp.seed_history_at(day_offset, hour) + INTERVAL '1 hour', 8, 'PUBLIC', status,
       '03000000-0000-0000-0000-000000000010'
FROM slots
ON CONFLICT DO NOTHING;

INSERT INTO event_staff (event_id, staff_id, role)
SELECT id, '08000000-0000-0000-0000-000000000003', 'LEAD'
FROM schedule_event event
WHERE id BETWEEN '19000000-0000-0000-0000-000000029001'::UUID AND '19000000-0000-0000-0000-000000029006'::UUID
  AND NOT EXISTS (SELECT 1 FROM event_staff assigned WHERE assigned.event_id = event.id)
ON CONFLICT DO NOTHING;

INSERT INTO event_resource (event_id, resource_id, blocks_whole_room)
SELECT id, '15000000-0000-0000-0000-000000000001', FALSE
FROM schedule_event event
WHERE id BETWEEN '19000000-0000-0000-0000-000000029001'::UUID AND '19000000-0000-0000-0000-000000029006'::UUID
  AND NOT EXISTS (SELECT 1 FROM event_resource assigned WHERE assigned.event_id = event.id)
ON CONFLICT DO NOTHING;

-- 29001 attended; 29002 cancelled in time, the entry stays used and a make-up credit is granted;
-- 29003 the make-up class, paid with that credit; 29004 booked by Beata after a phone call, absent;
-- 29005 cancelled too late; 29006 upcoming.
WITH bookings (slot, booked_by, source, pass_no, booked_day, booked_time, cancelled_day, cancelled_time, cancel_kind, attendance) AS (VALUES
    (29001, '03000000-0000-0000-0000-000000000004', 'SELF',  29001, -29, TIME '20:00', NULL, NULL,          NULL,      'PRESENT'),
    (29002, '03000000-0000-0000-0000-000000000004', 'SELF',  29001, -23, TIME '10:00', -21,  TIME '09:00', 'IN_TIME', 'UNMARKED'),
    (29003, '03000000-0000-0000-0000-000000000004', 'SELF',  NULL,  -18, TIME '12:00', NULL, NULL,          NULL,      'PRESENT'),
    (29004, '03000000-0000-0000-0000-000000000010', 'STAFF', 29001, -15, TIME '14:00', NULL, NULL,          NULL,      'ABSENT'),
    (29005, '03000000-0000-0000-0000-000000000004', 'SELF',  29001, -4,  TIME '19:30', -3,   TIME '15:00', 'LATE',    'UNMARKED'),
    (29006, '03000000-0000-0000-0000-000000000004', 'SELF',  29001, -2,  TIME '08:45', NULL, NULL,          NULL,      'UNMARKED')
)
INSERT INTO reservation (id, event_id, customer_id, booked_by, source, status, customer_pass_id, booking_policy_id,
                         free_cancel_until, cancelled_at, cancelled_by, cancel_kind, attendance, attendance_marked_by,
                         attendance_marked_at, created_at)
SELECT ('30000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, event.id,
       '07000000-0000-0000-0000-000000000001', booked_by::UUID, source,
       CASE WHEN cancel_kind IS NULL THEN 'ACTIVE' ELSE 'CANCELLED' END,
       CASE WHEN pass_no IS NULL THEN NULL ELSE ('28000000-0000-0000-0000-' || lpad(pass_no::TEXT, 12, '0'))::UUID END,
       '11000000-0000-0000-0000-000000000001', event.starts_at - INTERVAL '12 hours',
       CASE WHEN cancel_kind IS NULL THEN NULL ELSE pg_temp.seed_history_at(cancelled_day, cancelled_time) END,
       CASE WHEN cancel_kind IS NULL THEN NULL ELSE '03000000-0000-0000-0000-000000000004'::UUID END,
       cancel_kind, attendance,
       CASE WHEN attendance = 'UNMARKED' THEN NULL ELSE '03000000-0000-0000-0000-000000000003'::UUID END,
       CASE WHEN attendance = 'UNMARKED' THEN NULL ELSE event.ends_at END,
       pg_temp.seed_history_at(booked_day, booked_time)
FROM bookings
JOIN schedule_event event ON event.id = ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID
ON CONFLICT DO NOTHING;

INSERT INTO makeup_credit (id, customer_id, source_pass_id, source_reservation_id, granted_by, granted_at, expires_at,
                           status, used_reservation_id, used_at)
SELECT '33000000-0000-0000-0000-000000029001', '07000000-0000-0000-0000-000000000001',
       '28000000-0000-0000-0000-000000029001', '30000000-0000-0000-0000-000000029002',
       '03000000-0000-0000-0000-000000000004', pg_temp.seed_history_at(-21, '09:00'),
       pg_temp.seed_history_at(-21, '09:00') + INTERVAL '30 days', 'USED',
       '30000000-0000-0000-0000-000000029003', pg_temp.seed_history_at(-18, '12:00')
WHERE EXISTS (SELECT 1 FROM reservation WHERE id = '30000000-0000-0000-0000-000000029003')
ON CONFLICT DO NOTHING;

UPDATE reservation SET makeup_credit_id = '33000000-0000-0000-0000-000000029001'
WHERE id = '30000000-0000-0000-0000-000000029003' AND makeup_credit_id IS NULL
  AND EXISTS (SELECT 1 FROM makeup_credit WHERE id = '33000000-0000-0000-0000-000000029001');

-- A suspension for a knee injury, cut short after four of its seven days.
INSERT INTO pass_suspension (id, pass_id, starts_on, ends_on, reason, created_by, created_at) VALUES
    ('29000000-0000-0000-0000-000000029001', '28000000-0000-0000-0000-000000029001',
     pg_temp.seed_history_day(-10), pg_temp.seed_history_day(-7), 'Kontuzja kolana',
     '03000000-0000-0000-0000-000000000010', pg_temp.seed_history_at(-10, '09:00'))
ON CONFLICT DO NOTHING;

-- Balance: 4 → 3 → 2 → 1 → +1 correction = 2 → 1 → 0.
WITH entries (slot, kind, delta, booking, before_day, after_day, reason, actor, day_offset, local_time, suspension) AS (VALUES
    (29001, 'ACTIVATION',     4,  NULL,  NULL, -1,   'Płatność kartą w recepcji',                       '03000000-0000-0000-0000-000000000010', -30, TIME '09:00', NULL),
    (29002, 'ENTRY_RESERVED', -1, 29001, NULL, NULL, NULL,                                              '03000000-0000-0000-0000-000000000004', -29, TIME '20:00', NULL),
    (29003, 'ENTRY_CONSUMED', 0,  29001, NULL, NULL, NULL,                                              '03000000-0000-0000-0000-000000000003', -27, TIME '19:00', NULL),
    (29004, 'ENTRY_RESERVED', -1, 29002, NULL, NULL, NULL,                                              '03000000-0000-0000-0000-000000000004', -23, TIME '10:00', NULL),
    (29005, 'ENTRY_CONSUMED', 0,  29002, NULL, NULL, NULL,                                              '03000000-0000-0000-0000-000000000004', -21, TIME '09:00', NULL),
    (29006, 'ENTRY_RESERVED', -1, 29004, NULL, NULL, NULL,                                              '03000000-0000-0000-0000-000000000010', -15, TIME '14:00', NULL),
    (29007, 'ENTRY_CONSUMED', 0,  29004, NULL, NULL, NULL,                                              '03000000-0000-0000-0000-000000000003', -13, TIME '19:00', NULL),
    (29008, 'CORRECTION',     1,  NULL,  NULL, NULL, 'Nieobecność z powodu choroby, zwrot uzgodniony z klientką', '03000000-0000-0000-0000-000000000010', -12, TIME '10:00', NULL),
    (29009, 'EXTENSION',      0,  NULL,  -1,   6,    'Kontuzja kolana',                                 '03000000-0000-0000-0000-000000000010', -10, TIME '09:00', 29001),
    (29010, 'EXTENSION',      0,  NULL,  6,    3,    'Suspension cancelled',                            '03000000-0000-0000-0000-000000000010', -6,  TIME '11:00', 29001),
    (29011, 'EXTENSION',      0,  NULL,  3,    10,   'Wydłużenie za odwołane zajęcia w listopadzie',    '03000000-0000-0000-0000-000000000010', -5,  TIME '16:00', NULL),
    (29012, 'ENTRY_RESERVED', -1, 29005, NULL, NULL, NULL,                                              '03000000-0000-0000-0000-000000000004', -4,  TIME '19:30', NULL),
    (29013, 'ENTRY_CONSUMED', 0,  29005, NULL, NULL, NULL,                                              '03000000-0000-0000-0000-000000000004', -3,  TIME '15:00', NULL),
    (29014, 'ENTRY_RESERVED', -1, 29006, NULL, NULL, NULL,                                              '03000000-0000-0000-0000-000000000004', -2,  TIME '08:45', NULL)
)
INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, valid_until_before, valid_until_after, reservation_id,
                         reason, actor_account_id, occurred_at, suspension_id)
SELECT ('34000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, '28000000-0000-0000-0000-000000029001',
       kind, delta,
       CASE WHEN before_day IS NULL THEN NULL ELSE pg_temp.seed_history_day(before_day) END,
       CASE WHEN after_day IS NULL THEN NULL ELSE pg_temp.seed_history_day(after_day) END,
       CASE WHEN booking IS NULL THEN NULL ELSE ('30000000-0000-0000-0000-' || lpad(booking::TEXT, 12, '0'))::UUID END,
       reason, actor::UUID, pg_temp.seed_history_at(day_offset, local_time),
       CASE WHEN suspension IS NULL THEN NULL ELSE ('29000000-0000-0000-0000-' || lpad(suspension::TEXT, 12, '0'))::UUID END
FROM entries
WHERE EXISTS (SELECT 1 FROM customer_pass WHERE id = '28000000-0000-0000-0000-000000029001')
ON CONFLICT DO NOTHING;

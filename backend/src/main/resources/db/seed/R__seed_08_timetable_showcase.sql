-- DEV/STAGE ONLY. Two complete upcoming weeks for manual mobile layout checks.
-- Additive fixtures: fixed IDs, ON CONFLICT DO NOTHING, no resets of tester changes.
-- Dates are anchored to the next Monday on first application (Europe/Warsaw).
-- Event suffixes 101..126 / 201..226. Uses only existing active client accounts.

-- Complete the known cancellation terms of the original entry-backed fixtures.
-- Limit this to seeded reservations; real historical rows remain unchanged.
UPDATE reservation
SET cancellation_allowed_snapshot = TRUE,
    late_cancel_allowed_snapshot = TRUE,
    late_cancel_forfeits_entry_snapshot = TRUE
WHERE id IN (
    '30000000-0000-0000-0000-000000000002',
    '30000000-0000-0000-0000-000000000003',
    '30000000-0000-0000-0000-000000000005'
)
AND status = 'ACTIVE'
AND cancellation_allowed_snapshot IS NULL
AND late_cancel_allowed_snapshot IS NULL
AND late_cancel_forfeits_entry_snapshot IS NULL;

INSERT INTO service_type (id, name, description, category, level, min_age,
                          default_duration_minutes, min_participants, max_participants, calendar_color, is_active)
VALUES ('10000000-0000-0000-0000-000000000101', 'Stretching', 'Rozciąganie i mobilność całego ciała.',
        'GROUP_CLASS', 'ALL_LEVELS', 16, 45, 3, 8, '#4CAF50', TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO staff_qualification (staff_id, service_type_id) VALUES
    ('08000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000101')
ON CONFLICT DO NOTHING;

INSERT INTO service_type_resource (service_type_id, resource_id, is_default)
VALUES ('10000000-0000-0000-0000-000000000101', '15000000-0000-0000-0000-000000000003', TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO pass_product_service (product_id, service_type_id)
VALUES ('13000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000101')
ON CONFLICT DO NOTHING;

-- Zosia already has the parental consent; add her missing studio terms fixture.
INSERT INTO document_acceptance (id, document_version_id, customer_id, accepted_by, source)
VALUES ('39000000-0000-0000-0000-000000000101', '38000000-0000-0000-0000-000000000002',
        '07000000-0000-0000-0000-000000000004', '03000000-0000-0000-0000-000000000006', 'APP')
ON CONFLICT DO NOTHING;

CREATE TEMP TABLE seed08_slots (
    slot INT, weekday INT, start_time TIME, duration_minutes INT, service_no INT,
    title TEXT, staff_no INT, room_no INT, capacity INT
);
INSERT INTO seed08_slots VALUES
    ( 1, 1, '17:00', 60,   1, 'Pole dance od podstaw',          1, 1, 10),
    ( 2, 1, '17:00', 75,   3, 'Kalistenika — siła i technika',  2, 2, 12),
    ( 3, 1, '17:00', 45, 101, 'Stretching całego ciała',        3, 3,  8),
    ( 4, 1, '18:00', 45,   2, 'Pole dance — kombinacje',        1, 1, 10),
    ( 5, 1, '18:00', 30, 101, 'Mobilność po pracy',             3, 3,  8),
    ( 6, 1, '18:30', 30,   3, 'Kalistenika — core',             2, 2, 12),
    ( 7, 2, '10:00', 60,   1, 'Pole dance poranny',             1, 1, 10),
    ( 8, 2, '10:00', 60,   3, 'Kalistenika dla początkujących', 2, 2, 12),
    ( 9, 2, '17:15', 45, 101, 'Zdrowe plecy i mobilność',       3, 3,  8),
    (10, 2, '19:00', 60,   1, 'Pole dance — nowe figury',        3, 1, 10),
    (11, 2, '19:00', 75,   3, 'Kalistenika — trening obwodowy',  2, 2, 12),
    (12, 3, '17:00', 60,   1, 'Pole dance od podstaw',          1, 1, 10),
    (13, 3, '17:00', 75,   3, 'Kalistenika — siła',             2, 2, 12),
    (14, 3, '18:15', 45, 101, 'Stretching — spokojny wieczór',   3, 3,  8),
    (15, 4, '16:00', 60,   2, 'Pole dance — płynne przejścia',   1, 1, 10),
    (16, 4, '16:00', 45, 101, 'Mobilność bioder i barków',       3, 3,  8),
    (17, 4, '19:15', 45,   3, 'Kalistenika — technika',          2, 2, 12),
    (18, 5, '17:00', 60,   1, 'Pole dance — piątkowy trening',   1, 1, 10),
    (19, 5, '17:00', 75,   3, 'Kalistenika — mocny piątek',      2, 2, 12),
    (20, 5, '17:00', 45, 101, 'Stretching na dobry weekend',     3, 3,  8),
    (21, 3, '15:30', 45,   5, 'Pole dance dzieci — akrobatyka',  1, 1,  8),
    (22, 6, '10:00', 60,   3, 'Kalistenika w sobotę',            2, 2, 12),
    (23, 6, '11:00', 45, 101, 'Stretching i oddech',             3, 3,  8),
    (24, 7, '10:00', 60,   1, 'Pole dance — niedzielna energia', 1, 1, 10),
    (25, 7, '10:00', 75,   3, 'Kalistenika — trening weekendowy',2, 2, 12),
    (26, 7, '10:00', 45, 101, 'Stretching na cały tydzień',      3, 3,  8);

CREATE TEMP TABLE seed08_events AS
SELECT (100 + week_no * 100 + slot) AS event_no, s.*,
       (date_trunc('week', now() AT TIME ZONE 'Europe/Warsaw')
        + make_interval(weeks => week_no + 1, days =>
            CASE WHEN week_no = 1 THEN CASE slot WHEN 1 THEN 4 WHEN 4 THEN 4 WHEN 7 THEN 5 WHEN 12 THEN 6 WHEN 21 THEN 6 ELSE weekday END
                 ELSE weekday END - 1)
        + (CASE WHEN week_no = 1 THEN CASE slot WHEN 4 THEN TIME '19:15' WHEN 7 THEN TIME '16:00' WHEN 12 THEN TIME '12:00' WHEN 21 THEN TIME '10:00' ELSE start_time END
                ELSE start_time END - TIME '00:00')) AT TIME ZONE 'Europe/Warsaw' AS starts_at
FROM seed08_slots s CROSS JOIN generate_series(0, 1) AS weeks(week_no);

INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at,
                            capacity, visibility, status, created_by)
SELECT ('19000000-0000-0000-0000-' || lpad(event_no::text, 12, '0'))::uuid,
       'CLASS', ('10000000-0000-0000-0000-' || lpad(service_no::text, 12, '0'))::uuid,
       title, starts_at, starts_at + make_interval(mins => duration_minutes), capacity, 'PUBLIC', 'SCHEDULED',
       '03000000-0000-0000-0000-000000000001'::uuid
FROM seed08_events
ON CONFLICT DO NOTHING;

INSERT INTO event_staff (event_id, staff_id, role)
SELECT ('19000000-0000-0000-0000-' || lpad(event_no::text, 12, '0'))::uuid,
       ('08000000-0000-0000-0000-' || lpad(staff_no::text, 12, '0'))::uuid, 'LEAD'
FROM seed08_events
WHERE NOT EXISTS (SELECT 1 FROM event_staff assigned
                  WHERE assigned.event_id = ('19000000-0000-0000-0000-' || lpad(event_no::text, 12, '0'))::uuid)
ON CONFLICT DO NOTHING;

INSERT INTO event_resource (event_id, resource_id, blocks_whole_room)
SELECT ('19000000-0000-0000-0000-' || lpad(event_no::text, 12, '0'))::uuid,
       ('15000000-0000-0000-0000-' || lpad(room_no::text, 12, '0'))::uuid, TRUE
FROM seed08_events
ON CONFLICT DO NOTHING;

-- Two extra sessions per adult over two weeks; one extra child session.
-- The same reservation is visible when logging into either participant's own account.
CREATE TEMP TABLE seed08_bookings (event_no INT, person_no INT);
INSERT INTO seed08_bookings VALUES
    (101, 1), (214, 1), -- Katarzyna: Monday pole + next Wednesday stretching
    (101, 3), (214, 3), -- Monika attends those classes with Katarzyna
    (108, 2), (219, 2), -- Tomasz: one calisthenics session each week
    (121, 4);           -- Zosia: first Wednesday, managed by Monika

-- Reuse Monika's existing Open pass. Dedicated gifted passes for the other three
-- profiles keep existing purchases and balances untouched. The child's four-entry
-- pass has three remaining entries; adult Open passes have no entry limit.
INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by)
SELECT ('28000000-0000-0000-0000-' || lpad((1000 + person_no)::text, 12, '0'))::uuid,
       ('07000000-0000-0000-0000-' || lpad(person_no::text, 12, '0'))::uuid,
       ('14000000-0000-0000-0000-' || lpad((CASE WHEN person_no = 4 THEN 6 ELSE 5 END)::text, 12, '0'))::uuid,
       CASE WHEN person_no = 4 THEN 'Karnet dziecięcy — 4 wejścia' ELSE 'Karnet Open — miesiąc' END,
       CASE WHEN person_no = 4 THEN 4 ELSE NULL END,
       CASE WHEN person_no = 4 THEN 4 - count(*) ELSE NULL END,
       0, 'ACTIVE', CURRENT_DATE, CURRENT_DATE + 30, '03000000-0000-0000-0000-000000000001'::uuid
FROM seed08_bookings WHERE person_no <> 3 GROUP BY person_no
ON CONFLICT DO NOTHING;

INSERT INTO reservation (id, event_id, customer_id, booked_by, source, status, customer_pass_id,
                         free_cancel_until, cancellation_allowed_snapshot,
                         late_cancel_allowed_snapshot, late_cancel_forfeits_entry_snapshot)
SELECT ('30000000-0000-0000-0000-' || lpad((100000 + b.event_no * 1000 + b.person_no)::text, 12, '0'))::uuid,
       e.id, ('07000000-0000-0000-0000-' || lpad(b.person_no::text, 12, '0'))::uuid,
       ('03000000-0000-0000-0000-' || lpad((CASE b.person_no WHEN 1 THEN 4 WHEN 2 THEN 5 ELSE 6 END)::text, 12, '0'))::uuid,
       CASE WHEN b.person_no = 4 THEN 'GUARDIAN' ELSE 'SELF' END,
       'ACTIVE', ('28000000-0000-0000-0000-' || lpad((CASE WHEN b.person_no = 3 THEN 5 ELSE 1000 + b.person_no END)::text, 12, '0'))::uuid,
       e.starts_at - interval '12 hours', TRUE, TRUE, TRUE
FROM seed08_bookings b
JOIN schedule_event e ON e.id = ('19000000-0000-0000-0000-' || lpad(b.event_no::text, 12, '0'))::uuid
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, valid_until_after, reason, actor_account_id)
SELECT ('34000000-0000-0000-0000-' || lpad((1000 + person_no)::text, 12, '0'))::uuid,
       p.id, 'ACTIVATION', coalesce(p.entries_total, 0), p.valid_until, 'Karnet podarowany przez studio',
       '03000000-0000-0000-0000-000000000001'::uuid
FROM (SELECT DISTINCT person_no FROM seed08_bookings WHERE person_no <> 3) b
JOIN customer_pass p ON p.id = ('28000000-0000-0000-0000-' || lpad((1000 + person_no)::text, 12, '0'))::uuid
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, reservation_id, actor_account_id)
SELECT ('34000000-0000-0000-0000-' || lpad((100000 + event_no * 1000 + person_no)::text, 12, '0'))::uuid,
       r.customer_pass_id, 'ENTRY_RESERVED', CASE WHEN p.entries_total IS NULL THEN 0 ELSE -1 END,
       r.id, r.booked_by
FROM seed08_bookings b
JOIN reservation r ON r.id = ('30000000-0000-0000-0000-' || lpad((100000 + event_no * 1000 + person_no)::text, 12, '0'))::uuid
JOIN customer_pass p ON p.id = r.customer_pass_id
ON CONFLICT DO NOTHING;

DROP TABLE seed08_bookings;
DROP TABLE seed08_events;
DROP TABLE seed08_slots;

-- Complete two known original unlimited-pass fixtures as well: Monika's full
-- Thursday class and Tomasz's Monday calisthenics. Their allocations are zero.
UPDATE reservation r
SET customer_pass_id = fixture.pass_id, cancellation_allowed_snapshot = TRUE,
    late_cancel_allowed_snapshot = TRUE, late_cancel_forfeits_entry_snapshot = TRUE
FROM (VALUES
    ('30000000-0000-0000-0000-000000000004'::uuid, '07000000-0000-0000-0000-000000000003'::uuid, '28000000-0000-0000-0000-000000000005'::uuid),
    ('30000000-0000-0000-0000-000000000012'::uuid, '07000000-0000-0000-0000-000000000002'::uuid, '28000000-0000-0000-0000-000000001002'::uuid)
) fixture(reservation_id, customer_id, pass_id)
WHERE r.id = fixture.reservation_id AND r.customer_id = fixture.customer_id AND r.status = 'ACTIVE'
  AND (r.customer_pass_id IS NULL OR r.customer_pass_id = fixture.pass_id)
  AND r.cancellation_allowed_snapshot IS NULL AND r.late_cancel_allowed_snapshot IS NULL
  AND r.late_cancel_forfeits_entry_snapshot IS NULL;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, reservation_id, actor_account_id)
SELECT ('34000000-0000-0000-0000-' || lpad((900000 + right(r.id::text, 12)::int)::text, 12, '0'))::uuid,
       r.customer_pass_id, 'ENTRY_RESERVED', 0, r.id, r.booked_by
FROM reservation r
WHERE (r.id, r.customer_pass_id) IN (
    ('30000000-0000-0000-0000-000000000004'::uuid, '28000000-0000-0000-0000-000000000005'::uuid),
    ('30000000-0000-0000-0000-000000000012'::uuid, '28000000-0000-0000-0000-000000001002'::uuid)
) AND r.status = 'ACTIVE'
ON CONFLICT DO NOTHING;

-- DEV/STAGE ONLY. Five past weeks of classes for the statistics screen (#164, #166): attendance,
-- occupancy, no-shows and cancellations made in time or late.
-- Events 19000000-…-0000000120NN (series occurrences 120SW: S = series 1-4, W = week 1-5);
-- reservation and waiting-list IDs (30000000-…, 31000000-…) end in the event number × 100 + the
-- customer number, e.g. …-000001201101.
-- Insert-only: no accounts, customer profiles or passes are created and no pass balance changes;
-- places are booked without a pass, so no entry is used or forfeited. Dates are relative to the
-- week the seed first runs; reruns preserve them and any change made by a tester.

-- Studio-local time in a week relative to the current one, for an ISO weekday (1 = Monday).
CREATE OR REPLACE FUNCTION pg_temp.seed_stats_at(week_offset INT, iso_weekday INT, local_time TIME)
RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT (date_trunc('week', now() AT TIME ZONE 'Europe/Warsaw')
            + make_interval(weeks => week_offset, days => iso_weekday - 1)
            + (local_time - TIME '00:00')) AT TIME ZONE 'Europe/Warsaw'
$$;

-- Weeks 1-5 are five to one weeks ago. Series: 1 pole Tue 18:00, 2 pole Thu 18:00,
-- 3 calisthenics Mon 19:00, 4 kids pole Sat 10:00. An occurrence seed 03 already holds (the same
-- series and start) is left alone, together with the reservations below that would point at it.
-- Thursday three weeks ago was cancelled by the studio; Thursday a week ago was never completed
-- by the trainer, so its attendance stays unmarked.
WITH occurrences AS (
    SELECT ('19000000-0000-0000-0000-' || lpad((12000 + series_no * 10 + week_no)::TEXT, 12, '0'))::UUID AS id,
           12000 + series_no * 10 + week_no AS event_no, series.id AS series_id, series.service_type_id,
           series.training_group_id, series.title, series.capacity, series.duration_minutes,
           pg_temp.seed_stats_at(week_no - 6, series.weekday, series.local_start_time) AS starts_at
    FROM (VALUES (1), (2), (3), (4)) AS chosen(series_no)
    JOIN recurrence_series series ON series.id = ('18000000-0000-0000-0000-' || lpad(series_no::TEXT, 12, '0'))::UUID
    CROSS JOIN generate_series(1, 5) AS week_no
)
INSERT INTO schedule_event (id, kind, service_type_id, series_id, series_original_start, is_series_exception,
                            training_group_id, title, starts_at, ends_at, capacity, visibility, status,
                            cancel_reason, cancel_note, cancelled_by, cancelled_at, created_by)
SELECT id, 'CLASS', service_type_id, series_id, starts_at, FALSE, training_group_id, title,
       starts_at, starts_at + make_interval(mins => duration_minutes), capacity, 'PUBLIC',
       CASE event_no WHEN 12023 THEN 'CANCELLED' WHEN 12025 THEN 'SCHEDULED' ELSE 'COMPLETED' END,
       CASE WHEN event_no = 12023 THEN 'STAFF_ABSENCE' END,
       CASE WHEN event_no = 12023 THEN 'Choroba instruktorki, brak zastępstwa.' END,
       CASE WHEN event_no = 12023 THEN '03000000-0000-0000-0000-000000000001'::UUID END,
       CASE WHEN event_no = 12023 THEN starts_at - INTERVAL '1 day' END,
       '03000000-0000-0000-0000-000000000001'
FROM occurrences
ON CONFLICT DO NOTHING;

-- A full one-off stretching workshop three weeks ago, and Tomasz's personal sessions with Marek
-- on Fridays at 16:00, five to two weeks ago.
INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at, capacity, visibility, status,
                            created_by)
SELECT ('19000000-0000-0000-0000-' || lpad(event_no::TEXT, 12, '0'))::UUID, kind,
       ('10000000-0000-0000-0000-' || lpad(service_no::TEXT, 12, '0'))::UUID, title,
       pg_temp.seed_stats_at(week_offset, iso_weekday, local_time),
       pg_temp.seed_stats_at(week_offset, iso_weekday, local_time) + make_interval(mins => minutes),
       capacity, visibility, 'COMPLETED', '03000000-0000-0000-0000-000000000001'
FROM (VALUES
    (12051, 'WORKSHOP', 6, 'Warsztaty stretchingu - mostek', -3, 6, TIME '12:00', 120, 4, 'PUBLIC'),
    (12061, 'PERSONAL_TRAINING', 4, 'Trening personalny - Tomasz L.', -5, 5, TIME '16:00', 60, 1, 'PRIVATE'),
    (12062, 'PERSONAL_TRAINING', 4, 'Trening personalny - Tomasz L.', -4, 5, TIME '16:00', 60, 1, 'PRIVATE'),
    (12063, 'PERSONAL_TRAINING', 4, 'Trening personalny - Tomasz L.', -3, 5, TIME '16:00', 60, 1, 'PRIVATE'),
    (12064, 'PERSONAL_TRAINING', 4, 'Trening personalny - Tomasz L.', -2, 5, TIME '16:00', 60, 1, 'PRIVATE')
) AS one_off(event_no, kind, service_no, title, week_offset, iso_weekday, local_time, minutes, capacity, visibility)
ON CONFLICT DO NOTHING;

-- Series occurrences get the series' instructor and room; the workshop Ewa in the small room,
-- the personal sessions Marek in the large one.
INSERT INTO event_staff (event_id, staff_id, role)
SELECT event.id, COALESCE(series.default_staff_id,
       CASE WHEN event.kind = 'WORKSHOP' THEN '08000000-0000-0000-0000-000000000003'::UUID
            ELSE '08000000-0000-0000-0000-000000000002'::UUID END), 'LEAD'
FROM schedule_event event
LEFT JOIN recurrence_series series ON series.id = event.series_id
WHERE event.id BETWEEN '19000000-0000-0000-0000-000000012011'::UUID AND '19000000-0000-0000-0000-000000012064'::UUID
  AND NOT EXISTS (SELECT 1 FROM event_staff assigned WHERE assigned.event_id = event.id)
ON CONFLICT DO NOTHING;

INSERT INTO event_resource (event_id, resource_id, blocks_whole_room)
SELECT event.id, COALESCE(series.default_resource_id,
       CASE WHEN event.kind = 'WORKSHOP' THEN '15000000-0000-0000-0000-000000000003'::UUID
            ELSE '15000000-0000-0000-0000-000000000002'::UUID END), FALSE
FROM schedule_event event
LEFT JOIN recurrence_series series ON series.id = event.series_id
WHERE event.id BETWEEN '19000000-0000-0000-0000-000000012011'::UUID AND '19000000-0000-0000-0000-000000012064'::UUID
  AND NOT EXISTS (SELECT 1 FROM event_resource assigned WHERE assigned.event_id = event.id)
ON CONFLICT DO NOTHING;

-- Outcomes: P present, A absent (no-show), U unmarked, I cancelled in time, L cancelled late,
-- S cancelled with the class by the studio. Hours: how long before the start a place was cancelled
-- (free cancellation ends 12 hours before a class, 24 hours before a personal session).
-- Customers: 1 Katarzyna, 2 Tomasz, 3 Monika, 4 Zosia (child of 3), 6 Adam, 7 Krzysztof (no account),
-- 8 Julia (booked by the studio), 9 Karolina, 10 Kuba (child of 9).
WITH bookings (event_no, customer_no, outcome, hours_before) AS (VALUES
    (12011, 1, 'P', NULL), (12011, 3, 'P', NULL), (12011, 9, 'P', NULL), (12011, 8, 'P', NULL), (12011, 6, 'P', NULL), (12011, 7, 'I', 30),
    (12012, 1, 'P', NULL), (12012, 3, 'A', NULL), (12012, 9, 'P', NULL), (12012, 8, 'L', 3),    (12012, 6, 'P', NULL), (12012, 7, 'P', NULL),
    (12013, 1, 'P', NULL), (12013, 3, 'P', NULL), (12013, 9, 'P', NULL), (12013, 8, 'P', NULL), (12013, 6, 'P', NULL), (12013, 7, 'P', NULL),
    (12014, 1, 'P', NULL), (12014, 3, 'P', NULL), (12014, 9, 'L', 1),    (12014, 8, 'P', NULL), (12014, 6, 'I', 20),   (12014, 7, 'P', NULL),
    (12015, 1, 'P', NULL), (12015, 3, 'P', NULL), (12015, 9, 'P', NULL), (12015, 8, 'A', NULL), (12015, 6, 'P', NULL),
    (12021, 1, 'P', NULL), (12021, 3, 'P', NULL), (12021, 9, 'I', 50),
    (12022, 1, 'P', NULL), (12022, 8, 'P', NULL), (12022, 3, 'L', 5),
    (12023, 1, 'S', NULL), (12023, 3, 'S', NULL),
    (12024, 1, 'P', NULL), (12024, 3, 'P', NULL), (12024, 8, 'A', NULL), (12024, 9, 'P', NULL),
    (12025, 1, 'U', NULL), (12025, 3, 'U', NULL), (12025, 8, 'U', NULL),
    (12031, 2, 'P', NULL), (12031, 6, 'P', NULL), (12031, 7, 'P', NULL),
    (12032, 2, 'P', NULL), (12032, 6, 'A', NULL), (12032, 7, 'P', NULL), (12032, 9, 'P', NULL),
    (12033, 2, 'P', NULL), (12033, 6, 'P', NULL), (12033, 7, 'L', 8),
    (12034, 2, 'P', NULL), (12034, 6, 'P', NULL), (12034, 7, 'P', NULL), (12034, 9, 'I', 26),
    (12035, 2, 'P', NULL), (12035, 6, 'P', NULL), (12035, 9, 'P', NULL),
    (12041, 4, 'P', NULL), (12041, 10, 'P', NULL),
    (12042, 4, 'P', NULL), (12042, 10, 'A', NULL),
    (12043, 4, 'I', 72),   (12043, 10, 'P', NULL),
    (12044, 4, 'P', NULL), (12044, 10, 'P', NULL),
    (12045, 4, 'P', NULL), (12045, 10, 'L', 2),
    (12051, 1, 'P', NULL), (12051, 3, 'P', NULL), (12051, 9, 'P', NULL), (12051, 8, 'P', NULL),
    (12061, 2, 'P', NULL), (12062, 2, 'P', NULL), (12063, 2, 'L', 4), (12064, 2, 'A', NULL)
),
booked AS (
    SELECT bookings.*, event.id AS event_id, event.kind, event.starts_at, event.ends_at, event.cancelled_at AS event_cancelled_at,
           -- Who booked: the participant's own account, a child's guardian, or the studio's administrator.
           CASE customer_no WHEN 1 THEN '03000000-0000-0000-0000-000000000004' WHEN 2 THEN '03000000-0000-0000-0000-000000000005'
                            WHEN 3 THEN '03000000-0000-0000-0000-000000000006' WHEN 4 THEN '03000000-0000-0000-0000-000000000006'
                            WHEN 6 THEN '03000000-0000-0000-0000-000000000009' WHEN 9 THEN '03000000-0000-0000-0000-000000000011'
                            WHEN 10 THEN '03000000-0000-0000-0000-000000000011'
                            ELSE '03000000-0000-0000-0000-000000000010' END::UUID AS booked_by,
           CASE WHEN customer_no IN (4, 10) THEN 'GUARDIAN' WHEN customer_no IN (7, 8) THEN 'STAFF' ELSE 'SELF' END AS source,
           CASE WHEN event.kind = 'PERSONAL_TRAINING' THEN INTERVAL '24 hours' ELSE INTERVAL '12 hours' END AS free_cancel,
           (SELECT staff.account_id FROM event_staff assigned JOIN staff_profile staff ON staff.id = assigned.staff_id
            WHERE assigned.event_id = event.id ORDER BY assigned.staff_id LIMIT 1) AS trainer_account
    FROM bookings
    JOIN schedule_event event ON event.id = ('19000000-0000-0000-0000-' || lpad(event_no::TEXT, 12, '0'))::UUID
)
INSERT INTO reservation (id, event_id, customer_id, booked_by, source, status, booking_policy_id, free_cancel_until,
                         cancelled_at, cancelled_by, cancel_kind, attendance, attendance_marked_by,
                         attendance_marked_at, trainer_confirmed_at, settlement_status, created_at)
SELECT ('30000000-0000-0000-0000-' || lpad((event_no * 100 + customer_no)::TEXT, 12, '0'))::UUID, event_id,
       ('07000000-0000-0000-0000-' || lpad(customer_no::TEXT, 12, '0'))::UUID, booked_by, source,
       CASE WHEN outcome IN ('I', 'L', 'S') THEN 'CANCELLED' ELSE 'ACTIVE' END,
       CASE WHEN kind = 'PERSONAL_TRAINING' THEN '11000000-0000-0000-0000-000000000002'
            ELSE '11000000-0000-0000-0000-000000000001' END::UUID,
       starts_at - free_cancel,
       CASE outcome WHEN 'S' THEN event_cancelled_at WHEN 'I' THEN starts_at - make_interval(hours => hours_before)
                    WHEN 'L' THEN starts_at - make_interval(hours => hours_before) END,
       CASE outcome WHEN 'S' THEN '03000000-0000-0000-0000-000000000001'::UUID WHEN 'I' THEN booked_by WHEN 'L' THEN booked_by END,
       CASE outcome WHEN 'S' THEN 'STUDIO' WHEN 'I' THEN 'IN_TIME' WHEN 'L' THEN 'LATE' END,
       CASE outcome WHEN 'P' THEN 'PRESENT' WHEN 'A' THEN 'ABSENT' ELSE 'UNMARKED' END,
       CASE WHEN outcome IN ('P', 'A') THEN trainer_account END,
       CASE WHEN outcome IN ('P', 'A') THEN ends_at END,
       CASE WHEN kind = 'PERSONAL_TRAINING' AND outcome IN ('P', 'A') THEN ends_at END,
       CASE WHEN kind = 'PERSONAL_TRAINING' AND outcome IN ('P', 'A') THEN 'FINAL' ELSE 'OPEN' END,
       starts_at - INTERVAL '6 days'
FROM booked
ON CONFLICT DO NOTHING;

-- Two participants waited for a place at the full workshop and left the list before it started.
INSERT INTO waitlist_entry (id, event_id, customer_id, added_by, status, created_at, resolved_at)
SELECT ('31000000-0000-0000-0000-' || lpad((12051 * 100 + customer_no)::TEXT, 12, '0'))::UUID, event.id,
       ('07000000-0000-0000-0000-' || lpad(customer_no::TEXT, 12, '0'))::UUID, added_by::UUID, 'CANCELLED',
       event.starts_at - INTERVAL '4 days', event.starts_at - INTERVAL '1 day'
FROM (VALUES (6, '03000000-0000-0000-0000-000000000009'), (7, '03000000-0000-0000-0000-000000000010'))
    AS waiting(customer_no, added_by)
JOIN schedule_event event ON event.id = '19000000-0000-0000-0000-000000012051'
ON CONFLICT DO NOTHING;

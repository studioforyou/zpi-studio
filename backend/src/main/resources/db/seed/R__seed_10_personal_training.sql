-- DEV/STAGE ONLY. Personal-training packages, confirmations and accounting examples.
-- Adds dedicated rows; never resets balances, dates or decisions changed by a tester.
-- Dedicated table-number IDs use suffixes 10001..10022, separate from the timetable showcase.
-- Marek/Tomasz: 3 entries left, several packages and a calendar of meaningful states.
-- Marek/Monika: an exhausted PT package, separate from her original Open pass/make-up credit.
-- Ewa/Katarzyna: an unlimited PT package. No existing package or relationship is replaced.

CREATE OR REPLACE FUNCTION pg_temp.seed_pt_day(day_offset INT)
RETURNS DATE LANGUAGE sql AS $$
    SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + day_offset
$$;

CREATE OR REPLACE FUNCTION pg_temp.seed_pt_at(day_offset INT, local_time TIME)
RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT (pg_temp.seed_pt_day(day_offset) + local_time) AT TIME ZONE 'Europe/Warsaw'
$$;

INSERT INTO pass_product (id, name, description, kind, audience, makeup_policy_id,
                          suspension_allowed, max_suspension_days, is_active)
VALUES ('13000000-0000-0000-0000-000000010001', 'Elastyczny pakiet treningów personalnych',
        'Treningi indywidualne z możliwością uzgodnionego zawieszenia.', 'PT_PACKAGE', 'ADULT',
        '12000000-0000-0000-0000-000000000001', TRUE, 14, TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO pass_product_variant (id, product_id, entries_count, validity_days, price, currency, is_active) VALUES
    ('14000000-0000-0000-0000-000000010001', '13000000-0000-0000-0000-000000010001', 8,    60, 1120.00, 'PLN', TRUE),
    ('14000000-0000-0000-0000-000000010002', '13000000-0000-0000-0000-000000010001', 5,    60, 750.00,  'PLN', TRUE),
    ('14000000-0000-0000-0000-000000010003', '13000000-0000-0000-0000-000000010001', NULL, 30, 1800.00, 'PLN', TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO pass_product_service (product_id, service_type_id)
VALUES ('13000000-0000-0000-0000-000000010001', '10000000-0000-0000-0000-000000000004')
ON CONFLICT DO NOTHING;

INSERT INTO staff_assignment (id, customer_id, staff_id, scope, can_view_health, assigned_by, started_at)
VALUES ('09000000-0000-0000-0000-000000010001', '07000000-0000-0000-0000-000000000003',
        '08000000-0000-0000-0000-000000000002', 'PERSONAL_TRAINING', FALSE,
        '03000000-0000-0000-0000-000000000001', pg_temp.seed_pt_at(-40, '09:00'))
ON CONFLICT DO NOTHING;

INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by, created_at) VALUES
    ('28000000-0000-0000-0000-000000010001', '07000000-0000-0000-0000-000000000002', '14000000-0000-0000-0000-000000010001',
     'Pakiet PT — 8 treningów', 8, 3, 1120.00, 'ACTIVE', pg_temp.seed_pt_day(-45), pg_temp.seed_pt_day(14),
     '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-45, '09:00')),
    ('28000000-0000-0000-0000-000000010002', '07000000-0000-0000-0000-000000000002', '14000000-0000-0000-0000-000000010002',
     'Kolejny pakiet PT — 5 treningów', 5, 5, 750.00, 'SCHEDULED', pg_temp.seed_pt_day(31), pg_temp.seed_pt_day(90),
     '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-1, '09:00')),
    ('28000000-0000-0000-0000-000000010003', '07000000-0000-0000-0000-000000000003', '14000000-0000-0000-0000-000000010002',
     'Pakiet PT — 5 treningów', 5, 0, 750.00, 'ACTIVE', pg_temp.seed_pt_day(-39), pg_temp.seed_pt_day(20),
     '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-39, '09:00')),
    ('28000000-0000-0000-0000-000000010004', '07000000-0000-0000-0000-000000000002', '14000000-0000-0000-0000-000000010002',
     'Zawieszony pakiet PT — 5 treningów', 5, 5, 750.00, 'ACTIVE', pg_temp.seed_pt_day(-10), pg_temp.seed_pt_day(55),
     '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-10, '09:00')),
    ('28000000-0000-0000-0000-000000010005', '07000000-0000-0000-0000-000000000001', '14000000-0000-0000-0000-000000010003',
     'Pakiet PT — bez limitu', NULL, NULL, 1800.00, 'ACTIVE', pg_temp.seed_pt_day(-5), pg_temp.seed_pt_day(24),
     '03000000-0000-0000-0000-000000000003', pg_temp.seed_pt_at(-5, '09:00'))
ON CONFLICT DO NOTHING;

INSERT INTO pass_suspension (id, pass_id, starts_on, ends_on, reason, created_by, created_at)
VALUES ('29000000-0000-0000-0000-000000010001', '28000000-0000-0000-0000-000000010004',
        pg_temp.seed_pt_day(0), pg_temp.seed_pt_day(5), 'Uzgodniona przerwa w treningach',
        '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-1, '09:00'))
ON CONFLICT DO NOTHING;

WITH slots (slot, day_offset, hour, customer_no, staff_no, event_status) AS (VALUES
    (10001, -3, TIME '12:00', 2, 2, 'COMPLETED'),
    (10002, -2, TIME '12:00', 2, 2, 'COMPLETED'),
    (10003, -1, TIME '12:00', 2, 2, 'COMPLETED'),
    (10004,  1, TIME '12:00', 2, 2, 'SCHEDULED'),
    (10005,  2, TIME '12:00', 2, 2, 'SCHEDULED'),
    (10006,  3, TIME '12:00', 2, 2, 'CANCELLED'),
    (10007, -4, TIME '12:00', 2, 2, 'COMPLETED'),
    (10008,  1, TIME '13:00', 3, 2, 'SCHEDULED'),
    (10009, -2, TIME '13:00', 1, 3, 'COMPLETED'),
    (10010,  1, TIME '13:00', 1, 3, 'SCHEDULED')
)
INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at, capacity,
                             visibility, status, cancel_reason, cancelled_by, cancelled_at, created_by)
SELECT ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       'PERSONAL_TRAINING', '10000000-0000-0000-0000-000000000004',
       CASE customer_no WHEN 2 THEN 'Trening personalny — Tomasz'
                        WHEN 3 THEN 'Trening personalny — Monika'
                        ELSE 'Trening personalny — Katarzyna' END,
       pg_temp.seed_pt_at(day_offset, hour), pg_temp.seed_pt_at(day_offset, hour) + INTERVAL '1 hour',
       1, 'PRIVATE', event_status,
       CASE WHEN event_status = 'CANCELLED' THEN 'STAFF_ABSENCE' END,
       CASE WHEN event_status = 'CANCELLED' THEN '03000000-0000-0000-0000-000000000002'::UUID END,
       CASE WHEN event_status = 'CANCELLED' THEN pg_temp.seed_pt_at(-1, '09:00') END,
       ('03000000-0000-0000-0000-' || lpad(staff_no::TEXT, 12, '0'))::UUID
FROM slots
ON CONFLICT DO NOTHING;

INSERT INTO event_staff (event_id, staff_id, role)
SELECT id, CASE WHEN id IN ('19000000-0000-0000-0000-000000010009', '19000000-0000-0000-0000-000000010010')
               THEN '08000000-0000-0000-0000-000000000003'::UUID
               ELSE '08000000-0000-0000-0000-000000000002'::UUID END, 'LEAD'
FROM schedule_event event
WHERE id BETWEEN '19000000-0000-0000-0000-000000010001'::UUID AND '19000000-0000-0000-0000-000000010010'::UUID
  AND NOT EXISTS (SELECT 1 FROM event_staff assigned WHERE assigned.event_id = event.id)
ON CONFLICT DO NOTHING;

INSERT INTO event_resource (event_id, resource_id, blocks_whole_room)
SELECT id, CASE WHEN id IN ('19000000-0000-0000-0000-000000010009', '19000000-0000-0000-0000-000000010010')
               THEN '15000000-0000-0000-0000-000000000003'::UUID
               ELSE '15000000-0000-0000-0000-000000000002'::UUID END, FALSE
FROM schedule_event event
WHERE id BETWEEN '19000000-0000-0000-0000-000000010001'::UUID AND '19000000-0000-0000-0000-000000010010'::UUID
  AND NOT EXISTS (SELECT 1 FROM event_resource assigned WHERE assigned.event_id = event.id)
ON CONFLICT DO NOTHING;

-- 10007 deliberately has FINAL without a ledger entry: the read must expose the discrepancy.
-- 10008 has no package link: a cash/outside-package session is not automatically an error.
WITH bookings (slot, customer_no, staff_no, pass_no, trainer_confirmed, client_confirmed, settlement) AS (VALUES
    (10001, 2, 2, 10001, TRUE,  TRUE,  'FINAL'),
    (10002, 2, 2, 10001, TRUE,  FALSE, 'OPEN'),
    (10003, 2, 2, 10001, TRUE,  FALSE, 'DISPUTED'),
    (10004, 2, 2, 10001, FALSE, FALSE, 'OPEN'),
    (10005, 2, 2, 10001, FALSE, FALSE, 'OPEN'),
    (10006, 2, 2, 10001, FALSE, FALSE, 'FINAL'),
    (10007, 2, 2, 10001, TRUE,  TRUE,  'FINAL'),
    (10008, 3, 2, NULL, FALSE, FALSE, 'OPEN'),
    (10009, 1, 3, 10005, TRUE,  TRUE,  'FINAL'),
    (10010, 1, 3, 10005, FALSE, FALSE, 'OPEN')
)
INSERT INTO reservation (id, event_id, customer_id, booked_by, source, status, customer_pass_id,
                         booking_policy_id, free_cancel_until, cancelled_at, cancelled_by, cancel_kind,
                         entry_returned, attendance, attendance_marked_by, attendance_marked_at,
                         trainer_confirmed_at, client_confirmed_at, settlement_status, created_at)
SELECT ('30000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, event.id,
       ('07000000-0000-0000-0000-' || lpad(customer_no::TEXT, 12, '0'))::UUID, event.created_by,
       'STAFF', CASE WHEN event.status = 'CANCELLED' THEN 'CANCELLED' ELSE 'ACTIVE' END,
       CASE WHEN pass_no IS NOT NULL THEN ('28000000-0000-0000-0000-' || lpad(pass_no::TEXT, 12, '0'))::UUID END,
       '11000000-0000-0000-0000-000000000002', event.starts_at - INTERVAL '24 hours',
       event.cancelled_at, event.cancelled_by, CASE WHEN event.status = 'CANCELLED' THEN 'STUDIO' END,
       CASE WHEN event.status = 'CANCELLED' THEN TRUE END,
       CASE WHEN trainer_confirmed THEN 'PRESENT' ELSE 'UNMARKED' END,
       CASE WHEN trainer_confirmed THEN event.created_by END,
       CASE WHEN trainer_confirmed THEN event.ends_at END,
       CASE WHEN trainer_confirmed THEN event.ends_at END,
       CASE WHEN client_confirmed THEN event.ends_at + INTERVAL '10 minutes' END,
       settlement, LEAST(event.starts_at - INTERVAL '3 days', pg_temp.seed_pt_at(-1, '08:00'))
FROM bookings
JOIN schedule_event event ON event.id = ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID
ON CONFLICT DO NOTHING;

INSERT INTO training_dispute (id, reservation_id, raised_by, reason, status, raised_at)
VALUES ('32000000-0000-0000-0000-000000010001', '30000000-0000-0000-0000-000000010003',
        '03000000-0000-0000-0000-000000000005', 'Prośba o wyjaśnienie rozliczenia treningu.', 'OPEN',
        (SELECT ends_at + INTERVAL '30 minutes' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000010003'))
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, valid_until_before, valid_until_after,
                         reservation_id, reason, actor_account_id, occurred_at) VALUES
    ('34000000-0000-0000-0000-000000010001', '28000000-0000-0000-0000-000000010001', 'ACTIVATION', 8, NULL, pg_temp.seed_pt_day(14), NULL, 'Pakiet treningów personalnych', '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-45, '09:00')),
    ('34000000-0000-0000-0000-000000010002', '28000000-0000-0000-0000-000000010002', 'ACTIVATION', 5, NULL, pg_temp.seed_pt_day(90), NULL, 'Pakiet na kolejny okres', '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-1, '09:00')),
    ('34000000-0000-0000-0000-000000010003', '28000000-0000-0000-0000-000000010003', 'ACTIVATION', 5, NULL, pg_temp.seed_pt_day(20), NULL, 'Pakiet treningów personalnych', '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-39, '09:00')),
    ('34000000-0000-0000-0000-000000010004', '28000000-0000-0000-0000-000000010003', 'CORRECTION', -5, NULL, NULL, NULL, 'Treningi wykorzystane przed prowadzeniem ewidencji terminów', '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-5, '09:00')),
    ('34000000-0000-0000-0000-000000010005', '28000000-0000-0000-0000-000000010004', 'ACTIVATION', 5, NULL, pg_temp.seed_pt_day(49), NULL, 'Pakiet treningów personalnych', '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-10, '09:00')),
    ('34000000-0000-0000-0000-000000010006', '28000000-0000-0000-0000-000000010004', 'EXTENSION', 0, pg_temp.seed_pt_day(49), pg_temp.seed_pt_day(55), NULL, 'Uzgodnione zawieszenie na sześć dni', '03000000-0000-0000-0000-000000000002', pg_temp.seed_pt_at(-1, '09:00')),
    ('34000000-0000-0000-0000-000000010007', '28000000-0000-0000-0000-000000010005', 'ACTIVATION', 0, NULL, pg_temp.seed_pt_day(24), NULL, 'Pakiet treningów personalnych bez limitu', '03000000-0000-0000-0000-000000000003', pg_temp.seed_pt_at(-5, '09:00'))
ON CONFLICT DO NOTHING;

WITH accounting (slot, reservation_no, pass_no, kind, delta) AS (VALUES
    (10011, 10001, 10001, 'ENTRY_RESERVED', -1), (10012, 10001, 10001, 'ENTRY_CONSUMED', 0),
    (10013, 10002, 10001, 'ENTRY_RESERVED', -1),
    (10014, 10003, 10001, 'ENTRY_RESERVED', -1), (10015, 10003, 10001, 'ENTRY_CONSUMED', 0),
    (10016, 10004, 10001, 'ENTRY_RESERVED', -1), (10017, 10005, 10001, 'ENTRY_RESERVED', -1),
    (10018, 10006, 10001, 'ENTRY_RESERVED', -1), (10019, 10006, 10001, 'ENTRY_RETURNED', 1),
    (10020, 10009, 10005, 'ENTRY_RESERVED', 0), (10021, 10009, 10005, 'ENTRY_CONSUMED', 0),
    (10022, 10010, 10005, 'ENTRY_RESERVED', 0)
)
INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, reservation_id, actor_account_id, occurred_at)
SELECT ('34000000-0000-0000-0000-' || lpad(accounting.slot::TEXT, 12, '0'))::UUID,
       ('28000000-0000-0000-0000-' || lpad(accounting.pass_no::TEXT, 12, '0'))::UUID, accounting.kind, accounting.delta,
       booking.id, booking.booked_by,
       CASE WHEN accounting.kind = 'ENTRY_RESERVED' THEN booking.created_at
            WHEN accounting.kind = 'ENTRY_RETURNED' THEN booking.cancelled_at
            ELSE event.ends_at + INTERVAL '15 minutes' END
FROM accounting
JOIN reservation booking ON booking.id = ('30000000-0000-0000-0000-' || lpad(accounting.reservation_no::TEXT, 12, '0'))::UUID
JOIN schedule_event event ON event.id = booking.event_id
ON CONFLICT DO NOTHING;

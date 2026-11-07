-- DEV/STAGE ONLY. Dedicated examples for the client's pass entry history.
-- IDs 11001..11030 do not overlap the timetable or personal-training examples.
-- Insert-only: reruns preserve dates, balances and any decisions made by a tester.
CREATE OR REPLACE FUNCTION pg_temp.seed_usage_day(day_offset INT)
RETURNS DATE LANGUAGE sql AS $$
    SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + day_offset
$$;

CREATE OR REPLACE FUNCTION pg_temp.seed_usage_at(day_offset INT, local_time TIME)
RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT (pg_temp.seed_usage_day(day_offset) + local_time) AT TIME ZONE 'Europe/Warsaw'
$$;

INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by, created_at) VALUES
    ('28000000-0000-0000-0000-000000011001', '07000000-0000-0000-0000-000000000001', '14000000-0000-0000-0000-000000000002',
     'Karnet Pole Dance — 8 wejść', 8, 6, 260.00, 'ACTIVE', pg_temp.seed_usage_day(-10), pg_temp.seed_usage_day(19),
     '03000000-0000-0000-0000-000000000001', pg_temp.seed_usage_at(-10, '09:00')),
    ('28000000-0000-0000-0000-000000011002', '07000000-0000-0000-0000-000000000001', '14000000-0000-0000-0000-000000000001',
     'Poprzedni karnet Pole Dance — 4 wejścia', 4, 1, 180.00, 'EXPIRED', pg_temp.seed_usage_day(-60), pg_temp.seed_usage_day(-31),
     '03000000-0000-0000-0000-000000000001', pg_temp.seed_usage_at(-60, '09:00')),
    ('28000000-0000-0000-0000-000000011003', '07000000-0000-0000-0000-000000000003', '14000000-0000-0000-0000-000000000005',
     'Karnet Open — miesiąc', NULL, NULL, 299.00, 'ACTIVE', pg_temp.seed_usage_day(-5), pg_temp.seed_usage_day(24),
     '03000000-0000-0000-0000-000000000001', pg_temp.seed_usage_at(-5, '09:00')),
    ('28000000-0000-0000-0000-000000011004', '07000000-0000-0000-0000-000000000004', '14000000-0000-0000-0000-000000000006',
     'Karnet dziecięcy — 4 wejścia', 4, 3, 160.00, 'ACTIVE', pg_temp.seed_usage_day(-5), pg_temp.seed_usage_day(24),
     '03000000-0000-0000-0000-000000000001', pg_temp.seed_usage_at(-5, '09:00')),
    ('28000000-0000-0000-0000-000000011005', '07000000-0000-0000-0000-000000000002', '14000000-0000-0000-0000-000000000004',
     'Nowy pakiet PT — 5 treningów', 5, 5, 750.00, 'ACTIVE', pg_temp.seed_usage_day(0), pg_temp.seed_usage_day(59),
     '03000000-0000-0000-0000-000000000002', CURRENT_TIMESTAMP),
    ('28000000-0000-0000-0000-000000011006', '07000000-0000-0000-0000-000000000003', '14000000-0000-0000-0000-000000000001',
     'Karnet Pole Dance — 4 wejścia', 4, 3, 180.00, 'ACTIVE', pg_temp.seed_usage_day(-10), pg_temp.seed_usage_day(19),
     '03000000-0000-0000-0000-000000000001', pg_temp.seed_usage_at(-10, '09:00'))
ON CONFLICT DO NOTHING;

WITH slots (slot, day_offset, hour, service_no, title, status) AS (VALUES
    (11001, -6, TIME '11:00', 1, 'Pole dance — podstawy', 'COMPLETED'),
    (11002,  2, TIME '11:00', 1, 'Pole dance — nowa kombinacja', 'SCHEDULED'),
    (11003,  1, TIME '11:00', 1, 'Pole dance — odwołany termin', 'CANCELLED'),
    (11004, -2, TIME '11:00', 1, 'Pole dance — trening techniki', 'COMPLETED'),
    (11005, -40, TIME '11:00', 1, 'Pole dance — poprzedni miesiąc', 'COMPLETED'),
    (11006, -3, TIME '13:00', 1, 'Pole dance Open — technika', 'COMPLETED'),
    (11007,  2, TIME '13:00', 1, 'Pole dance Open — kombinacje', 'SCHEDULED'),
    (11008, -1, TIME '10:00', 5, 'Pole dance dzieci — podstawy', 'COMPLETED')
)
INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at, capacity, visibility,
                             status, cancel_reason, cancelled_by, cancelled_at, created_by)
SELECT ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       'CLASS', ('10000000-0000-0000-0000-' || lpad(service_no::TEXT, 12, '0'))::UUID,
       title, pg_temp.seed_usage_at(day_offset, hour), pg_temp.seed_usage_at(day_offset, hour)
           + CASE WHEN service_no = 5 THEN INTERVAL '45 minutes' ELSE INTERVAL '1 hour' END,
       8, 'PUBLIC', status,
       CASE WHEN status = 'CANCELLED' THEN 'STAFF_ABSENCE' END,
       CASE WHEN status = 'CANCELLED' THEN '03000000-0000-0000-0000-000000000001'::UUID END,
       CASE WHEN status = 'CANCELLED' THEN pg_temp.seed_usage_at(-1, '09:00') END,
       '03000000-0000-0000-0000-000000000001'
FROM slots
ON CONFLICT DO NOTHING;

INSERT INTO event_staff (event_id, staff_id, role)
SELECT id, '08000000-0000-0000-0000-000000000001', 'LEAD'
FROM schedule_event event
WHERE id BETWEEN '19000000-0000-0000-0000-000000011001'::UUID AND '19000000-0000-0000-0000-000000011008'::UUID
  AND NOT EXISTS (SELECT 1 FROM event_staff assigned WHERE assigned.event_id = event.id)
ON CONFLICT DO NOTHING;

INSERT INTO event_resource (event_id, resource_id, blocks_whole_room)
SELECT id, '15000000-0000-0000-0000-000000000001', FALSE
FROM schedule_event event
WHERE id BETWEEN '19000000-0000-0000-0000-000000011001'::UUID AND '19000000-0000-0000-0000-000000011008'::UUID
  AND NOT EXISTS (SELECT 1 FROM event_resource assigned WHERE assigned.event_id = event.id)
ON CONFLICT DO NOTHING;

WITH bookings (slot, customer_no, pass_no) AS (VALUES
    (11001, 1, 11001), (11002, 1, 11001), (11003, 1, 11001), (11004, 1, 11001),
    (11005, 1, 11002), (11006, 3, 11003), (11007, 3, 11003), (11008, 4, 11004)
)
INSERT INTO reservation (id, event_id, customer_id, booked_by, source, status, customer_pass_id, booking_policy_id,
                         free_cancel_until, cancelled_at, cancelled_by, cancel_kind, entry_returned,
                         attendance, created_at)
SELECT ('30000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, event.id,
       ('07000000-0000-0000-0000-' || lpad(customer_no::TEXT, 12, '0'))::UUID, event.created_by, 'STAFF',
       CASE WHEN event.status = 'CANCELLED' THEN 'CANCELLED' ELSE 'ACTIVE' END,
       ('28000000-0000-0000-0000-' || lpad(pass_no::TEXT, 12, '0'))::UUID,
       '11000000-0000-0000-0000-000000000001', event.starts_at - INTERVAL '12 hours',
       event.cancelled_at, event.cancelled_by, CASE WHEN event.status = 'CANCELLED' THEN 'STUDIO' END,
       CASE WHEN event.status = 'CANCELLED' THEN TRUE END,
       CASE WHEN event.status = 'COMPLETED' THEN 'PRESENT' ELSE 'UNMARKED' END,
       GREATEST(event.starts_at - INTERVAL '3 days', pass.created_at + INTERVAL '1 hour')
FROM bookings JOIN schedule_event event
ON event.id = ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID
JOIN customer_pass pass ON pass.id = ('28000000-0000-0000-0000-' || lpad(pass_no::TEXT, 12, '0'))::UUID
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, occurred_at, reason) VALUES
    ('34000000-0000-0000-0000-000000011001', '28000000-0000-0000-0000-000000011001', 'ACTIVATION', 8, pg_temp.seed_usage_at(-10, '09:00'), 'Przyznanie karnetu'),
    ('34000000-0000-0000-0000-000000011002', '28000000-0000-0000-0000-000000011002', 'ACTIVATION', 4, pg_temp.seed_usage_at(-60, '09:00'), 'Przyznanie karnetu'),
    ('34000000-0000-0000-0000-000000011003', '28000000-0000-0000-0000-000000011003', 'ACTIVATION', 0, pg_temp.seed_usage_at(-5, '09:00'), 'Przyznanie karnetu Open'),
    ('34000000-0000-0000-0000-000000011004', '28000000-0000-0000-0000-000000011004', 'ACTIVATION', 4, pg_temp.seed_usage_at(-5, '09:00'), 'Przyznanie karnetu dziecięcego'),
    ('34000000-0000-0000-0000-000000011005', '28000000-0000-0000-0000-000000011005', 'ACTIVATION', 5, CURRENT_TIMESTAMP, 'Przyznanie nowego pakietu'),
    ('34000000-0000-0000-0000-000000011006', '28000000-0000-0000-0000-000000011001', 'CORRECTION', 1, pg_temp.seed_usage_at(-1, '15:00'), 'Uzgodnione dodatkowe wejście'),
    ('34000000-0000-0000-0000-000000011007', '28000000-0000-0000-0000-000000011002', 'CORRECTION', -2, pg_temp.seed_usage_at(-45, '09:00'), 'Wykorzystanie przed prowadzeniem ewidencji terminów'),
    ('34000000-0000-0000-0000-000000011008', '28000000-0000-0000-0000-000000011006', 'ACTIVATION', 4, pg_temp.seed_usage_at(-10, '09:00'), 'Przyznanie karnetu'),
    ('34000000-0000-0000-0000-000000011026', '28000000-0000-0000-0000-000000011006', 'CORRECTION', 1, pg_temp.seed_usage_at(-1, '14:00'), 'Uzgodnione dodatkowe wejście')
ON CONFLICT DO NOTHING;

-- One guardian account has its own finite/Open passes and the child's pass.
INSERT INTO reservation (id, event_id, customer_id, booked_by, source, customer_pass_id,
                         booking_policy_id, free_cancel_until, attendance, created_at)
SELECT ('30000000-0000-0000-0000-' || lpad(booking_no::TEXT, 12, '0'))::UUID, event.id,
       pass.customer_id, event.created_by, 'STAFF', pass.id,
       '11000000-0000-0000-0000-000000000001', event.starts_at - INTERVAL '12 hours', 'PRESENT',
       GREATEST(event.starts_at - INTERVAL '3 days', pass.created_at + INTERVAL '1 hour')
FROM (VALUES (11009, 11001), (11010, 11004)) AS bookings(booking_no, event_no)
JOIN schedule_event event ON event.id = ('19000000-0000-0000-0000-' || lpad(event_no::TEXT, 12, '0'))::UUID
JOIN customer_pass pass ON pass.id = '28000000-0000-0000-0000-000000011006'
ON CONFLICT DO NOTHING;

WITH accounting (slot, booking_no, pass_no, kind, delta) AS (VALUES
    (11011, 11001, 11001, 'ENTRY_RESERVED', -1), (11012, 11001, 11001, 'ENTRY_CONSUMED', 0),
    (11013, 11002, 11001, 'ENTRY_RESERVED', -1),
    (11014, 11003, 11001, 'ENTRY_RESERVED', -1), (11015, 11003, 11001, 'ENTRY_RETURNED', 1),
    (11016, 11004, 11001, 'ENTRY_CONSUMED', -1), (11017, 11005, 11002, 'ENTRY_CONSUMED', -1),
    (11018, 11006, 11003, 'ENTRY_RESERVED', 0), (11019, 11006, 11003, 'ENTRY_CONSUMED', 0),
    (11020, 11007, 11003, 'ENTRY_RESERVED', 0),
    (11021, 11008, 11004, 'ENTRY_RESERVED', -1), (11022, 11008, 11004, 'ENTRY_CONSUMED', 0),
    (11023, 11009, 11006, 'ENTRY_RESERVED', -1), (11024, 11009, 11006, 'ENTRY_CONSUMED', 0),
    (11025, 11010, 11006, 'ENTRY_CONSUMED', -1)
)
INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, reservation_id, actor_account_id, occurred_at)
SELECT ('34000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       ('28000000-0000-0000-0000-' || lpad(pass_no::TEXT, 12, '0'))::UUID, accounting.kind, delta,
       booking.id, booking.booked_by,
       CASE WHEN accounting.kind = 'ENTRY_RESERVED' THEN booking.created_at
            WHEN accounting.kind = 'ENTRY_RETURNED' THEN booking.cancelled_at
            ELSE event.ends_at END
FROM accounting
JOIN reservation booking ON booking.id = ('30000000-0000-0000-0000-' || lpad(booking_no::TEXT, 12, '0'))::UUID
JOIN schedule_event event ON event.id = booking.event_id
ON CONFLICT DO NOTHING;

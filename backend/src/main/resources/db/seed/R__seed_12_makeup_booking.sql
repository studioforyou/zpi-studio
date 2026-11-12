-- DEV/STAGE ONLY. Insert-only make-up booking examples; reruns never renew or reopen credits.
CREATE OR REPLACE FUNCTION pg_temp.seed_makeup_at(day_offset INT, local_time TIME)
RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT (((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + day_offset) + local_time)
           AT TIME ZONE 'Europe/Warsaw'
$$;

INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by, created_at)
SELECT ('28000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       ('07000000-0000-0000-0000-' || lpad(customer_no::TEXT, 12, '0'))::UUID,
       ('14000000-0000-0000-0000-' || lpad(variant_no::TEXT, 12, '0'))::UUID, product_name, 4, 0,
       price, 'EXPIRED', (pg_temp.seed_makeup_at(-50, '09:00') AT TIME ZONE 'Europe/Warsaw')::DATE,
       (pg_temp.seed_makeup_at(-20, '09:00') AT TIME ZONE 'Europe/Warsaw')::DATE,
       '03000000-0000-0000-0000-000000000001', pg_temp.seed_makeup_at(-50, '09:00')
FROM (VALUES (12001, 3, 1, 'Poprzedni karnet Pole Dance — 4 wejścia', 180.00),
             (12002, 4, 6, 'Poprzedni karnet dziecięcy — 4 wejścia', 160.00))
     AS examples(slot, customer_no, variant_no, product_name, price)
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, reason, occurred_at)
SELECT ('34000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       ('28000000-0000-0000-0000-' || lpad(pass_no::TEXT, 12, '0'))::UUID,
       kind, delta, reason, pg_temp.seed_makeup_at(day_offset, '09:00')
FROM (VALUES (12001, 12001, 'ACTIVATION', 4, 'Przyznanie karnetu', -50),
             (12002, 12001, 'CORRECTION', -4, 'Rozliczenie wykorzystanych wejść', -21),
             (12003, 12002, 'ACTIVATION', 4, 'Przyznanie karnetu', -50),
             (12004, 12002, 'CORRECTION', -4, 'Rozliczenie wykorzystanych wejść', -21))
     AS accounting(slot, pass_no, kind, delta, reason, day_offset)
ON CONFLICT DO NOTHING;

INSERT INTO makeup_credit (id, customer_id, source_pass_id, granted_by, granted_at, expires_at, status)
SELECT ('33000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, pass.customer_id, pass.id,
       '03000000-0000-0000-0000-000000000001', pg_temp.seed_makeup_at(-1, '09:00'),
       pg_temp.seed_makeup_at(expiry_day, expiry_time), examples.status
FROM (VALUES (12001, 12001, 12, TIME '23:59', 'AVAILABLE'),
             (12002, 12001,  2, TIME '12:00', 'AVAILABLE'),
             (12003, 12002,  8, TIME '23:59', 'AVAILABLE'),
             (12004, 12001, -1, TIME '08:00', 'EXPIRED'))
     AS examples(slot, pass_no, expiry_day, expiry_time, status)
JOIN customer_pass pass ON pass.id = ('28000000-0000-0000-0000-' || lpad(pass_no::TEXT, 12, '0'))::UUID
ON CONFLICT DO NOTHING;

INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at, capacity, visibility, status, created_by)
SELECT ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, 'CLASS',
       ('10000000-0000-0000-0000-' || lpad(service_no::TEXT, 12, '0'))::UUID, title,
       pg_temp.seed_makeup_at(day_offset, hour), pg_temp.seed_makeup_at(day_offset, hour) + duration,
       capacity, 'PUBLIC', 'SCHEDULED', '03000000-0000-0000-0000-000000000001'
FROM (VALUES (12001, 1, 'Pole dance — technika i powtórka', 1, TIME '18:00', INTERVAL '1 hour', 8),
             (12002, 2, 'Pole dance — figury i kombinacje', 4, TIME '18:00', INTERVAL '1 hour', 8),
             (12003, 5, 'Pole dance dzieci — odrabiamy razem', 3, TIME '16:00', INTERVAL '45 minutes', 8),
             (12004, 1, 'Pole dance — komplet miejsc', 2, TIME '19:00', INTERVAL '1 hour', 1),
             (12005, 1, 'Pole dance — późniejszy termin', 20, TIME '18:00', INTERVAL '1 hour', 8),
             (12006, 3, 'Calisthenics — trening siłowy', 2, TIME '18:00', INTERVAL '75 minutes', 12))
     AS sessions(slot, service_no, title, day_offset, hour, duration, capacity)
ON CONFLICT DO NOTHING;

INSERT INTO event_staff (event_id, staff_id, role)
SELECT event.id, CASE WHEN event.service_type_id = '10000000-0000-0000-0000-000000000003'::UUID
                     THEN '08000000-0000-0000-0000-000000000002'::UUID
                     ELSE '08000000-0000-0000-0000-000000000001'::UUID END, 'LEAD'
FROM schedule_event event
WHERE event.id BETWEEN '19000000-0000-0000-0000-000000012001' AND '19000000-0000-0000-0000-000000012006'
  AND NOT EXISTS (SELECT 1 FROM event_staff assigned WHERE assigned.event_id = event.id)
ON CONFLICT DO NOTHING;

INSERT INTO event_resource (event_id, resource_id, blocks_whole_room)
SELECT event.id, CASE WHEN event.service_type_id = '10000000-0000-0000-0000-000000000003'::UUID
                     THEN '15000000-0000-0000-0000-000000000002'::UUID
                     ELSE '15000000-0000-0000-0000-000000000001'::UUID END, FALSE
FROM schedule_event event
WHERE event.id BETWEEN '19000000-0000-0000-0000-000000012001' AND '19000000-0000-0000-0000-000000012006'
  AND NOT EXISTS (SELECT 1 FROM event_resource assigned WHERE assigned.event_id = event.id)
ON CONFLICT DO NOTHING;

INSERT INTO reservation (id, event_id, customer_id, booked_by, source)
VALUES ('30000000-0000-0000-0000-000000012001', '19000000-0000-0000-0000-000000012004',
        '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000001', 'STAFF')
ON CONFLICT DO NOTHING;

-- The parent can test booking for the child without first completing an unrelated document flow.
INSERT INTO document_acceptance (id, document_version_id, customer_id, accepted_by, source)
SELECT '39000000-0000-0000-0000-000000012001'::UUID, '38000000-0000-0000-0000-000000000002'::UUID,
       '07000000-0000-0000-0000-000000000004'::UUID, '03000000-0000-0000-0000-000000000006'::UUID, 'APP'
WHERE NOT EXISTS (SELECT 1 FROM document_acceptance
    WHERE document_version_id = '38000000-0000-0000-0000-000000000002'
      AND customer_id = '07000000-0000-0000-0000-000000000004' AND revoked_at IS NULL)
ON CONFLICT DO NOTHING;

-- DEV/STAGE ONLY. Personal cancellations and disputes; reruns preserve tester decisions.
CREATE OR REPLACE FUNCTION pg_temp.seed_pt_action_at(day_offset INT, local_time TIME)
RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT (((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + day_offset) + local_time)
           AT TIME ZONE 'Europe/Warsaw'
$$;

INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by)
VALUES ('28000000-0000-0000-0000-000000013001', '07000000-0000-0000-0000-000000000003',
        '14000000-0000-0000-0000-000000010001', 'Pakiet PT — odwołania i zgłoszenia', 8, 3, 1120,
        'ACTIVE', CURRENT_DATE, CURRENT_DATE + 60, '03000000-0000-0000-0000-000000000001'),
       ('28000000-0000-0000-0000-000000013002', '07000000-0000-0000-0000-000000000001',
        '14000000-0000-0000-0000-000000010003', 'Pakiet PT bez limitu — odwołanie', NULL, NULL, 1800,
        'ACTIVE', CURRENT_DATE, CURRENT_DATE + 30, '03000000-0000-0000-0000-000000000001'),
       ('28000000-0000-0000-0000-000000013003', '07000000-0000-0000-0000-000000000003',
        '14000000-0000-0000-0000-000000010001', 'Pakiet PT — scenariusze odwołania', 8, 6, 1120,
        'ACTIVE', CURRENT_DATE, CURRENT_DATE + 60, '03000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at, capacity, visibility, status, created_by)
SELECT ('19000000-0000-0000-0000-' || lpad(slot::TEXT,12,'0'))::UUID,
       CASE WHEN slot=13012 THEN 'CLASS' ELSE 'PERSONAL_TRAINING' END,
       CASE WHEN slot=13012 THEN '10000000-0000-0000-0000-000000000005'::UUID
            ELSE '10000000-0000-0000-0000-000000000004'::UUID END,
       title, starts_at, starts_at + INTERVAL '1 hour', CASE WHEN slot=13012 THEN 8 ELSE 1 END,
       CASE WHEN slot=13012 THEN 'PUBLIC' ELSE 'PRIVATE' END, status,
       '03000000-0000-0000-0000-000000000002'
FROM (VALUES
    (13001, 'Personalny — odwołanie w terminie', pg_temp.seed_pt_action_at(2,'09:15'), 'SCHEDULED'),
    (13002, 'Personalny — późne odwołanie', CURRENT_TIMESTAMP + INTERVAL '3 hours', 'SCHEDULED'),
    (13003, 'Personalny — możliwość zgłoszenia', CURRENT_TIMESTAMP - INTERVAL '4 hours', 'COMPLETED'),
    (13004, 'Personalny — minął czas na zgłoszenie', CURRENT_TIMESTAMP - INTERVAL '29 hours', 'COMPLETED'),
    (13005, 'Personalny — rezerwacja z odróbki', pg_temp.seed_pt_action_at(3,'09:15'), 'SCHEDULED'),
    (13006, 'Personalny — pakiet bez limitu', pg_temp.seed_pt_action_at(3,'11:15'), 'SCHEDULED'),
    (13007, 'Personalny — bez pakietu', pg_temp.seed_pt_action_at(4,'09:15'), 'SCHEDULED'),
    (13008, 'Personalny — zgłoszenie w toku', CURRENT_TIMESTAMP - INTERVAL '5 hours', 'COMPLETED'),
    -- Fresh pair uses a separate package so earlier testing cannot exhaust its makeup limit.
    (13009, 'Personalny — odwołaj z odróbką', CURRENT_TIMESTAMP + INTERVAL '48 hours', 'SCHEDULED'),
    (13010, 'Personalny — odwołaj z utratą wejścia', CURRENT_TIMESTAMP + INTERVAL '6 hours', 'SCHEDULED'),
    (13011, 'Personalny — Zosia', CURRENT_TIMESTAMP + INTERVAL '49 hours', 'SCHEDULED'),
    (13012, 'Akrobatyka — Zosia', CURRENT_TIMESTAMP + INTERVAL '26 hours', 'SCHEDULED')
) AS examples(slot,title,starts_at,status)
ON CONFLICT DO NOTHING;

INSERT INTO event_staff (event_id,staff_id,role)
SELECT id, CASE WHEN id='19000000-0000-0000-0000-000000013006'::UUID
                THEN '08000000-0000-0000-0000-000000000003'::UUID
                ELSE '08000000-0000-0000-0000-000000000002'::UUID END, 'LEAD'
FROM schedule_event WHERE id BETWEEN '19000000-0000-0000-0000-000000013001' AND '19000000-0000-0000-0000-000000013012'
ON CONFLICT DO NOTHING;

INSERT INTO event_resource (event_id,resource_id,blocks_whole_room)
SELECT id, '15000000-0000-0000-0000-000000000002'::UUID,FALSE FROM schedule_event
WHERE id BETWEEN '19000000-0000-0000-0000-000000013001' AND '19000000-0000-0000-0000-000000013012'
ON CONFLICT DO NOTHING;

INSERT INTO reservation (id,event_id,customer_id,booked_by,source,customer_pass_id,free_cancel_until,
                         cancellation_allowed_snapshot,late_cancel_allowed_snapshot,late_cancel_forfeits_entry_snapshot,
                         trainer_confirmed_at,attendance,settlement_status)
SELECT ('30000000-0000-0000-0000-' || lpad(slot::TEXT,12,'0'))::UUID, event.id,
       CASE WHEN slot=13006 THEN '07000000-0000-0000-0000-000000000001'::UUID
            WHEN slot IN (13011,13012) THEN '07000000-0000-0000-0000-000000000004'::UUID
            ELSE '07000000-0000-0000-0000-000000000003'::UUID END,
       CASE WHEN slot=13006 THEN '03000000-0000-0000-0000-000000000004'::UUID
            ELSE '03000000-0000-0000-0000-000000000006'::UUID END, 'STAFF',
       CASE WHEN slot=13006 THEN '28000000-0000-0000-0000-000000013002'::UUID
            WHEN slot IN (13005,13007,13011,13012) THEN NULL
            WHEN slot IN (13009,13010) THEN '28000000-0000-0000-0000-000000013003'::UUID
            ELSE '28000000-0000-0000-0000-000000013001'::UUID END,
       event.starts_at - INTERVAL '24 hours', TRUE,TRUE,TRUE,
       CASE WHEN event.status='COMPLETED' THEN event.ends_at + INTERVAL '30 minutes' END,
       CASE WHEN event.status='COMPLETED' THEN 'PRESENT' ELSE 'UNMARKED' END,
       CASE WHEN slot=13008 THEN 'DISPUTED' ELSE 'OPEN' END
FROM generate_series(13001,13012) slot JOIN schedule_event event
  ON event.id=('19000000-0000-0000-0000-' || lpad(slot::TEXT,12,'0'))::UUID
ON CONFLICT DO NOTHING;

-- A pre-existing credit is used for 13005. Its expiry is never extended on cancellation.
INSERT INTO makeup_credit (id,customer_id,source_pass_id,granted_by,granted_at,expires_at,status,used_reservation_id,used_at)
VALUES ('33000000-0000-0000-0000-000000013001','07000000-0000-0000-0000-000000000003',
        '28000000-0000-0000-0000-000000013001','03000000-0000-0000-0000-000000000001',
        CURRENT_TIMESTAMP - INTERVAL '3 days', CURRENT_TIMESTAMP + INTERVAL '10 days','USED',
        '30000000-0000-0000-0000-000000013005',CURRENT_TIMESTAMP - INTERVAL '1 day')
ON CONFLICT DO NOTHING;

-- Attach only when creating the example; a tester may since have cancelled and reused the credit.
UPDATE reservation SET makeup_credit_id='33000000-0000-0000-0000-000000013001'
WHERE id='30000000-0000-0000-0000-000000013005' AND makeup_credit_id IS NULL AND version=0
  AND EXISTS (SELECT 1 FROM makeup_credit WHERE id='33000000-0000-0000-0000-000000013001'
              AND used_reservation_id='30000000-0000-0000-0000-000000013005' AND status='USED');

INSERT INTO pass_ledger (id,pass_id,kind,entries_delta)
VALUES ('34000000-0000-0000-0000-000000013001','28000000-0000-0000-0000-000000013001','ACTIVATION',8),
       ('34000000-0000-0000-0000-000000013002','28000000-0000-0000-0000-000000013002','ACTIVATION',0),
       ('34000000-0000-0000-0000-000000013003','28000000-0000-0000-0000-000000013003','ACTIVATION',8)
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id,pass_id,reservation_id,kind,entries_delta)
SELECT ('34000000-0000-0000-0000-' || lpad(slot::TEXT,12,'0'))::UUID, customer_pass_id, id, 'ENTRY_RESERVED',
       CASE WHEN customer_pass_id='28000000-0000-0000-0000-000000013002'::UUID THEN 0 ELSE -1 END
FROM (SELECT r.*, 13100 + row_number() OVER (ORDER BY r.id) AS slot FROM reservation r
      WHERE r.id BETWEEN '30000000-0000-0000-0000-000000013001' AND '30000000-0000-0000-0000-000000013012'
        AND r.customer_pass_id IS NOT NULL) entries
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id,pass_id,reservation_id,kind,entries_delta)
SELECT ('34000000-0000-0000-0000-' || lpad(slot::TEXT,12,'0'))::UUID, customer_pass_id, id, 'ENTRY_CONSUMED',0
FROM (SELECT r.*, 13200 + row_number() OVER (ORDER BY r.id) AS slot FROM reservation r
      WHERE r.id IN ('30000000-0000-0000-0000-000000013003','30000000-0000-0000-0000-000000013004','30000000-0000-0000-0000-000000013008')) entries
ON CONFLICT DO NOTHING;

INSERT INTO training_dispute (id,reservation_id,raised_by,reason,raised_at)
VALUES ('32000000-0000-0000-0000-000000013001','30000000-0000-0000-0000-000000013008',
        '03000000-0000-0000-0000-000000000006','Proszę o sprawdzenie rozliczenia treningu.',CURRENT_TIMESTAMP - INTERVAL '2 hours')
ON CONFLICT DO NOTHING;

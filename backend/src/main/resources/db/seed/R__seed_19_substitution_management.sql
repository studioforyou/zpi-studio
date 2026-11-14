-- DEV/STAGE: insert-only examples. Decisions, dates, balances and read state survive reruns.
WITH slots AS (
    SELECT ((day::DATE + hour::TIME) AT TIME ZONE 'Europe/Warsaw') AS candidate
    FROM generate_series((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + 1,
                         (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + 10, INTERVAL '1 day') day
    CROSS JOIN (VALUES ('10:00'), ('13:00'), ('16:00'), ('19:00')) hours(hour)
), free AS (
    SELECT candidate, row_number() OVER (ORDER BY candidate) AS ordinal FROM slots
    WHERE NOT EXISTS (
        SELECT 1 FROM schedule_event e
        WHERE e.status <> 'CANCELLED' AND e.starts_at < candidate + INTERVAL '1 hour' AND e.ends_at > candidate
          AND (EXISTS (SELECT 1 FROM event_staff es WHERE es.event_id=e.id AND es.staff_id IN (
              '08000000-0000-0000-0000-000000000002'::UUID, '08000000-0000-0000-0000-000000000003'::UUID))
            OR EXISTS (SELECT 1 FROM event_resource er WHERE er.event_id=e.id AND er.resource_id IN (
              '15000000-0000-0000-0000-000000000001'::UUID, '15000000-0000-0000-0000-000000000002'::UUID)))
    ) AND NOT EXISTS (
        SELECT 1 FROM staff_time_off t WHERE t.staff_id IN (
            '08000000-0000-0000-0000-000000000002'::UUID, '08000000-0000-0000-0000-000000000003'::UUID)
          AND t.status='APPROVED' AND t.starts_at < candidate + INTERVAL '1 hour' AND t.ends_at > candidate
    )
)
INSERT INTO schedule_event(id,kind,service_type_id,title,starts_at,ends_at,capacity,visibility,status,created_by)
SELECT ('19000000-0000-0000-0000-' || lpad((19000 + slot)::TEXT,12,'0'))::UUID,
       CASE WHEN slot=5 THEN 'CLASS' ELSE 'PERSONAL_TRAINING' END,
       CASE WHEN slot=5 THEN '10000000-0000-0000-0000-000000000001'::UUID ELSE '10000000-0000-0000-0000-000000000004'::UUID END,
       CASE WHEN slot=5 THEN 'Pole dance — zastępstwo' ELSE 'Trening personalny' END,
       candidate, candidate + INTERVAL '1 hour', CASE WHEN slot=5 THEN 10 ELSE 1 END,
       CASE WHEN slot=5 THEN 'PUBLIC' ELSE 'PRIVATE' END, 'SCHEDULED', '03000000-0000-0000-0000-000000000010'
FROM generate_series(1,8) slot JOIN free ON ordinal=CASE WHEN slot=8 THEN 7 ELSE slot END
WHERE slot<>7
ON CONFLICT DO NOTHING;

INSERT INTO event_staff(event_id,staff_id,role)
SELECT id, CASE WHEN id='19000000-0000-0000-0000-000000019006'::UUID THEN '08000000-0000-0000-0000-000000000002'::UUID
                ELSE '08000000-0000-0000-0000-000000000003'::UUID END, 'LEAD'
FROM schedule_event e WHERE id BETWEEN '19000000-0000-0000-0000-000000019001' AND '19000000-0000-0000-0000-000000019008'
AND id <> '19000000-0000-0000-0000-000000019007'
AND NOT EXISTS (SELECT 1 FROM event_staff s WHERE s.event_id=e.id) ON CONFLICT DO NOTHING;

INSERT INTO event_resource(event_id,resource_id,blocks_whole_room)
SELECT id, CASE WHEN id='19000000-0000-0000-0000-000000019005'::UUID THEN '15000000-0000-0000-0000-000000000001'::UUID
                ELSE '15000000-0000-0000-0000-000000000002'::UUID END, TRUE
FROM schedule_event e WHERE id BETWEEN '19000000-0000-0000-0000-000000019001' AND '19000000-0000-0000-0000-000000019008'
AND id <> '19000000-0000-0000-0000-000000019007'
AND NOT EXISTS (SELECT 1 FROM event_resource r WHERE r.event_id=e.id) ON CONFLICT DO NOTHING;

INSERT INTO substitution_request(id,event_id,original_staff_id,requested_by,reason,status,substitute_staff_id,claimed_at)
SELECT ('22000000-0000-0000-0000-' || lpad(slot::TEXT,12,'0'))::UUID, e.id,
       CASE WHEN slot=19006 THEN '08000000-0000-0000-0000-000000000002'::UUID ELSE '08000000-0000-0000-0000-000000000003'::UUID END,
       CASE WHEN slot=19006 THEN '03000000-0000-0000-0000-000000000002'::UUID ELSE '03000000-0000-0000-0000-000000000003'::UUID END,
       'Sprawa rodzinna', CASE WHEN slot=19002 THEN 'CLAIMED' ELSE 'OPEN' END,
       CASE WHEN slot=19002 THEN '08000000-0000-0000-0000-000000000002'::UUID END,
       CASE WHEN slot=19002 THEN CURRENT_TIMESTAMP END
FROM generate_series(19001,19008) slot JOIN schedule_event e ON e.id=('19000000-0000-0000-0000-'||lpad(slot::TEXT,12,'0'))::UUID
WHERE slot<>19007
ON CONFLICT DO NOTHING;

-- An overlapping personal session makes the third offer unavailable to Marek.
INSERT INTO schedule_event(id,kind,service_type_id,title,starts_at,ends_at,capacity,visibility,status,created_by)
SELECT '19000000-0000-0000-0000-000000019007',kind,service_type_id,'Termin zajęty',starts_at,ends_at,1,'PRIVATE','SCHEDULED',created_by
FROM schedule_event WHERE id='19000000-0000-0000-0000-000000019003' ON CONFLICT DO NOTHING;
INSERT INTO event_staff(event_id,staff_id,role)
SELECT id,'08000000-0000-0000-0000-000000000002','LEAD' FROM schedule_event
WHERE id='19000000-0000-0000-0000-000000019007' AND NOT EXISTS (SELECT 1 FROM event_staff WHERE event_id=schedule_event.id)
ON CONFLICT DO NOTHING;

-- The fourth offer overlaps approved time off, so it is also blocked for Marek.
INSERT INTO staff_time_off(id,staff_id,kind,starts_at,ends_at,status,reason,requested_at,decided_by,decided_at)
SELECT '17000000-0000-0000-0000-000000019001','08000000-0000-0000-0000-000000000002','UNAVAILABLE',
       starts_at,ends_at,'APPROVED','Przykład niedostępności',now(),'03000000-0000-0000-0000-000000000010',now()
FROM schedule_event WHERE id='19000000-0000-0000-0000-000000019004' ON CONFLICT DO NOTHING;

INSERT INTO customer_pass(id,customer_id,variant_id,product_name,entries_total,entries_remaining,price_paid,status,valid_from,valid_until,created_by)
VALUES('28000000-0000-0000-0000-000000019001','07000000-0000-0000-0000-000000000002',
       '14000000-0000-0000-0000-000000010001','Pakiet PT — zastępstwa',8,6,1120,'ACTIVE',CURRENT_DATE,CURRENT_DATE+60,
       '03000000-0000-0000-0000-000000000010') ON CONFLICT DO NOTHING;

INSERT INTO reservation(id,event_id,customer_id,customer_pass_id,booked_by,source,status,free_cancel_until,
                        cancellation_allowed_snapshot,late_cancel_allowed_snapshot,late_cancel_forfeits_entry_snapshot)
SELECT ('21000000-0000-0000-0000-'||lpad(slot::TEXT,12,'0'))::UUID,
       ('19000000-0000-0000-0000-'||lpad(slot::TEXT,12,'0'))::UUID,
       '07000000-0000-0000-0000-000000000002','28000000-0000-0000-0000-000000019001',
       '03000000-0000-0000-0000-000000000010','STAFF','ACTIVE',starts_at-INTERVAL '6 hours',TRUE,TRUE,TRUE
FROM (VALUES(19001),(19002)) examples(slot)
JOIN schedule_event e ON e.id=('19000000-0000-0000-0000-'||lpad(slot::TEXT,12,'0'))::UUID
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger(id,pass_id,kind,entries_delta,actor_account_id,reason)
VALUES('34000000-0000-0000-0000-000000019001','28000000-0000-0000-0000-000000019001',
       'ACTIVATION',8,'03000000-0000-0000-0000-000000000010','Aktywacja pakietu') ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger(pass_id,reservation_id,kind,entries_delta,actor_account_id)
SELECT customer_pass_id,id,'ENTRY_RESERVED',-1,'03000000-0000-0000-0000-000000000010'
FROM reservation r WHERE r.id IN ('21000000-0000-0000-0000-000000019001','21000000-0000-0000-0000-000000019002')
AND NOT EXISTS (SELECT 1 FROM pass_ledger l WHERE l.reservation_id=r.id AND l.kind='ENTRY_RESERVED')
ON CONFLICT DO NOTHING;

INSERT INTO notification(id,recipient_id,notification_type_id,recipient_role,title,body,payload,dedup_key,scheduled_for)
SELECT '29000000-0000-0000-0000-000000019001','03000000-0000-0000-0000-000000000002',13,'TRAINER',
       'Dostępne zastępstwo','Trening personalny · '||to_char(e.starts_at AT TIME ZONE 'Europe/Warsaw','DD.MM HH24:MI'),
       jsonb_build_object('requestId',r.id::TEXT,'title','Trening personalny','rooms','Sala treningowa 1',
                         'startsAt',e.starts_at::TEXT,'endsAt',e.ends_at::TEXT,'role','TRAINER'),
       'SUBSTITUTION_AVAILABLE:'||r.id::TEXT||':0:03000000-0000-0000-0000-000000000002',now()
FROM substitution_request r JOIN schedule_event e ON e.id=r.event_id
WHERE r.id='22000000-0000-0000-0000-000000019001' AND r.status='OPEN'
ON CONFLICT DO NOTHING;

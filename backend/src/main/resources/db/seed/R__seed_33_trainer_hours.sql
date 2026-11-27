-- Seed 33: trainer hours (#169), DEV/STAGE only.
-- Dates are relative to the first run. Related rows are inserted only with a new event,
-- so reruns preserve edits, cancellations and changed assignments. No balances are changed.
WITH examples(no,kind,service,title,days,hour,minutes,status,lead) AS (VALUES
    (1,'CLASS',3,'Raport godzin — prowadzenie i asysta',-3,TIME '06:00',75,'COMPLETED',2),
    (2,'PERSONAL_TRAINING',4,'Raport godzin — zatwierdzone zastępstwo',-2,TIME '06:00',45,'COMPLETED',2),
    (3,'CONSULTATION',NULL,'Raport godzin — konsultacja',-2,TIME '07:00',30,'SCHEDULED',3),
    (4,'WORKSHOP',6,'Raport godzin — warsztat',-1,TIME '06:00',90,'COMPLETED',3),
    (5,'CLASS',1,'Raport godzin — bez potwierdzenia',-1,TIME '08:00',60,'SCHEDULED',1),
    (6,'PERSONAL_TRAINING',4,'Raport godzin — odwołany trening',-1,TIME '09:00',60,'CANCELLED',2),
    (7,'CLASS',1,'Raport godzin — przyszłe zajęcia',1,TIME '06:00',60,'SCHEDULED',1),
    (8,'CONSULTATION',NULL,'Raport godzin — sama rezerwacja sali',-1,TIME '10:00',30,'SCHEDULED',NULL)
), inserted AS (
    INSERT INTO schedule_event(id,kind,service_type_id,title,starts_at,ends_at,capacity,visibility,status,
                               created_by,created_at,cancel_reason,cancel_note,cancelled_by,cancelled_at)
    SELECT ('19000000-0000-0000-0000-'||lpad((33000+no)::TEXT,12,'0'))::UUID,kind,
           CASE WHEN service IS NOT NULL THEN ('10000000-0000-0000-0000-'||lpad(service::TEXT,12,'0'))::UUID END,
           title,(((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE+days)+hour) AT TIME ZONE 'Europe/Warsaw',
           ((((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE+days)+hour) AT TIME ZONE 'Europe/Warsaw')+make_interval(mins=>minutes),
           CASE kind WHEN 'CLASS' THEN 10 WHEN 'WORKSHOP' THEN 8 WHEN 'PERSONAL_TRAINING' THEN 1 END,
           CASE WHEN kind IN ('CLASS','WORKSHOP') THEN 'PUBLIC' ELSE 'PRIVATE' END,status,
           '03000000-0000-0000-0000-000000000010',CURRENT_TIMESTAMP-INTERVAL '7 days',
           CASE WHEN status='CANCELLED' THEN 'OTHER' END,
           CASE WHEN status='CANCELLED' THEN 'Odwołany przykład — nie wlicza się do godzin' END,
           CASE WHEN status='CANCELLED' THEN '03000000-0000-0000-0000-000000000010'::UUID END,
           CASE WHEN status='CANCELLED' THEN CURRENT_TIMESTAMP-INTERVAL '2 days' END
    FROM examples ON CONFLICT DO NOTHING RETURNING id,starts_at
), assigned AS (
    INSERT INTO event_staff(event_id,staff_id,role)
    SELECT e.id,('08000000-0000-0000-0000-'||lpad(x.lead::TEXT,12,'0'))::UUID,'LEAD'
    FROM inserted e JOIN examples x ON e.id=('19000000-0000-0000-0000-'||lpad((33000+x.no)::TEXT,12,'0'))::UUID
    WHERE x.lead IS NOT NULL
    UNION ALL SELECT id,'08000000-0000-0000-0000-000000000001','ASSISTANT' FROM inserted
    WHERE id='19000000-0000-0000-0000-000000033001'
    UNION ALL SELECT id,'08000000-0000-0000-0000-000000000001','ASSISTANT' FROM inserted
    WHERE id='19000000-0000-0000-0000-000000033004'
    RETURNING event_id
), rooms AS (
    INSERT INTO event_resource(event_id,resource_id,blocks_whole_room)
    SELECT id,CASE WHEN id IN ('19000000-0000-0000-0000-000000033005','19000000-0000-0000-0000-000000033007')
                  THEN '15000000-0000-0000-0000-000000000001'::UUID
                  ELSE '15000000-0000-0000-0000-000000000003'::UUID END,TRUE FROM inserted
    UNION ALL SELECT id,'15000000-0000-0000-0000-000000000002',TRUE FROM inserted
    WHERE id='19000000-0000-0000-0000-000000033001'
    RETURNING event_id
)
INSERT INTO substitution_request(id,event_id,original_staff_id,requested_by,status,substitute_staff_id,
                                 claimed_at,decided_by,decided_at,created_at)
SELECT '22000000-0000-0000-0000-000000033001',id,'08000000-0000-0000-0000-000000000003',
       '03000000-0000-0000-0000-000000000003','APPROVED','08000000-0000-0000-0000-000000000002',
       starts_at-INTERVAL '2 days','03000000-0000-0000-0000-000000000010',
       starts_at-INTERVAL '1 day',starts_at-INTERVAL '3 days'
FROM inserted WHERE id='19000000-0000-0000-0000-000000033002';

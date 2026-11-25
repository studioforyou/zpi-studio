-- Seed 26: administrator consultations (#139), DEV/STAGE only.
-- Insert examples once; later guest, room, trainer and cancellation edits remain intact.
WITH examples(id,title,guest,hour,staff,status) AS (VALUES
    ('19000000-0000-0000-0000-000000026001'::UUID,'Konsultacja — nowy gość','Alicja Zielińska','08:15'::TIME,
     '08000000-0000-0000-0000-000000000002'::UUID,'SCHEDULED'),
    ('19000000-0000-0000-0000-000000026002'::UUID,'Konsultacja — rezerwacja sali',NULL,'08:45'::TIME,
     NULL::UUID,'SCHEDULED'),
    ('19000000-0000-0000-0000-000000026003'::UUID,'Konsultacja — odwołana','Robert Mazur','09:15'::TIME,
     '08000000-0000-0000-0000-000000000003'::UUID,'CANCELLED')
), inserted AS (
    INSERT INTO schedule_event(id,kind,title,guest_name,starts_at,ends_at,visibility,status,created_by,
                               cancel_reason,cancel_note,cancelled_by,cancelled_at)
    SELECT id,'CONSULTATION',title,guest,
           (((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE+1)+hour) AT TIME ZONE 'Europe/Warsaw',
           ((((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE+1)+hour) AT TIME ZONE 'Europe/Warsaw')+INTERVAL '30 minutes',
           'PRIVATE',status,'03000000-0000-0000-0000-000000000010',
           CASE WHEN status='CANCELLED' THEN 'OTHER' END,
           CASE WHEN status='CANCELLED' THEN 'Zmiana planów gościa' END,
           CASE WHEN status='CANCELLED' THEN '03000000-0000-0000-0000-000000000010'::UUID END,
           CASE WHEN status='CANCELLED' THEN CURRENT_TIMESTAMP END
    FROM examples ON CONFLICT DO NOTHING RETURNING id,status
), assigned AS (
    INSERT INTO event_staff(event_id,staff_id,role)
    SELECT e.id,x.staff,'LEAD' FROM inserted e JOIN examples x USING(id) WHERE x.staff IS NOT NULL RETURNING event_id
), rooms AS (
    INSERT INTO event_resource(event_id,resource_id,blocks_whole_room)
    SELECT id,'15000000-0000-0000-0000-000000000003',TRUE FROM inserted RETURNING event_id
)
INSERT INTO audit_log(actor_account_id,module,entity_type,entity_id,action,changes,reason)
SELECT '03000000-0000-0000-0000-000000000010','scheduling','schedule_event',id,'CANCELLED',
       '{"status":{"old":"SCHEDULED","new":"CANCELLED"}}'::jsonb,'Zmiana planów gościa'
FROM inserted WHERE status='CANCELLED';

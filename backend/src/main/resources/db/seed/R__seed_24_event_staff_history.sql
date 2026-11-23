-- Seed 24: event staff history (#138), DEV/STAGE only.
-- A personal session has an approved substitution followed by adding an assistant.
-- A group class has an older approved substitution without a staff audit entry.
-- All related examples are inserted only with their new event: reruns preserve later edits.
WITH examples(id, kind, service, title, hour, room, lead) AS (VALUES
    ('19000000-0000-0000-0000-000000024001'::UUID, 'PERSONAL_TRAINING',
     '10000000-0000-0000-0000-000000000004'::UUID, 'Trening personalny — historia prowadzących', '21:00'::TIME,
     '15000000-0000-0000-0000-000000000003'::UUID, '08000000-0000-0000-0000-000000000002'::UUID),
    ('19000000-0000-0000-0000-000000024002'::UUID, 'CLASS',
     '10000000-0000-0000-0000-000000000001'::UUID, 'Pole dance — historia zastępstwa', '22:00'::TIME,
     '15000000-0000-0000-0000-000000000001'::UUID, '08000000-0000-0000-0000-000000000003'::UUID)
), inserted_events AS (
    INSERT INTO schedule_event(id,kind,service_type_id,title,starts_at,ends_at,capacity,visibility,status,created_by)
    SELECT id,kind,service,title,
           (((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE-1)+hour) AT TIME ZONE 'Europe/Warsaw',
           ((((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE-1)+hour) AT TIME ZONE 'Europe/Warsaw')+INTERVAL '1 hour',
           CASE WHEN kind='CLASS' THEN 10 ELSE 1 END, CASE WHEN kind='CLASS' THEN 'PUBLIC' ELSE 'PRIVATE' END,
           'COMPLETED','03000000-0000-0000-0000-000000000010'
    FROM examples ON CONFLICT DO NOTHING RETURNING id,starts_at
), inserted_staff AS (
    INSERT INTO event_staff(event_id,staff_id,role)
    SELECT e.id,x.lead,'LEAD' FROM inserted_events e JOIN examples x USING(id)
    UNION ALL SELECT id,'08000000-0000-0000-0000-000000000003','ASSISTANT'
    FROM inserted_events WHERE id='19000000-0000-0000-0000-000000024001'
    RETURNING event_id
), inserted_rooms AS (
    INSERT INTO event_resource(event_id,resource_id,blocks_whole_room)
    SELECT e.id,x.room,TRUE FROM inserted_events e JOIN examples x USING(id) RETURNING event_id
), inserted_substitutions AS (
    INSERT INTO substitution_request(id,event_id,original_staff_id,requested_by,status,substitute_staff_id,
                                     claimed_at,decided_by,decided_at,created_at)
    SELECT CASE e.id WHEN '19000000-0000-0000-0000-000000024001'::UUID
                THEN '22000000-0000-0000-0000-000000024001'::UUID ELSE '22000000-0000-0000-0000-000000024002'::UUID END,
           e.id,CASE x.kind WHEN 'PERSONAL_TRAINING' THEN '08000000-0000-0000-0000-000000000003'::UUID
                ELSE '08000000-0000-0000-0000-000000000001'::UUID END,
           CASE x.kind WHEN 'PERSONAL_TRAINING' THEN '03000000-0000-0000-0000-000000000003'::UUID
                ELSE '03000000-0000-0000-0000-000000000001'::UUID END,
           'APPROVED',x.lead,e.starts_at-INTERVAL '3 days 1 hour',
           '03000000-0000-0000-0000-000000000010',e.starts_at-INTERVAL '3 days',e.starts_at-INTERVAL '4 days'
    FROM inserted_events e JOIN examples x USING(id) RETURNING id
), members AS (
    SELECT jsonb_build_object('staffId',id,'displayName',concat_ws(' ',first_name,last_name),'role','LEAD') AS lead,
           jsonb_build_object('staffId',id,'displayName',concat_ws(' ',first_name,last_name),'role','ASSISTANT') AS assistant,id
    FROM staff_profile WHERE id IN ('08000000-0000-0000-0000-000000000002','08000000-0000-0000-0000-000000000003')
)
INSERT INTO audit_log(occurred_at,actor_account_id,module,entity_type,entity_id,action,changes)
SELECT e.starts_at-INTERVAL '3 days','03000000-0000-0000-0000-000000000010'::UUID,'scheduling','schedule_event',e.id,
       'STAFF_SUBSTITUTION',jsonb_build_object(
           'staff',jsonb_build_object('old',jsonb_build_array(ewa.lead),'new',jsonb_build_array(marek.lead)),
           'substitutionRequestId',jsonb_build_object('old',NULL,'new','22000000-0000-0000-0000-000000024001'))
FROM inserted_events e CROSS JOIN members marek CROSS JOIN members ewa
WHERE e.id='19000000-0000-0000-0000-000000024001'
  AND marek.id='08000000-0000-0000-0000-000000000002' AND ewa.id='08000000-0000-0000-0000-000000000003'
UNION ALL
SELECT e.starts_at-INTERVAL '2 days','03000000-0000-0000-0000-000000000010'::UUID,'scheduling','schedule_event',e.id,
       'STAFF_MANUAL',jsonb_build_object('staff',jsonb_build_object(
           'old',jsonb_build_array(marek.lead),'new',jsonb_build_array(marek.lead,ewa.assistant)))
FROM inserted_events e CROSS JOIN members marek CROSS JOIN members ewa
WHERE e.id='19000000-0000-0000-0000-000000024001'
  AND marek.id='08000000-0000-0000-0000-000000000002' AND ewa.id='08000000-0000-0000-0000-000000000003';

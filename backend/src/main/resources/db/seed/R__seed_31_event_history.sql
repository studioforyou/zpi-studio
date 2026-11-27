-- Seed 31: occurrence history (#138), DEV/STAGE only.
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
    INSERT INTO schedule_event(id,kind,service_type_id,title,starts_at,ends_at,capacity,visibility,status,created_by,created_at)
    SELECT id,kind,service,title,
           (((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE-1)+hour) AT TIME ZONE 'Europe/Warsaw',
           ((((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE-1)+hour) AT TIME ZONE 'Europe/Warsaw')+INTERVAL '1 hour',
           CASE WHEN kind='CLASS' THEN 10 ELSE 1 END, CASE WHEN kind='CLASS' THEN 'PUBLIC' ELSE 'PRIVATE' END,
           'COMPLETED','03000000-0000-0000-0000-000000000010',CURRENT_TIMESTAMP-INTERVAL '6 days'
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

-- Older versions of this fixture backdated decisions but used now() for creation.
-- Repair only that impossible creation order; do not move sessions or change user decisions.
UPDATE schedule_event e SET created_at=first_change.at-INTERVAL '1 day'
FROM (SELECT entity_id,min(occurred_at) AS at FROM (
          SELECT entity_id,occurred_at FROM audit_log WHERE module='scheduling' AND entity_type='schedule_event'
          UNION ALL SELECT event_id,decided_at FROM substitution_request WHERE status='APPROVED' AND decided_at IS NOT NULL
      ) changes WHERE entity_id IN ('19000000-0000-0000-0000-000000024001','19000000-0000-0000-0000-000000024002')
      GROUP BY entity_id) first_change
WHERE e.id=first_change.entity_id AND e.created_at>first_change.at;

-- A single occurrence shows a combined edit, a force-run decision and cancellation.
WITH inserted AS (
    INSERT INTO schedule_event(id,kind,service_type_id,title,starts_at,ends_at,capacity,visibility,status,
                               created_by,created_at,force_run,cancel_reason,cancel_note,cancelled_by,cancelled_at)
    SELECT '19000000-0000-0000-0000-000000024003','CLASS','10000000-0000-0000-0000-000000000003',
           'Calisthenics — historia zajęć',
           (((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE-1)+TIME '18:30') AT TIME ZONE 'Europe/Warsaw',
           (((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE-1)+TIME '19:45') AT TIME ZONE 'Europe/Warsaw',
           8,'PUBLIC','CANCELLED','03000000-0000-0000-0000-000000000010',CURRENT_TIMESTAMP-INTERVAL '6 days',
           TRUE,'OTHER','Prace w sali','03000000-0000-0000-0000-000000000010',CURRENT_TIMESTAMP-INTERVAL '2 days'
    ON CONFLICT DO NOTHING RETURNING id,starts_at,ends_at
), assigned AS (
    INSERT INTO event_staff(event_id,staff_id,role)
    SELECT id,'08000000-0000-0000-0000-000000000002','LEAD' FROM inserted RETURNING event_id
), rooms AS (
    INSERT INTO event_resource(event_id,resource_id,blocks_whole_room)
    SELECT id,'15000000-0000-0000-0000-000000000003',TRUE FROM inserted RETURNING event_id
), room_values AS (
    SELECT jsonb_build_array(jsonb_build_object('resourceId',r.id,'name',r.name,'kind',r.kind,'blocksWholeRoom',TRUE)) AS value,r.id
    FROM resource r WHERE id IN ('15000000-0000-0000-0000-000000000002','15000000-0000-0000-0000-000000000003')
)
INSERT INTO audit_log(occurred_at,actor_account_id,module,entity_type,entity_id,action,changes,reason)
SELECT now()-INTERVAL '4 days','03000000-0000-0000-0000-000000000010'::UUID,'scheduling','schedule_event',e.id,
       'EVENT_MANUAL',jsonb_build_object(
           'time',jsonb_build_object('old',jsonb_build_object('startsAt',e.starts_at-INTERVAL '90 minutes','endsAt',e.starts_at-INTERVAL '30 minutes'),
                                    'new',jsonb_build_object('startsAt',e.starts_at,'endsAt',e.ends_at)),
           'resources',jsonb_build_object('old',old_room.value,'new',new_room.value),
           'title',jsonb_build_object('old','Calisthenics — trening grupowy','new','Calisthenics — historia zajęć'),
           'capacity',jsonb_build_object('old',10,'new',8)),NULL
FROM inserted e CROSS JOIN room_values old_room CROSS JOIN room_values new_room
WHERE old_room.id='15000000-0000-0000-0000-000000000002' AND new_room.id='15000000-0000-0000-0000-000000000003'
UNION ALL
SELECT now()-INTERVAL '3 days','03000000-0000-0000-0000-000000000010'::UUID,'scheduling','schedule_event',id,
       'EVENT_FORCE_RUN_CHANGED','{"forceRun":{"old":false,"new":true}}'::jsonb,NULL FROM inserted
UNION ALL
SELECT now()-INTERVAL '2 days','03000000-0000-0000-0000-000000000010'::UUID,'scheduling','schedule_event',id,
       'CANCELLED','{"status":{"old":"SCHEDULED","new":"CANCELLED"}}'::jsonb,'Prace w sali' FROM inserted;

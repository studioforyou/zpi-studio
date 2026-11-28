-- Seed 34: monthly settlements and payout history (#301), DEV/STAGE only.
-- Real business tables only. Insert once; preserve later approvals, corrections and ledger edits.
WITH examples(no,staff,months_ago,hour,minutes,status) AS (VALUES
    (1,1,1,TIME '06:00',60,'COMPLETED'), (2,2,1,TIME '07:00',60,'COMPLETED'),
    (3,3,1,TIME '08:00',45,'COMPLETED'), (4,5,1,TIME '09:00',90,'COMPLETED'),
    (5,1,1,TIME '11:00',30,'SCHEDULED'),
    (6,1,2,TIME '06:00',75,'COMPLETED'), (7,1,2,TIME '08:00',30,'SCHEDULED'),
    (8,2,2,TIME '09:00',45,'COMPLETED'), (9,3,2,TIME '10:00',60,'COMPLETED'),
    (10,3,2,TIME '11:00',30,'SCHEDULED'), (11,5,2,TIME '12:00',90,'COMPLETED')
), inserted AS (
    INSERT INTO schedule_event(id,kind,title,starts_at,ends_at,visibility,status,created_by)
    SELECT ('19000000-0000-0000-0000-'||lpad((34000+no)::TEXT,12,'0'))::UUID,'CONSULTATION',
           CASE WHEN status='SCHEDULED' THEN 'Rozliczenie — konsultacja bez potwierdzenia' ELSE 'Rozliczenie — konsultacja historyczna' END,
           ((date_trunc('month',CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE-make_interval(months=>months_ago)+INTERVAL '4 days')::DATE+hour) AT TIME ZONE 'Europe/Warsaw',
           (((date_trunc('month',CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE-make_interval(months=>months_ago)+INTERVAL '4 days')::DATE+hour) AT TIME ZONE 'Europe/Warsaw')+make_interval(mins=>minutes),
           'PRIVATE',status,'03000000-0000-0000-0000-000000000010'
    FROM examples ON CONFLICT DO NOTHING RETURNING id
), assigned AS (
    INSERT INTO event_staff(event_id,staff_id,role)
    SELECT e.id,('08000000-0000-0000-0000-'||lpad(x.staff::TEXT,12,'0'))::UUID,'LEAD'
    FROM inserted e JOIN examples x ON e.id=('19000000-0000-0000-0000-'||lpad((34000+x.no)::TEXT,12,'0'))::UUID
    RETURNING event_id
)
INSERT INTO event_resource(event_id,resource_id,blocks_whole_room)
SELECT id,'15000000-0000-0000-0000-000000000003',TRUE FROM inserted;

WITH examples(no,staff,months_ago,amount,include_unconfirmed,version) AS (VALUES
    (1,1,1,450.00,FALSE,0), (2,2,1,700.00,FALSE,1),
    (3,3,1,300.00,FALSE,1), (4,5,1,500.00,FALSE,4),
    (5,1,2,1000.50,TRUE,2), (6,2,2,0.00,FALSE,0),
    (7,3,2,625.50,FALSE,1), (8,5,2,900.00,FALSE,0)
),
inserted AS (
    INSERT INTO trainer_settlement(id,staff_id,month,currency,version)
    SELECT ('33000000-0000-0000-0000-'||lpad((34000+no)::TEXT,12,'0'))::UUID,
           ('08000000-0000-0000-0000-'||lpad(staff::TEXT,12,'0'))::UUID,
           (date_trunc('month',CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')-make_interval(months=>months_ago))::DATE,
           COALESCE((SELECT currency FROM studio LIMIT 1),'PLN'),
           version
    FROM examples ON CONFLICT DO NOTHING RETURNING *
), seed_rows AS (
    SELECT h.*,x.no,x.amount,x.include_unconfirmed FROM inserted h JOIN examples x ON h.id=('33000000-0000-0000-0000-'||lpad((34000+x.no)::TEXT,12,'0'))::UUID
), work AS (
    SELECT h.id AS settlement_id,e.id,e.kind,COALESCE(NULLIF(e.title,''),service.name,'') AS title,e.starts_at,e.ends_at,
           es.role,EXTRACT(EPOCH FROM e.ends_at-e.starts_at)::BIGINT AS seconds,e.status='COMPLETED' AS confirmed,
           COALESCE((SELECT string_agg(DISTINCT r.name,', ' ORDER BY r.name) FROM event_resource er JOIN resource r ON r.id=er.resource_id
                     WHERE er.event_id=e.id AND r.kind='ROOM'),'') AS rooms
    FROM seed_rows h JOIN event_staff es ON es.staff_id=h.staff_id JOIN schedule_event e ON e.id=es.event_id
    LEFT JOIN service_type service ON service.id=e.service_type_id
    WHERE e.kind IN ('CLASS','WORKSHOP','PERSONAL_TRAINING','CONSULTATION') AND e.status<>'CANCELLED' AND e.ends_at<=CURRENT_TIMESTAMP
      AND (e.starts_at AT TIME ZONE COALESCE((SELECT time_zone FROM studio LIMIT 1),'Europe/Warsaw'))::DATE>=h.month
      AND (e.starts_at AT TIME ZONE COALESCE((SELECT time_zone FROM studio LIMIT 1),'Europe/Warsaw'))::DATE<(h.month+INTERVAL '1 month')::DATE
), grouped AS (
    SELECT h.id,
           jsonb_build_object('seconds',COALESCE(SUM(w.seconds),0),'leadSeconds',COALESCE(SUM(w.seconds) FILTER(WHERE w.role='LEAD'),0),
             'assistantSeconds',COALESCE(SUM(w.seconds) FILTER(WHERE w.role='ASSISTANT'),0),'events',COUNT(DISTINCT w.id),
             'unconfirmedSeconds',COALESCE(SUM(w.seconds) FILTER(WHERE NOT w.confirmed),0),'unconfirmedEvents',COUNT(DISTINCT w.id) FILTER(WHERE NOT w.confirmed)) AS counts,
           COALESCE(jsonb_agg(jsonb_build_object('eventId',w.id,'kind',w.kind,'title',w.title,'startsAt',w.starts_at,'endsAt',w.ends_at,
              'role',w.role,'seconds',w.seconds,'confirmed',w.confirmed,'rooms',w.rooms) ORDER BY w.starts_at DESC,w.id) FILTER(WHERE w.id IS NOT NULL),'[]'::JSONB) AS sessions
    FROM seed_rows h LEFT JOIN work w ON w.settlement_id=h.id GROUP BY h.id
), snapshots AS (
    SELECT h.*,jsonb_build_object('studioName',COALESCE((SELECT name FROM studio LIMIT 1),'Studio'),'generatedAt',CURRENT_TIMESTAMP-INTERVAL '20 minutes',
       'report',jsonb_build_object('from',h.month,'to',(h.month+INTERVAL '1 month'-INTERVAL '1 day')::DATE,
       'timeZone',COALESCE((SELECT time_zone FROM studio LIMIT 1),'Europe/Warsaw'),'totals',g.counts,
       'instructors',jsonb_build_array(jsonb_build_object('id',h.staff_id,'displayName',s.first_name||' '||s.last_name,'active',s.status='ACTIVE','counts',g.counts,'sessions',g.sessions)))) AS snapshot
    FROM seed_rows h JOIN grouped g ON g.id=h.id JOIN staff_profile s ON s.id=h.staff_id
), revisions AS (
    INSERT INTO trainer_settlement_revision(id,settlement_id,revision,amount,include_unconfirmed,snapshot,reason,actor_id,actor_label,created_at)
    SELECT id,id,1,CASE no WHEN 4 THEN 400.00 ELSE amount END,include_unconfirmed,snapshot,
           CASE no WHEN 5 THEN 'Rozliczenie z uwzględnieniem godzin bez potwierdzenia'
                   WHEN 6 THEN 'Brak kwoty do wypłaty w tym miesiącu'
                   ELSE 'Miesięczne rozliczenie — przykład' END,
           '03000000-0000-0000-0000-000000000010'::UUID,'beata.lis@seed.studioforyou.pl',CURRENT_TIMESTAMP-INTERVAL '20 minutes' FROM snapshots
    UNION ALL SELECT '34000000-0000-0000-0000-000000034001',id,2,amount,FALSE,snapshot,'Korekta uzgodnionej kwoty z 400 do 500 PLN',
           '03000000-0000-0000-0000-000000000010'::UUID,'beata.lis@seed.studioforyou.pl',CURRENT_TIMESTAMP-INTERVAL '15 minutes' FROM snapshots WHERE no=4
    RETURNING settlement_id
), payouts(no,suffix,amount,reverses,method,note) AS (VALUES
    (2,1,200.00,NULL::INTEGER,'TRANSFER','Pierwsza część wypłaty'),
    (3,2,300.00,NULL,'TRANSFER','Wypłata w całości'),
    (4,3,150.00,NULL,'TRANSFER','Wpis z błędną kwotą'),
    (4,4,150.00,3,'TRANSFER','Wycofanie błędnego wpisu'),
    (4,5,100.00,NULL,'TRANSFER','Poprawny wpis wypłaty'),
    (5,6,400.00,NULL,'CASH','Pierwsza część — gotówka'),
    (5,7,600.50,NULL,'TRANSFER','Druga część — rozliczenie całości'),
    (7,8,250.25,NULL,'OTHER','Częściowa wypłata inną metodą')
)
INSERT INTO trainer_payout(id,settlement_id,amount,paid_on,method,note,reverses_id,actor_id,actor_label,created_at)
SELECT ('35000000-0000-0000-0000-'||lpad((34000+p.suffix)::TEXT,12,'0'))::UUID,h.id,p.amount,
       (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE,p.method,p.note,
       CASE WHEN p.reverses IS NOT NULL THEN ('35000000-0000-0000-0000-'||lpad((34000+p.reverses)::TEXT,12,'0'))::UUID END,
       '03000000-0000-0000-0000-000000000010','beata.lis@seed.studioforyou.pl',CURRENT_TIMESTAMP-INTERVAL '10 minutes'+make_interval(mins=>p.suffix)
FROM seed_rows h JOIN payouts p ON p.no=h.no;

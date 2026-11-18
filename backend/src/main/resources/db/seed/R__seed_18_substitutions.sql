-- Upcoming group and personal sessions: own open requests, a claimed request,
-- and another trainer's request. Insert only; reruns preserve a tester's withdrawal.
INSERT INTO substitution_request (id, event_id, original_staff_id, requested_by, reason, status,
                                  substitute_staff_id, claimed_at)
SELECT sample.id::UUID, event.id, staff.id, staff.account_id, sample.reason, sample.status,
       CASE WHEN sample.status = 'CLAIMED' THEN '08000000-0000-0000-0000-000000000003'::UUID END,
       CASE WHEN sample.status = 'CLAIMED' THEN CURRENT_TIMESTAMP END
FROM (VALUES
    ('22000000-0000-0000-0000-000000127001', '08000000-0000-0000-0000-000000000002', 'CLASS', 0, 'Wyjazd', 'OPEN'),
    ('22000000-0000-0000-0000-000000127002', '08000000-0000-0000-0000-000000000002', 'PERSONAL_TRAINING', 0, 'Zmiana planów', 'OPEN'),
    ('22000000-0000-0000-0000-000000127003', '08000000-0000-0000-0000-000000000002', 'PERSONAL_TRAINING', 1, 'Wizyta lekarska', 'CLAIMED'),
    ('22000000-0000-0000-0000-000000127004', '08000000-0000-0000-0000-000000000001', 'CLASS', 0, 'Sprawa rodzinna', 'OPEN')
) AS sample(id, staff_id, kind, skip, reason, status)
JOIN staff_profile staff ON staff.id = sample.staff_id::UUID
CROSS JOIN LATERAL (
    SELECT e.id FROM schedule_event e
    JOIN event_staff s ON s.event_id = e.id AND s.staff_id = staff.id AND s.role = 'LEAD'
    WHERE e.status = 'SCHEDULED' AND e.kind = sample.kind AND e.starts_at > CURRENT_TIMESTAMP
      AND NOT EXISTS (SELECT 1 FROM substitution_request r WHERE r.event_id = e.id AND r.status IN ('OPEN', 'CLAIMED'))
    ORDER BY e.starts_at, e.id OFFSET sample.skip LIMIT 1
) AS event
ON CONFLICT DO NOTHING;

-- Seed 16: history of account statuses, roles and permissions (#63)
--
-- DEV/STAGE TEST DATA ONLY. See R__seed_01_identity.sql for the rules.
--
-- Adds the changes behind the seeded state, so the administrator's account history has
-- every kind of entry: account creation and activation, granted and revoked roles, and added and
-- removed permission overrides. Final roles and overrides stay as seed 01 sets them.
--   Beata Lis           created as an administrator by Anna
--   Karolina Szymańska  invited by Anna, accepted the invitation, then got the trainer role
--   Ewa Wiśniewska      attendance correction taken away by Anna (seed 01 override)
--   Tomasz Lewandowski  trainer role granted by mistake and revoked the next day by Beata
--   Marek Nowak         given the whole schedule for a colleague's leave, back to his defaults later

INSERT INTO account_status_change (id, account_id, from_status, to_status, changed_by, reason, changed_at) VALUES
    ('06000000-0000-0000-0000-000000000008', '03000000-0000-0000-0000-000000000010', NULL,      'ACTIVE',  '03000000-0000-0000-0000-000000000001', NULL,                  now() - interval '170 days'),
    ('06000000-0000-0000-0000-000000000009', '03000000-0000-0000-0000-000000000011', NULL,      'INVITED', '03000000-0000-0000-0000-000000000001', NULL,                  now() - interval '111 days'),
    ('06000000-0000-0000-0000-000000000010', '03000000-0000-0000-0000-000000000011', 'INVITED', 'ACTIVE',  NULL,                                   'Invitation accepted', now() - interval '110 days')
ON CONFLICT DO NOTHING;

-- audit_log has an identity key, so rows are matched by correlation_id instead of ON CONFLICT.
INSERT INTO audit_log (occurred_at, actor_account_id, module, entity_type, entity_id, action, changes, reason, correlation_id)
SELECT v.occurred_at, v.actor_account_id::uuid, 'identity', 'account', v.entity_id::uuid, v.action, v.changes::jsonb, NULL, v.correlation_id::uuid
FROM (VALUES
    (now() - interval '170 days',                         '03000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000010', 'ROLE_GRANTED',
     '{"roles": {"old": [], "new": ["ADMIN"]}}',                                          '47000000-0000-0000-0000-000000000006'),
    (now() - interval '111 days',                         '03000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000011', 'ROLE_GRANTED',
     '{"roles": {"old": [], "new": ["CLIENT"]}}',                                         '47000000-0000-0000-0000-000000000007'),
    (now() - interval '110 days' + interval '2 hours',    '03000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000011', 'ROLE_GRANTED',
     '{"roles": {"old": ["CLIENT"], "new": ["CLIENT", "TRAINER"]}}',                      '47000000-0000-0000-0000-000000000008'),
    (now() - interval '100 days',                         '03000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000003', 'PERMISSION_OVERRIDE_ADDED',
     '{"permissionOverride.ATTENDANCE_CORRECT": {"old": null, "new": "REVOKE"}}',         '47000000-0000-0000-0000-000000000009'),
    (now() - interval '40 days',                          '03000000-0000-0000-0000-000000000010', '03000000-0000-0000-0000-000000000005', 'ROLE_GRANTED',
     '{"roles": {"old": ["CLIENT"], "new": ["CLIENT", "TRAINER"]}}',                      '47000000-0000-0000-0000-000000000010'),
    (now() - interval '39 days',                          '03000000-0000-0000-0000-000000000010', '03000000-0000-0000-0000-000000000005', 'ROLE_REVOKED',
     '{"roles": {"old": ["CLIENT", "TRAINER"], "new": ["CLIENT"]}}',                      '47000000-0000-0000-0000-000000000011'),
    (now() - interval '20 days',                          '03000000-0000-0000-0000-000000000010', '03000000-0000-0000-0000-000000000002', 'PERMISSION_OVERRIDE_ADDED',
     '{"permissionOverride.SCHEDULE_MANAGE_ALL": {"old": null, "new": "GRANT"}}',         '47000000-0000-0000-0000-000000000012'),
    (now() - interval '13 days',                          '03000000-0000-0000-0000-000000000010', '03000000-0000-0000-0000-000000000002', 'PERMISSION_OVERRIDE_REMOVED',
     '{"permissionOverride.SCHEDULE_MANAGE_ALL": {"old": "GRANT", "new": null}}',         '47000000-0000-0000-0000-000000000013')
) AS v(occurred_at, actor_account_id, entity_id, action, changes, correlation_id)
WHERE NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.correlation_id = v.correlation_id::uuid);

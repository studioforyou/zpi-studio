-- Seed 28: change history of participant profiles, as trainers see it (#56)
-- DEV/STAGE TEST DATA ONLY. Conventions: see R__seed_01_identity.sql.
-- Audit log entries (correlation ids 47...026001..) that end in the current seed values, so the
-- history matches the profile and the card. Each entry is added once and never changed. The ids
-- keep 026 from the first version of this file (seed 26), so a database seeded with it gets no
-- duplicates.
--
--   Katarzyna (trainer Ewa, consent): phone changed by herself, emergency contact changed by Beata
--     in the panel, goals changed by Ewa, her injury note added by herself and archived by Ewa.
--   Tomasz (trainer Marek, consent): sport experience filled in by Marek, birth date and phone
--     changed by himself, his knee note added by himself.

INSERT INTO audit_log (occurred_at, actor_account_id, module, entity_type, entity_id, action, changes, reason, correlation_id)
SELECT v.occurred_at, v.actor_account_id::uuid, v.module, v.entity_type, v.entity_id::uuid, v.action, v.changes::jsonb, NULL, v.correlation_id::uuid
FROM (VALUES
    -- Katarzyna
    (now() - interval '40 days',                    '03000000-0000-0000-0000-000000000004', 'trainings', 'health_note',      '35000000-0000-0000-0000-000000015002', 'HEALTH_NOTE_ADDED',
     NULL,                                                                                                                            '47000000-0000-0000-0000-000000026001'),
    (now() - interval '18 days',                    '03000000-0000-0000-0000-000000000003', 'trainings', 'trainee_card',     '07000000-0000-0000-0000-000000000001', 'CARD_UPDATED',
     '{"goals": {"old": "Szpagat.", "new": "Szpagat i figury odwrócone."}}',                                                          '47000000-0000-0000-0000-000000026002'),
    (now() - interval '12 days',                    '03000000-0000-0000-0000-000000000003', 'trainings', 'health_note',      '35000000-0000-0000-0000-000000015002', 'HEALTH_NOTE_ARCHIVED',
     NULL,                                                                                                                            '47000000-0000-0000-0000-000000026003'),
    (now() - interval '6 days',                     '03000000-0000-0000-0000-000000000010', 'customer',  'customer_profile', '07000000-0000-0000-0000-000000000001', 'PROFILE_UPDATED',
     '{"emergencyContactName": {"old": "Anna Zielińska", "new": "Jan Zieliński"}, "emergencyPhone": {"old": "+48600100198", "new": "+48600100199"}}',
                                                                                                                                      '47000000-0000-0000-0000-000000026004'),
    (now() - interval '1 day',                      '03000000-0000-0000-0000-000000000004', 'customer',  'customer_profile', '07000000-0000-0000-0000-000000000001', 'PROFILE_UPDATED',
     '{"phone": {"old": "+48600100100", "new": "+48600100101"}}',                                                                     '47000000-0000-0000-0000-000000026005'),
    -- Tomasz
    (now() - interval '80 days',                    '03000000-0000-0000-0000-000000000005', 'trainings', 'health_note',      '35000000-0000-0000-0000-000000000001', 'HEALTH_NOTE_ADDED',
     NULL,                                                                                                                            '47000000-0000-0000-0000-000000026006'),
    (now() - interval '20 days',                    '03000000-0000-0000-0000-000000000002', 'trainings', 'trainee_card',     '07000000-0000-0000-0000-000000000002', 'CARD_UPDATED',
     '{"sportExperience": {"old": null, "new": "Siłownia 2 lata, piłka nożna amatorsko."}}',                                         '47000000-0000-0000-0000-000000026007'),
    (now() - interval '3 days',                     '03000000-0000-0000-0000-000000000005', 'customer',  'customer_profile', '07000000-0000-0000-0000-000000000002', 'PROFILE_UPDATED',
     '{"birthDate": {"old": null, "new": "1988-09-30"}, "phone": {"old": "+48500900102", "new": "+48600100102"}}',                   '47000000-0000-0000-0000-000000026008')
) AS v(occurred_at, actor_account_id, module, entity_type, entity_id, action, changes, correlation_id)
WHERE NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.correlation_id = v.correlation_id::uuid)
  -- The health entries need their note, which a tester may have removed with a fresh card.
  AND (v.entity_type <> 'health_note' OR EXISTS (SELECT 1 FROM health_note n WHERE n.id = v.entity_id::uuid));

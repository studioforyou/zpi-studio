-- Seed 05: trainee cards, health and trainer notes, documents and consents
-- DEV/STAGE TEST DATA ONLY. Conventions: see R__seed_01_identity.sql.
--
-- Documents (37...NN):
--   01 Regulamin (v1 old, v2 current)   02 RODO   03 Zgoda pole dance (PDF)   04 Zgoda rodzica   05 Zgoda marketing e-mail

INSERT INTO trainee_card (customer_id, goals, sport_experience, updated_by, updated_at) VALUES
    ('07000000-0000-0000-0000-000000000002', 'Pierwsze podciągnięcie na drążku, redukcja 5 kg.', 'Siłownia 2 lata, piłka nożna amatorsko.', '03000000-0000-0000-0000-000000000002', now() - interval '20 days'),
    ('07000000-0000-0000-0000-000000000001', 'Szpagat i figury odwrócone.',                     'Taniec współczesny w liceum.',             '03000000-0000-0000-0000-000000000003', now() - interval '15 days')
ON CONFLICT DO NOTHING;

INSERT INTO health_note (id, customer_id, kind, description, reported_by, created_at, archived_at) VALUES
    ('35000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000002', 'INJURY',           'Uraz lewego kolana (2023), unikać skoków.',    '03000000-0000-0000-0000-000000000005', now() - interval '80 days', NULL),
    ('35000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000001', 'CONDITION',        'Astma wysiłkowa, inhalator w torbie.',         '03000000-0000-0000-0000-000000000004', now() - interval '20 days', NULL),
    ('35000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000002', 'CONTRAINDICATION', 'Naciągnięty bark - bez wiszenia przez 2 tyg.', '03000000-0000-0000-0000-000000000002', now() - interval '60 days', now() - interval '45 days')
ON CONFLICT DO NOTHING;

INSERT INTO trainer_note (id, customer_id, author_staff_id, body, created_at, updated_at) VALUES
    ('36000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000002', '08000000-0000-0000-0000-000000000002', 'Dobra technika przysiadu, popracować nad mobilnością bioder.', now() - interval '6 days',  now() - interval '6 days'),
    ('36000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000001', '08000000-0000-0000-0000-000000000003', 'Gotowa na pierwsze figury odwrócone z asekuracją.',          now() - interval '10 days', now() - interval '8 days')
ON CONFLICT DO NOTHING;

INSERT INTO document (id, code, title, kind, audience, is_required, blocks_booking, is_active) VALUES
    ('37000000-0000-0000-0000-000000000001', 'SEED_TERMS',           'Regulamin studia',                     'TERMS',                 'ALL',   TRUE,  TRUE,  TRUE),
    ('37000000-0000-0000-0000-000000000002', 'SEED_GDPR',            'Klauzula informacyjna RODO',           'GDPR',                  'ALL',   TRUE,  FALSE, TRUE),
    ('37000000-0000-0000-0000-000000000003', 'SEED_POLE_CONSENT',    'Zgoda na udział w zajęciach pole dance','PARTICIPATION_CONSENT', 'ADULT', TRUE,  TRUE,  TRUE),
    ('37000000-0000-0000-0000-000000000004', 'SEED_PARENT_CONSENT',  'Zgoda rodzica na udział dziecka',      'PARENTAL_CONSENT',      'CHILD', TRUE,  TRUE,  TRUE),
    ('37000000-0000-0000-0000-000000000005', 'SEED_MARKETING_EMAIL', 'Zgoda na newsletter e-mail',           'MARKETING_EMAIL',       'ADULT', FALSE, FALSE, TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO document_version (id, document_id, version_no, content, file_id, requires_reacceptance, published_at, published_by, created_at) VALUES
    ('38000000-0000-0000-0000-000000000001', '37000000-0000-0000-0000-000000000001', 1, 'Regulamin studia (wersja 1). Rezerwacji można dokonywać w aplikacji.',                 NULL,                                   TRUE,  now() - interval '200 days', '03000000-0000-0000-0000-000000000001', now() - interval '200 days'),
    ('38000000-0000-0000-0000-000000000002', '37000000-0000-0000-0000-000000000001', 2, 'Regulamin studia (wersja 2). Bezpłatne odwołanie do 12 godzin przed zajęciami.',      NULL,                                   TRUE,  now() - interval '30 days',  '03000000-0000-0000-0000-000000000001', now() - interval '31 days'),
    ('38000000-0000-0000-0000-000000000003', '37000000-0000-0000-0000-000000000002', 1, 'Administratorem danych osobowych jest Studio For You.',                                 NULL,                                   TRUE,  now() - interval '200 days', '03000000-0000-0000-0000-000000000001', now() - interval '200 days'),
    ('38000000-0000-0000-0000-000000000004', '37000000-0000-0000-0000-000000000003', 1, NULL,                                                                                    '02000000-0000-0000-0000-000000000002', TRUE,  now() - interval '200 days', '03000000-0000-0000-0000-000000000001', now() - interval '200 days'),
    ('38000000-0000-0000-0000-000000000005', '37000000-0000-0000-0000-000000000004', 1, 'Wyrażam zgodę na udział mojego dziecka w zajęciach.',                                   NULL,                                   TRUE,  now() - interval '200 days', '03000000-0000-0000-0000-000000000001', now() - interval '200 days'),
    ('38000000-0000-0000-0000-000000000006', '37000000-0000-0000-0000-000000000005', 1, 'Wyrażam zgodę na otrzymywanie informacji marketingowych e-mailem.',                    NULL,                                   FALSE, now() - interval '200 days', '03000000-0000-0000-0000-000000000001', now() - interval '200 days'),
    ('38000000-0000-0000-0000-000000000007', '37000000-0000-0000-0000-000000000001', 3, 'Regulamin studia (wersja 3, szkic).',                                                  NULL,                                   FALSE, NULL,                        NULL,                                   now() - interval '1 day')
ON CONFLICT DO NOTHING;

INSERT INTO document_service_requirement (document_id, service_type_id) VALUES
    ('37000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001'),
    ('37000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000002'),
    ('37000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000005')
ON CONFLICT DO NOTHING;

INSERT INTO document_acceptance (id, document_version_id, customer_id, accepted_by, accepted_at, revoked_at, source) VALUES
    ('39000000-0000-0000-0000-000000000001', '38000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', now() - interval '100 days', NULL,                     'APP'),
    ('39000000-0000-0000-0000-000000000002', '38000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', now() - interval '29 days',  NULL,                     'APP'),
    ('39000000-0000-0000-0000-000000000003', '38000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', now() - interval '100 days', NULL,                     'APP'),
    ('39000000-0000-0000-0000-000000000004', '38000000-0000-0000-0000-000000000004', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', now() - interval '100 days', NULL,                     'APP'),
    ('39000000-0000-0000-0000-000000000005', '38000000-0000-0000-0000-000000000006', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', now() - interval '100 days', now() - interval '10 days', 'APP'),
    ('39000000-0000-0000-0000-000000000006', '38000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000002', now() - interval '25 days',  NULL,                     'PAPER'),
    ('39000000-0000-0000-0000-000000000007', '38000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', now() - interval '20 days',  NULL,                     'APP'),
    ('39000000-0000-0000-0000-000000000008', '38000000-0000-0000-0000-000000000004', '07000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', now() - interval '20 days',  NULL,                     'APP'),
    ('39000000-0000-0000-0000-000000000009', '38000000-0000-0000-0000-000000000005', '07000000-0000-0000-0000-000000000004', '03000000-0000-0000-0000-000000000006', now() - interval '20 days',  NULL,                     'APP'),
    ('39000000-0000-0000-0000-000000000010', '38000000-0000-0000-0000-000000000006', '07000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', now() - interval '20 days',  NULL,                     'APP')
ON CONFLICT DO NOTHING;

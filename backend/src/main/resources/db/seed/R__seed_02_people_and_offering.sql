-- Seed 02: customer and staff profiles, services, products, rooms
-- DEV/STAGE TEST DATA ONLY. Conventions: see R__seed_01_identity.sql.
--
-- Customer profiles (07...NN):
--   01 Katarzyna Zielińska   02 Tomasz Lewandowski   03 Monika Dąbrowska   04 Zosia Dąbrowska (child of 03)
--   05 Piotr Wójcik          06 Adam Mazur           07 Krzysztof Wrona (no account)   08 Julia Kamińska
-- Staff profiles (08...NN): 01 Anna Kowalska, 02 Marek Nowak, 03 Ewa Wiśniewska, 04 Beata Lis (admin)
-- Service types (10...NN):
--   01 Pole dance beginners   02 Pole dance intermediate   03 Calisthenics
--   04 Personal training      05 Pole dance kids           06 Stretching workshop

INSERT INTO customer_profile (id, account_id, kind, first_name, last_name, birth_date, phone, emergency_contact_name, emergency_phone, status, created_by) VALUES
    ('07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', 'ADULT', 'Katarzyna', 'Zielińska',   '1995-04-12', '+48600100101', 'Jan Zieliński',     '+48600100199', 'ACTIVE', NULL),
    ('07000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000005', 'ADULT', 'Tomasz',    'Lewandowski', '1988-09-30', '+48600100102', NULL,                NULL,           'ACTIVE', NULL),
    ('07000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', 'ADULT', 'Monika',    'Dąbrowska',   '1985-01-21', '+48600100103', 'Paweł Dąbrowski',   '+48600100198', 'ACTIVE', '03000000-0000-0000-0000-000000000001'),
    ('07000000-0000-0000-0000-000000000004', NULL,                                   'CHILD', 'Zosia',     'Dąbrowska',   '2016-06-05', NULL,           'Monika Dąbrowska',  '+48600100103', 'ACTIVE', '03000000-0000-0000-0000-000000000006'),
    ('07000000-0000-0000-0000-000000000005', '03000000-0000-0000-0000-000000000007', 'ADULT', 'Piotr',     'Wójcik',      '2000-11-02', '+48600100105', NULL,                NULL,           'ACTIVE', NULL),
    ('07000000-0000-0000-0000-000000000006', '03000000-0000-0000-0000-000000000009', 'ADULT', 'Adam',      'Mazur',       '1992-03-14', '+48600100106', NULL,                NULL,           'ACTIVE', NULL),
    ('07000000-0000-0000-0000-000000000007', NULL,                                   'ADULT', 'Krzysztof', 'Wrona',       '1979-07-19', '+48600100107', NULL,                NULL,           'ARCHIVED', '03000000-0000-0000-0000-000000000001'),
    ('07000000-0000-0000-0000-000000000008', '03000000-0000-0000-0000-000000000008', 'ADULT', 'Julia',     'Kamińska',    '1998-08-08', '+48600100108', NULL,                NULL,           'ACTIVE', '03000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

INSERT INTO guardianship (guardian_id, child_id, relation, is_payer) VALUES
    ('07000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000004', 'PARENT', TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO staff_profile (id, account_id, first_name, last_name, phone, bio, photo_file_id, calendar_color, status) VALUES
    ('08000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000001', 'Anna', 'Kowalska',   '+48600200201', 'Właścicielka studia, instruktorka pole dance od 10 lat.', '02000000-0000-0000-0000-000000000001', '#E91E63', 'ACTIVE'),
    ('08000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000002', 'Marek', 'Nowak',     '+48600200202', 'Trener personalny i calisthenics.',                      NULL,                                   '#2196F3', 'ACTIVE'),
    ('08000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000003', 'Ewa',   'Wiśniewska','+48600200203', 'Instruktorka pole dance i stretchingu.',                 NULL,                                   '#4CAF50', 'ACTIVE'),
    ('08000000-0000-0000-0000-000000000004', '03000000-0000-0000-0000-000000000010', 'Beata', 'Lis',       '+48600200204', 'Administratorka studia: grafik, karnety i konta klientów.', NULL,                                 NULL,      'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO service_type (id, name, description, category, level, min_age, max_age, default_duration_minutes, min_participants, max_participants, calendar_color, is_active) VALUES
    ('10000000-0000-0000-0000-000000000001', 'Pole dance - początkujący',       'Podstawy pracy na rurce.',              'GROUP_CLASS',       'BEGINNER',     16,   NULL, 60, 3,    10,   '#E91E63', TRUE),
    ('10000000-0000-0000-0000-000000000002', 'Pole dance - średniozaawansowany','Figury i kombinacje.',                  'GROUP_CLASS',       'INTERMEDIATE', 16,   NULL, 60, 3,    10,   '#AD1457', TRUE),
    ('10000000-0000-0000-0000-000000000003', 'Calisthenics',                    'Trening z masą własnego ciała.',        'GROUP_CLASS',       'ALL_LEVELS',   16,   NULL, 75, 4,    12,   '#2196F3', TRUE),
    ('10000000-0000-0000-0000-000000000004', 'Trening personalny',              'Trening 1:1 z wybranym trenerem.',      'PERSONAL_TRAINING', NULL,           NULL, NULL, 60, 1,    1,    '#FF9800', TRUE),
    ('10000000-0000-0000-0000-000000000005', 'Pole dance dla dzieci',           'Zajęcia akrobatyczne dla dzieci.',      'GROUP_CLASS',       'BEGINNER',     7,    12,   45, 3,    8,    '#9C27B0', TRUE),
    ('10000000-0000-0000-0000-000000000006', 'Warsztaty stretchingu',           'Jednorazowe warsztaty rozciągania.',    'WORKSHOP',          'ALL_LEVELS',   16,   NULL, 120, 4,   8,    '#4CAF50', TRUE),
    ('10000000-0000-0000-0000-000000000007', 'Joga (wycofane)',                 NULL,                                    'GROUP_CLASS',       'ALL_LEVELS',   NULL, NULL, 60, NULL, NULL, '#9E9E9E', FALSE)
ON CONFLICT DO NOTHING;

INSERT INTO customer_interest (customer_id, service_type_id) VALUES
    ('07000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002'),
    ('07000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000006'),
    ('07000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003'),
    ('07000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

INSERT INTO staff_qualification (staff_id, service_type_id) VALUES
    ('08000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001'),
    ('08000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002'),
    ('08000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000005'),
    ('08000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003'),
    ('08000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000004'),
    ('08000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001'),
    ('08000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000004'),
    ('08000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000006')
ON CONFLICT DO NOTHING;

INSERT INTO staff_assignment (id, customer_id, staff_id, scope, can_view_health, assigned_by, started_at, ended_at, end_reason) VALUES
    ('09000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000002', '08000000-0000-0000-0000-000000000002', 'PERSONAL_TRAINING', TRUE,  '03000000-0000-0000-0000-000000000001', now() - interval '80 days', NULL, NULL),
    ('09000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000001', '08000000-0000-0000-0000-000000000003', 'PERSONAL_TRAINING', TRUE,  '03000000-0000-0000-0000-000000000001', now() - interval '20 days', NULL, NULL),
    ('09000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000001', '08000000-0000-0000-0000-000000000002', 'PERSONAL_TRAINING', FALSE, '03000000-0000-0000-0000-000000000001', now() - interval '90 days', now() - interval '21 days', 'Zmiana trenera na prośbę klientki')
ON CONFLICT DO NOTHING;

INSERT INTO makeup_policy (id, name, validity_days, max_per_pass, grant_on_in_time_cancel, grant_on_studio_cancel, expiry_checked_against, is_active) VALUES
    ('12000000-0000-0000-0000-000000000001', 'Odrabianie 30 dni',  30, 2,    TRUE,  TRUE, 'EVENT_DATE',   TRUE),
    ('12000000-0000-0000-0000-000000000002', 'Odrabianie dzieci',  14, 1,    FALSE, TRUE, 'EVENT_DATE',   TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO pass_product (id, name, description, kind, audience, makeup_policy_id, suspension_allowed, max_suspension_days, is_active) VALUES
    ('13000000-0000-0000-0000-000000000001', 'Karnet Pole Dance',           'Wejścia na zajęcia pole dance.',          'ENTRY_PASS',   'ADULT', '12000000-0000-0000-0000-000000000001', TRUE,  14,   TRUE),
    ('13000000-0000-0000-0000-000000000002', 'Wejście jednorazowe',         'Jedno wejście na dowolne zajęcia.',       'SINGLE_ENTRY', 'ANY',   NULL,                                   FALSE, NULL, TRUE),
    ('13000000-0000-0000-0000-000000000003', 'Pakiet treningów personalnych','Pakiet sesji 1:1.',                      'PT_PACKAGE',   'ADULT', NULL,                                   FALSE, NULL, TRUE),
    ('13000000-0000-0000-0000-000000000004', 'Karnet Open - miesiąc',       'Bez limitu wejść przez 30 dni.',          'TIME_PASS',    'ADULT', NULL,                                   TRUE,  14,   TRUE),
    ('13000000-0000-0000-0000-000000000005', 'Karnet dziecięcy',            'Zajęcia dla dzieci.',                     'ENTRY_PASS',   'CHILD', '12000000-0000-0000-0000-000000000002', FALSE, NULL, TRUE),
    ('13000000-0000-0000-0000-000000000006', 'Voucher prezentowy',          'Voucher na dowolny karnet.',              'VOUCHER',      'ANY',   NULL,                                   FALSE, NULL, FALSE)
ON CONFLICT DO NOTHING;

INSERT INTO pass_product_variant (id, product_id, entries_count, validity_days, price, currency, is_active, valid_from, valid_to) VALUES
    ('14000000-0000-0000-0000-000000000001', '13000000-0000-0000-0000-000000000001', 4,    30, 180.00, 'PLN', TRUE,  now() - interval '120 days', NULL),
    ('14000000-0000-0000-0000-000000000002', '13000000-0000-0000-0000-000000000001', 8,    30, 260.00, 'PLN', TRUE,  now() - interval '120 days', NULL),
    ('14000000-0000-0000-0000-000000000003', '13000000-0000-0000-0000-000000000002', 1,    30, 55.00,  'PLN', TRUE,  now() - interval '120 days', NULL),
    ('14000000-0000-0000-0000-000000000004', '13000000-0000-0000-0000-000000000003', 5,    60, 750.00, 'PLN', TRUE,  now() - interval '120 days', NULL),
    ('14000000-0000-0000-0000-000000000005', '13000000-0000-0000-0000-000000000004', NULL, 30, 299.00, 'PLN', TRUE,  now() - interval '120 days', NULL),
    ('14000000-0000-0000-0000-000000000006', '13000000-0000-0000-0000-000000000005', 4,    30, 160.00, 'PLN', TRUE,  now() - interval '120 days', NULL),
    ('14000000-0000-0000-0000-000000000007', '13000000-0000-0000-0000-000000000001', 4,    30, 160.00, 'PLN', FALSE, now() - interval '365 days', now() - interval '120 days'),
    ('14000000-0000-0000-0000-000000000008', '13000000-0000-0000-0000-000000000006', 1,    90, 100.00, 'PLN', FALSE, now() - interval '365 days', now() - interval '200 days')
ON CONFLICT DO NOTHING;

INSERT INTO pass_product_service (product_id, service_type_id) VALUES
    ('13000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001'),
    ('13000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002'),
    ('13000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001'),
    ('13000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002'),
    ('13000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003'),
    ('13000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000004'),
    ('13000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001'),
    ('13000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000002'),
    ('13000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000003'),
    ('13000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000005')
ON CONFLICT DO NOTHING;

INSERT INTO resource (id, name, kind, parent_resource_id, max_people, description, is_active) VALUES
    ('15000000-0000-0000-0000-000000000001', 'Sala pole dance',   'ROOM', NULL, 10, 'Sala z 10 rurkami.',          TRUE),
    ('15000000-0000-0000-0000-000000000002', 'Sala treningowa 1', 'ROOM', NULL, 12, 'Duża sala, 3 stanowiska PT.', TRUE),
    ('15000000-0000-0000-0000-000000000003', 'Sala treningowa 2', 'ROOM', NULL, 8,  'Mała sala.',                  TRUE)
ON CONFLICT DO NOTHING;

-- Only ROOM resources exist for now (#133). Removes the EQUIPMENT row from databases seeded before.
DELETE FROM resource WHERE id = '15000000-0000-0000-0000-000000000004';

INSERT INTO service_type_resource (service_type_id, resource_id, is_default) VALUES
    ('10000000-0000-0000-0000-000000000001', '15000000-0000-0000-0000-000000000001', TRUE),
    ('10000000-0000-0000-0000-000000000002', '15000000-0000-0000-0000-000000000001', TRUE),
    ('10000000-0000-0000-0000-000000000005', '15000000-0000-0000-0000-000000000001', TRUE),
    ('10000000-0000-0000-0000-000000000003', '15000000-0000-0000-0000-000000000002', TRUE),
    ('10000000-0000-0000-0000-000000000003', '15000000-0000-0000-0000-000000000003', FALSE),
    ('10000000-0000-0000-0000-000000000004', '15000000-0000-0000-0000-000000000002', TRUE),
    ('10000000-0000-0000-0000-000000000004', '15000000-0000-0000-0000-000000000003', FALSE),
    ('10000000-0000-0000-0000-000000000006', '15000000-0000-0000-0000-000000000003', TRUE)
ON CONFLICT DO NOTHING;

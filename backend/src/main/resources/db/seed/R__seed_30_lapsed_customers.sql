-- Seed 30: customers who stopped coming, for the panel's "Nieaktywni klienci" list (#171)
-- DEV/STAGE TEST DATA ONLY. Conventions: see R__seed_01_identity.sql.
-- New clients (ids ...171NNN) whose visits ended a while before the seed first ran, with no
-- upcoming bookings. Visit dates are fixed when the seed first runs, so they only get older.
--
--   Magdalena Wróbel: regular until about 15 weeks ago; a valid pass with 2 entries left,
--     marketing consent, a personal announcement from Beata.
--   Paweł Kaczmarek: last visit about 13 weeks ago; his pass has expired.
--   Joanna Pawlak: came twice, about 5 weeks ago; never had a pass, marketing consent.
--   Oliwer Kubiak (child, born 2017): last class about 5 weeks ago; reached through his mother
--     Marta Kubiak, who never comes herself; a valid child pass.
-- The clients have no Firebase users: they show up in the panel only.

INSERT INTO account (id, email, status, created_by, activated_at, created_at) VALUES
    ('03000000-0000-0000-0000-000000171001', 'magdalena.wrobel@seed.studioforyou.pl',  'ACTIVE', '03000000-0000-0000-0000-000000000010', now() - interval '200 days', now() - interval '200 days'),
    ('03000000-0000-0000-0000-000000171002', 'pawel.kaczmarek@seed.studioforyou.pl',   'ACTIVE', '03000000-0000-0000-0000-000000000010', now() - interval '180 days', now() - interval '180 days'),
    ('03000000-0000-0000-0000-000000171003', 'joanna.pawlak@seed.studioforyou.pl',     'ACTIVE', '03000000-0000-0000-0000-000000000010', now() - interval '60 days',  now() - interval '60 days'),
    ('03000000-0000-0000-0000-000000171004', 'marta.kubiak@seed.studioforyou.pl',      'ACTIVE', '03000000-0000-0000-0000-000000000010', now() - interval '150 days', now() - interval '150 days')
ON CONFLICT DO NOTHING;

INSERT INTO account_role (account_id, role_id, granted_by, granted_at)
SELECT a.id::uuid, r.id, '03000000-0000-0000-0000-000000000010', now()
FROM (VALUES ('03000000-0000-0000-0000-000000171001'), ('03000000-0000-0000-0000-000000171002'),
             ('03000000-0000-0000-0000-000000171003'), ('03000000-0000-0000-0000-000000171004')) AS a(id)
JOIN role r ON r.code = 'CLIENT'
ON CONFLICT DO NOTHING;

INSERT INTO customer_profile (id, account_id, kind, first_name, last_name, birth_date, phone, status, created_by) VALUES
    ('07000000-0000-0000-0000-000000171001', '03000000-0000-0000-0000-000000171001', 'ADULT', 'Magdalena', 'Wróbel',    '1990-02-17', '+48601234118', 'ACTIVE', NULL),
    ('07000000-0000-0000-0000-000000171002', '03000000-0000-0000-0000-000000171002', 'ADULT', 'Paweł',     'Kaczmarek', '1987-07-08', '+48512908441', 'ACTIVE', NULL),
    ('07000000-0000-0000-0000-000000171003', '03000000-0000-0000-0000-000000171003', 'ADULT', 'Joanna',    'Pawlak',    '1999-12-01', '+48608773905', 'ACTIVE', NULL),
    ('07000000-0000-0000-0000-000000171004', '03000000-0000-0000-0000-000000171004', 'ADULT', 'Marta',     'Kubiak',    '1984-05-23', '+48790552014', 'ACTIVE', NULL),
    ('07000000-0000-0000-0000-000000171005', NULL,                                   'CHILD', 'Oliwer',    'Kubiak',    '2017-03-11', NULL,           'ACTIVE', '03000000-0000-0000-0000-000000171004')
ON CONFLICT DO NOTHING;

INSERT INTO guardianship (guardian_id, child_id, relation, is_payer) VALUES
    ('07000000-0000-0000-0000-000000171004', '07000000-0000-0000-0000-000000171005', 'PARENT', TRUE)
ON CONFLICT DO NOTHING;

-- Passes: Magdalena's 8-entry pole dance pass is still valid, Paweł's expired, Oliwer's child pass is valid.
INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining, price_paid, status, valid_from, valid_until, created_by, created_at) VALUES
    ('28000000-0000-0000-0000-000000171001', '07000000-0000-0000-0000-000000171001', '14000000-0000-0000-0000-000000000002', 'Karnet Pole Dance', 8, 2,    260.00, 'ACTIVE',  current_date - 25, current_date + 5,  '03000000-0000-0000-0000-000000000010', now() - interval '25 days'),
    ('28000000-0000-0000-0000-000000171002', '07000000-0000-0000-0000-000000171002', '14000000-0000-0000-0000-000000000002', 'Karnet Pole Dance', 8, 1,    260.00, 'EXPIRED', current_date - 120, current_date - 75, '03000000-0000-0000-0000-000000000010', now() - interval '120 days'),
    ('28000000-0000-0000-0000-000000171003', '07000000-0000-0000-0000-000000171005', '14000000-0000-0000-0000-000000000006', 'Karnet dziecięcy',  4, 3,    160.00, 'ACTIVE',  current_date - 40, current_date + 20, '03000000-0000-0000-0000-000000000010', now() - interval '40 days')
ON CONFLICT DO NOTHING;

-- One past class per visit (completed, attended), at 18:00 studio time (10:00 for the child).
WITH visit(no, customer_no, days_ago, service_no, title, local_time) AS (VALUES
    (1,  1, 160, 1, 'Pole dance - początkujący', TIME '18:00'),
    (2,  1, 150, 1, 'Pole dance - początkujący', TIME '18:00'),
    (3,  1, 130, 1, 'Pole dance - początkujący', TIME '18:00'),
    (4,  1, 118, 1, 'Pole dance - początkujący', TIME '18:00'),
    (5,  1, 111, 1, 'Pole dance - początkujący', TIME '18:00'),
    (6,  1, 104, 1, 'Pole dance - początkujący', TIME '18:00'),
    (7,  2, 140, 3, 'Calisthenics',              TIME '19:00'),
    (8,  2, 126, 3, 'Calisthenics',              TIME '19:00'),
    (9,  2, 112, 3, 'Calisthenics',              TIME '19:00'),
    (10, 2, 98,  3, 'Calisthenics',              TIME '19:00'),
    (11, 2, 89,  3, 'Calisthenics',              TIME '19:00'),
    (12, 3, 40,  1, 'Pole dance - początkujący', TIME '18:00'),
    (13, 3, 33,  1, 'Pole dance - początkujący', TIME '18:00'),
    (14, 5, 51,  5, 'Pole dance dla dzieci',     TIME '10:00'),
    (15, 5, 44,  5, 'Pole dance dla dzieci',     TIME '10:00'),
    (16, 5, 37,  5, 'Pole dance dla dzieci',     TIME '10:00')
), timed AS (
    SELECT v.*, ((current_date - v.days_ago) + v.local_time) AT TIME ZONE 'Europe/Warsaw' AS starts_at
    FROM visit v
), events AS (
    INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at, capacity, visibility, status, created_by)
    SELECT ('19000000-0000-0000-0000-' || lpad((171000 + no)::TEXT, 12, '0'))::UUID, 'CLASS',
           ('10000000-0000-0000-0000-' || lpad(service_no::TEXT, 12, '0'))::UUID, title,
           starts_at, starts_at + interval '1 hour', 10, 'PUBLIC', 'COMPLETED', '03000000-0000-0000-0000-000000000001'
    FROM timed
    ON CONFLICT DO NOTHING
    RETURNING id
)
INSERT INTO reservation (id, event_id, customer_id, booked_by, source, status, attendance, attendance_marked_by,
                         attendance_marked_at, created_at)
SELECT ('30000000-0000-0000-0000-' || lpad((171000 + t.no)::TEXT, 12, '0'))::UUID, e.id,
       ('07000000-0000-0000-0000-' || lpad((171000 + t.customer_no)::TEXT, 12, '0'))::UUID,
       CASE WHEN t.customer_no = 5 THEN '03000000-0000-0000-0000-000000171004'
            ELSE ('03000000-0000-0000-0000-' || lpad((171000 + t.customer_no)::TEXT, 12, '0')) END::UUID,
       'SELF', 'ACTIVE', 'PRESENT', '03000000-0000-0000-0000-000000000001', t.starts_at + interval '1 hour',
       t.starts_at - interval '2 days'
FROM timed t
JOIN events e ON e.id = ('19000000-0000-0000-0000-' || lpad((171000 + t.no)::TEXT, 12, '0'))::UUID
ON CONFLICT DO NOTHING;

-- Marketing consent (in-app notifications): Magdalena and Joanna.
INSERT INTO document_acceptance (id, document_version_id, customer_id, accepted_by, accepted_at)
SELECT c.id::uuid, v.id, c.customer_id::uuid, c.account_id::uuid, now() - interval '150 days'
FROM (VALUES
    ('39000000-0000-0000-0000-000000171001', '07000000-0000-0000-0000-000000171001', '03000000-0000-0000-0000-000000171001'),
    ('39000000-0000-0000-0000-000000171002', '07000000-0000-0000-0000-000000171003', '03000000-0000-0000-0000-000000171003')
) AS c(id, customer_id, account_id)
JOIN document d ON d.code = 'MARKETING_PUSH'
JOIN document_version v ON v.document_id = d.id AND v.version_no = 1
ON CONFLICT DO NOTHING;

-- Beata already wrote to Magdalena once, a few weeks after she stopped coming.
INSERT INTO announcement (id, author_id, audience, target_account_id, is_marketing, title, body, created_at, sent_at) VALUES
    ('42000000-0000-0000-0000-000000171001', '03000000-0000-0000-0000-000000000010', 'PERSON', '03000000-0000-0000-0000-000000171001', FALSE,
     'Tęsknimy za Tobą', 'Dawno Cię nie było. Masz jeszcze 2 wejścia na karnecie, zapraszamy na zajęcia!', now() - interval '55 days', now() - interval '55 days')
ON CONFLICT DO NOTHING;

INSERT INTO notification (id, recipient_id, notification_type_id, recipient_role, subject_customer_id, category, title, body,
                          payload, announcement_id, dedup_key, scheduled_for, created_at)
SELECT '44000000-0000-0000-0000-000000171001', a.target_account_id, 14, 'CLIENT', NULL,
       'TRANSACTIONAL', a.title, a.body,
       jsonb_build_object('announcementId', a.id::TEXT, 'marketing', a.is_marketing::TEXT, 'author', 'Beata Lis',
                          'audience', 'PERSON'),
       a.id, 'ANNOUNCEMENT:' || a.id || ':' || a.target_account_id, a.sent_at, a.sent_at
FROM announcement a
WHERE a.id = '42000000-0000-0000-0000-000000171001'
ON CONFLICT DO NOTHING;

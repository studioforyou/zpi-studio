-- Seed 01: studio, accounts, roles/permissions, files
--
-- DEV/STAGE TEST DATA ONLY. Loaded by the "seed" Spring profile (application-seed.yml),
-- which the dev and stage profiles switch on. Never part of production.
--
-- Rules for all seed files:
--  * Repeatable migrations (R__): run after all versioned migrations, again whenever the file changes.
--  * Every INSERT ends with ON CONFLICT DO NOTHING: re-running only adds missing rows and never
--    overwrites data changed by testers. To change an existing row, give it a new id
--    or recreate the local database (docker compose down -v).
--  * Fixed ids, readable by table: 'TT000000-0000-0000-0000-0000000000NN',
--    TT = table number (see the list below), NN = row number.
--  * Dictionaries that will come from real migrations (permission, notification_type) use
--    ids >= 900 and a SEED_ code prefix, so a later V__ migration cannot collide with them.
--  * Accounts log in through Firebase only if a Firebase user with the given UID exists;
--    to log in as yourself use scripts/db/create_dev_account.sql.
--
-- Table numbers:
--   01 studio              13 pass_product           25 order_item              37 document
--   02 stored_file         14 pass_product_variant   26 payment                 38 document_version
--   03 account             15 resource               27 payment_webhook_event   39 document_acceptance
--   04 identity_link       16 training_group         28 customer_pass           40 conversation
--   05 account_invitation  17 group_membership       29 pass_suspension         41 message
--   06 account_status_chg  18 recurrence_series      30 reservation             42 announcement
--   07 customer_profile    19 schedule_event         31 waitlist_entry          43 notification_template
--   08 staff_profile       20 trainer_availability   32 training_dispute        44 notification
--   09 staff_assignment    21 staff_time_off         33 makeup_credit           45 notification_delivery
--   10 service_type        22 substitution_request   34 pass_ledger             46 device_token
--   11 booking_policy      23 staff_rate             35 health_note             47 audit_log (correlation_id)
--   12 makeup_policy       24 customer_order         36 trainer_note
--
-- Accounts (03...NN):
--   01 Anna Kowalska      owner + trainer   ACTIVE
--   02 Marek Nowak        trainer           ACTIVE
--   03 Ewa Wiśniewska     trainer           ACTIVE
--   04 Katarzyna Zielińska client           ACTIVE
--   05 Tomasz Lewandowski client            ACTIVE
--   06 Monika Dąbrowska   client (parent)   ACTIVE
--   07 Piotr Wójcik       client            PENDING_APPROVAL
--   08 Julia Kamińska     client            INVITED
--   09 Adam Mazur         client            SUSPENDED

INSERT INTO studio (id, name, time_zone, currency, group_auto_book_weeks, pt_dispute_window_hours, substitution_needs_approval)
VALUES ('01000000-0000-0000-0000-000000000001', 'Studio For You (seed)', 'Europe/Warsaw', 'PLN', 4, 24, TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO account (id, email, status, created_by, activated_at, last_login_at, created_at) VALUES
    ('03000000-0000-0000-0000-000000000001', 'anna.kowalska@seed.studioforyou.test',     'ACTIVE',           NULL,                                   now() - interval '180 days', now() - interval '1 day',  now() - interval '180 days'),
    ('03000000-0000-0000-0000-000000000002', 'marek.nowak@seed.studioforyou.test',       'ACTIVE',           '03000000-0000-0000-0000-000000000001', now() - interval '150 days', now() - interval '2 days', now() - interval '150 days'),
    ('03000000-0000-0000-0000-000000000003', 'ewa.wisniewska@seed.studioforyou.test',    'ACTIVE',           '03000000-0000-0000-0000-000000000001', now() - interval '120 days', now() - interval '3 days', now() - interval '120 days'),
    ('03000000-0000-0000-0000-000000000004', 'katarzyna.zielinska@seed.studioforyou.test','ACTIVE',          NULL,                                   now() - interval '100 days', now() - interval '1 day',  now() - interval '101 days'),
    ('03000000-0000-0000-0000-000000000005', 'tomasz.lewandowski@seed.studioforyou.test','ACTIVE',           NULL,                                   now() - interval '90 days',  now() - interval '5 days', now() - interval '91 days'),
    ('03000000-0000-0000-0000-000000000006', 'monika.dabrowska@seed.studioforyou.test',  'ACTIVE',           '03000000-0000-0000-0000-000000000001', now() - interval '60 days',  now() - interval '4 days', now() - interval '60 days'),
    ('03000000-0000-0000-0000-000000000007', 'piotr.wojcik@seed.studioforyou.test',      'PENDING_APPROVAL', NULL,                                   NULL,                        NULL,                      now() - interval '2 days'),
    ('03000000-0000-0000-0000-000000000008', 'julia.kaminska@seed.studioforyou.test',    'INVITED',          '03000000-0000-0000-0000-000000000001', NULL,                        NULL,                      now() - interval '1 day'),
    ('03000000-0000-0000-0000-000000000009', 'adam.mazur@seed.studioforyou.test',        'SUSPENDED',        NULL,                                   now() - interval '200 days', now() - interval '30 days', now() - interval '201 days')
ON CONFLICT DO NOTHING;

INSERT INTO identity_link (id, account_id, provider, external_uid) VALUES
    ('04000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000001', 'FIREBASE', 'seed-uid-anna-kowalska'),
    ('04000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000002', 'FIREBASE', 'seed-uid-marek-nowak'),
    ('04000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000003', 'FIREBASE', 'seed-uid-ewa-wisniewska'),
    ('04000000-0000-0000-0000-000000000004', '03000000-0000-0000-0000-000000000004', 'FIREBASE', 'seed-uid-katarzyna-zielinska'),
    ('04000000-0000-0000-0000-000000000005', '03000000-0000-0000-0000-000000000005', 'FIREBASE', 'seed-uid-tomasz-lewandowski'),
    ('04000000-0000-0000-0000-000000000006', '03000000-0000-0000-0000-000000000006', 'FIREBASE', 'seed-uid-monika-dabrowska'),
    ('04000000-0000-0000-0000-000000000007', '03000000-0000-0000-0000-000000000007', 'FIREBASE', 'seed-uid-piotr-wojcik'),
    ('04000000-0000-0000-0000-000000000009', '03000000-0000-0000-0000-000000000009', 'FIREBASE', 'seed-uid-adam-mazur')
ON CONFLICT DO NOTHING;

-- Only used if the app gets its own login; {noop} = plain text in Spring's DelegatingPasswordEncoder.
INSERT INTO local_credential (account_id, password_hash) VALUES
    ('03000000-0000-0000-0000-000000000001', '{noop}seed-password')
ON CONFLICT DO NOTHING;

INSERT INTO account_invitation (id, account_id, token_hash, sent_by, sent_at, expires_at, used_at, revoked_at) VALUES
    ('05000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000008', encode(sha256('seed-invite-julia'::bytea), 'hex'),
     '03000000-0000-0000-0000-000000000001', now() - interval '1 day', now() + interval '6 days', NULL, NULL),
    ('05000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000006', encode(sha256('seed-invite-monika'::bytea), 'hex'),
     '03000000-0000-0000-0000-000000000001', now() - interval '61 days', now() - interval '54 days', now() - interval '60 days', NULL),
    ('05000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', encode(sha256('seed-invite-monika-old'::bytea), 'hex'),
     '03000000-0000-0000-0000-000000000001', now() - interval '62 days', now() - interval '55 days', NULL, now() - interval '61 days')
ON CONFLICT DO NOTHING;

-- Placeholder permissions until the permission model migration exists.
INSERT INTO permission (id, code, category, description) VALUES
    (901, 'SEED_SCHEDULE_MANAGE_ALL',   'SCHEDULE',     'Edit the whole schedule (seed placeholder)'),
    (902, 'SEED_SCHEDULE_EDIT_OWN',     'SCHEDULE',     'Edit own classes (seed placeholder)'),
    (903, 'SEED_AVAILABILITY_EDIT_OWN', 'SCHEDULE',     'Edit own availability (seed placeholder)'),
    (904, 'SEED_CUSTOMER_HEALTH_READ',  'CUSTOMER',     'Read client health data (seed placeholder)'),
    (905, 'SEED_PASS_ADJUST',           'MEMBERSHIP',   'Adjust client passes (seed placeholder)'),
    (906, 'SEED_ATTENDANCE_CORRECT',    'BOOKING',      'Correct attendance (seed placeholder)'),
    (907, 'SEED_BOOKING_SELF',          'BOOKING',      'Book own classes (seed placeholder)')
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission_id) VALUES
    (1, 907),
    (2, 902),
    (2, 903),
    (2, 906)
ON CONFLICT DO NOTHING;

INSERT INTO account_role (account_id, role_id, granted_by, granted_at) VALUES
    ('03000000-0000-0000-0000-000000000001', 2, NULL,                                   now() - interval '180 days'),
    ('03000000-0000-0000-0000-000000000002', 2, '03000000-0000-0000-0000-000000000001', now() - interval '150 days'),
    ('03000000-0000-0000-0000-000000000003', 2, '03000000-0000-0000-0000-000000000001', now() - interval '120 days'),
    ('03000000-0000-0000-0000-000000000004', 1, '03000000-0000-0000-0000-000000000001', now() - interval '100 days'),
    ('03000000-0000-0000-0000-000000000005', 1, '03000000-0000-0000-0000-000000000001', now() - interval '90 days'),
    ('03000000-0000-0000-0000-000000000006', 1, '03000000-0000-0000-0000-000000000001', now() - interval '60 days'),
    ('03000000-0000-0000-0000-000000000007', 1, NULL,                                   now() - interval '2 days'),
    ('03000000-0000-0000-0000-000000000008', 1, '03000000-0000-0000-0000-000000000001', now() - interval '1 day'),
    ('03000000-0000-0000-0000-000000000009', 1, '03000000-0000-0000-0000-000000000001', now() - interval '200 days')
ON CONFLICT DO NOTHING;

INSERT INTO account_permission_override (account_id, permission_id, effect, granted_by, granted_at) VALUES
    ('03000000-0000-0000-0000-000000000001', 901, 'GRANT',  '03000000-0000-0000-0000-000000000001', now() - interval '180 days'),
    ('03000000-0000-0000-0000-000000000001', 904, 'GRANT',  '03000000-0000-0000-0000-000000000001', now() - interval '180 days'),
    ('03000000-0000-0000-0000-000000000001', 905, 'GRANT',  '03000000-0000-0000-0000-000000000001', now() - interval '180 days'),
    ('03000000-0000-0000-0000-000000000003', 906, 'REVOKE', '03000000-0000-0000-0000-000000000001', now() - interval '100 days')
ON CONFLICT DO NOTHING;

INSERT INTO account_status_change (id, account_id, from_status, to_status, changed_by, reason, changed_at) VALUES
    ('06000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', NULL,               'PENDING_APPROVAL', NULL,                                   NULL,                                   now() - interval '101 days'),
    ('06000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000004', 'PENDING_APPROVAL', 'ACTIVE',           '03000000-0000-0000-0000-000000000001', 'Zweryfikowana na recepcji',            now() - interval '100 days'),
    ('06000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000005', 'PENDING_APPROVAL', 'ACTIVE',           '03000000-0000-0000-0000-000000000001', NULL,                                   now() - interval '90 days'),
    ('06000000-0000-0000-0000-000000000004', '03000000-0000-0000-0000-000000000006', 'INVITED',          'ACTIVE',           NULL,                                   'Pierwsze logowanie z zaproszenia',     now() - interval '60 days'),
    ('06000000-0000-0000-0000-000000000005', '03000000-0000-0000-0000-000000000007', NULL,               'PENDING_APPROVAL', NULL,                                   NULL,                                   now() - interval '2 days'),
    ('06000000-0000-0000-0000-000000000006', '03000000-0000-0000-0000-000000000008', NULL,               'INVITED',          '03000000-0000-0000-0000-000000000001', NULL,                                   now() - interval '1 day'),
    ('06000000-0000-0000-0000-000000000007', '03000000-0000-0000-0000-000000000009', 'ACTIVE',           'SUSPENDED',        '03000000-0000-0000-0000-000000000001', 'Zaległa płatność za karnet',           now() - interval '30 days')
ON CONFLICT DO NOTHING;

INSERT INTO stored_file (id, owner_module, uploaded_by, bucket, object_key, original_name, mime_type, size_bytes, sha256, sensitivity) VALUES
    ('02000000-0000-0000-0000-000000000001', 'staff',         '03000000-0000-0000-0000-000000000001', 'zpi-studio-seed', 'seed/staff/anna-kowalska.jpg',     'anna.jpg',               'image/jpeg',      184320, encode(sha256('seed-file-1'::bytea), 'hex'), 'NORMAL'),
    ('02000000-0000-0000-0000-000000000002', 'documents',     '03000000-0000-0000-0000-000000000001', 'zpi-studio-seed', 'seed/documents/zgoda-pole-v1.pdf', 'zgoda-pole-dance.pdf',   'application/pdf', 96256,  encode(sha256('seed-file-2'::bytea), 'hex'), 'NORMAL'),
    ('02000000-0000-0000-0000-000000000003', 'communication', '03000000-0000-0000-0000-000000000002', 'zpi-studio-seed', 'seed/chat/plan-treningowy.pdf',    'plan-treningowy.pdf',    'application/pdf', 51200,  encode(sha256('seed-file-3'::bytea), 'hex'), 'NORMAL'),
    ('02000000-0000-0000-0000-000000000004', 'communication', '03000000-0000-0000-0000-000000000005', 'zpi-studio-seed', 'seed/health/zaswiadczenie.pdf',    'zaswiadczenie-lekarz.pdf','application/pdf', 70144,  encode(sha256('seed-file-4'::bytea), 'hex'), 'HEALTH')
ON CONFLICT DO NOTHING;

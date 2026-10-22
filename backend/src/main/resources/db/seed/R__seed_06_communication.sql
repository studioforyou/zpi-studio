-- Seed 06: chat, announcements, notifications, devices, calendar feeds, audit log
-- DEV/STAGE TEST DATA ONLY. Conventions: see R__seed_01_identity.sql.

INSERT INTO conversation (id, kind, training_group_id, event_id, title, created_by, created_at) VALUES
    ('40000000-0000-0000-0000-000000000001', 'DIRECT', NULL,                                   NULL,                                   NULL,                                  '03000000-0000-0000-0000-000000000005', now() - interval '20 days'),
    ('40000000-0000-0000-0000-000000000002', 'GROUP',  '16000000-0000-0000-0000-000000000001', NULL,                                   'Pole dance początkujący wt/czw',      '03000000-0000-0000-0000-000000000001', now() - interval '60 days'),
    ('40000000-0000-0000-0000-000000000003', 'EVENT',  NULL,                                   '19000000-0000-0000-0000-000000000009', 'Warsztaty stretchingu',               '03000000-0000-0000-0000-000000000003', now() - interval '5 days')
ON CONFLICT DO NOTHING;

INSERT INTO conversation_participant (conversation_id, account_id, joined_at, left_at, last_read_at) VALUES
    ('40000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000005', now() - interval '20 days', NULL,                     now() - interval '1 day'),
    ('40000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000002', now() - interval '20 days', NULL,                     now() - interval '2 days'),
    ('40000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000001', now() - interval '60 days', NULL,                     now() - interval '1 day'),
    ('40000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000004', now() - interval '60 days', NULL,                     now() - interval '3 days'),
    ('40000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000006', now() - interval '30 days', NULL,                     NULL),
    ('40000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000009', now() - interval '60 days', now() - interval '30 days', now() - interval '31 days'),
    ('40000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000003', now() - interval '5 days',  NULL,                     now() - interval '1 day'),
    ('40000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', now() - interval '3 days',  NULL,                     NULL)
ON CONFLICT DO NOTHING;

INSERT INTO message (id, conversation_id, sender_id, body, created_at, edited_at, deleted_at) VALUES
    ('41000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000005', 'Cześć, czy możemy przesunąć trening na 18:00?',     now() - interval '3 days',  NULL,                      NULL),
    ('41000000-0000-0000-0000-000000000002', '40000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000002', 'Jasne. W załączniku plan na ten tydzień.',          now() - interval '2 days',  now() - interval '2 days' + interval '3 minutes', NULL),
    ('41000000-0000-0000-0000-000000000003', '40000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000005', 'Dzięki!',                                           now() - interval '1 day',   NULL,                      NULL),
    ('41000000-0000-0000-0000-000000000004', '40000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000001', 'Przypominam o wygodnym stroju z krótkimi spodenkami.',now() - interval '4 days', NULL,                      NULL),
    ('41000000-0000-0000-0000-000000000005', '40000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', 'Pomyłka',                                           now() - interval '2 days',  NULL,                      now() - interval '2 days')
ON CONFLICT DO NOTHING;

INSERT INTO message_attachment (message_id, file_id) VALUES
    ('41000000-0000-0000-0000-000000000002', '02000000-0000-0000-0000-000000000003'),
    ('41000000-0000-0000-0000-000000000003', '02000000-0000-0000-0000-000000000004')
ON CONFLICT DO NOTHING;

INSERT INTO announcement (id, author_id, audience, target_account_id, training_group_id, event_id, is_marketing, title, body, created_at, sent_at) VALUES
    ('42000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000001', 'ALL',    NULL,                                   NULL,                                   NULL,                                   FALSE, 'Nowy grafik',            'Od przyszłego miesiąca nowy grafik zajęć w aplikacji.', now() - interval '7 days', now() - interval '7 days'),
    ('42000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000001', 'GROUP',  NULL,                                   '16000000-0000-0000-0000-000000000001', NULL,                                   FALSE, 'Zmiana sali',            'W czwartek ćwiczymy w sali treningowej 2.',            now() - interval '3 days', now() - interval '3 days'),
    ('42000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000003', 'PERSON', '03000000-0000-0000-0000-000000000004', NULL,                                   NULL,                                   FALSE, 'Trening personalny',     'Zabierz proszę matę na trening.',                      now() - interval '2 days', now() - interval '2 days'),
    ('42000000-0000-0000-0000-000000000004', '03000000-0000-0000-0000-000000000001', 'ALL',    NULL,                                   NULL,                                   NULL,                                   TRUE,  'Promocja -20% na karnety','Tylko w tym tygodniu karnety 20% taniej.',             now() - interval '1 day',  now() - interval '1 day'),
    ('42000000-0000-0000-0000-000000000005', '03000000-0000-0000-0000-000000000003', 'EVENT',  NULL,                                   NULL,                                   '19000000-0000-0000-0000-000000000009', FALSE, 'Warsztaty - co zabrać',  'Weźcie wodę i ręcznik.',                               now() - interval '1 hour', NULL)
ON CONFLICT DO NOTHING;

-- Placeholder notification types until the real ones come with a migration.
INSERT INTO notification_type (id, code, description, is_enabled, lead_time_minutes, default_channels, updated_by) VALUES
    (901, 'SEED_CLASS_REMINDER',  'Przypomnienie o zajęciach (seed)',         TRUE,  120,  'PUSH,EMAIL', '03000000-0000-0000-0000-000000000001'),
    (902, 'SEED_PASS_EXPIRING',   'Karnet niedługo wygaśnie (seed)',          TRUE,  4320, 'PUSH,EMAIL', '03000000-0000-0000-0000-000000000001'),
    (903, 'SEED_CLASS_CANCELLED', 'Zajęcia odwołane (seed)',                  TRUE,  NULL, 'PUSH,EMAIL', NULL),
    (904, 'SEED_ANNOUNCEMENT',    'Ogłoszenie studia (seed)',                 TRUE,  NULL, 'PUSH',       NULL),
    (905, 'SEED_WAITLIST_SPOT',   'Zwolniło się miejsce (seed)',              FALSE, NULL, 'PUSH',       '03000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

INSERT INTO notification_template (id, notification_type_id, channel, locale, subject, body_template, updated_by) VALUES
    ('43000000-0000-0000-0000-000000000001', 901, 'PUSH',  'pl', NULL,                          'Za {{minutes}} min: {{event_title}}',                        '03000000-0000-0000-0000-000000000001'),
    ('43000000-0000-0000-0000-000000000002', 901, 'EMAIL', 'pl', 'Przypomnienie o zajęciach',   'Cześć {{first_name}}, przypominamy o zajęciach {{event_title}} o {{start_time}}.', '03000000-0000-0000-0000-000000000001'),
    ('43000000-0000-0000-0000-000000000003', 901, 'PUSH',  'en', NULL,                          'In {{minutes}} min: {{event_title}}',                        '03000000-0000-0000-0000-000000000001'),
    ('43000000-0000-0000-0000-000000000004', 902, 'EMAIL', 'pl', 'Twój karnet wkrótce wygaśnie','Karnet {{pass_name}} jest ważny do {{valid_until}}.',       '03000000-0000-0000-0000-000000000001'),
    ('43000000-0000-0000-0000-000000000005', 903, 'PUSH',  'pl', NULL,                          'Zajęcia {{event_title}} zostały odwołane.',                  NULL),
    ('43000000-0000-0000-0000-000000000006', 904, 'PUSH',  'pl', NULL,                          '{{title}}',                                                  NULL),
    ('43000000-0000-0000-0000-000000000007', 905, 'PUSH',  'pl', NULL,                          'Zwolniło się miejsce na {{event_title}}!',                   NULL)
ON CONFLICT DO NOTHING;

INSERT INTO notification_preference (account_id, notification_type_id, channel, enabled) VALUES
    ('03000000-0000-0000-0000-000000000004', 901, 'EMAIL', FALSE),
    ('03000000-0000-0000-0000-000000000005', 904, 'EMAIL', TRUE),
    ('03000000-0000-0000-0000-000000000006', 902, 'PUSH',  FALSE)
ON CONFLICT DO NOTHING;

INSERT INTO notification (id, recipient_id, notification_type_id, category, title, body, payload, announcement_id, dedup_key, scheduled_for, created_at, read_at) VALUES
    ('44000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', 901, 'TRANSACTIONAL', 'Zajęcia za 2 godziny', 'Pole dance początkujący o 18:00',
     '{"reservationId": "30000000-0000-0000-0000-000000000002", "eventId": "19000000-0000-0000-0000-000000000002"}', NULL,
     'SEED_CLASS_REMINDER:30000000-0000-0000-0000-000000000002', (SELECT starts_at - interval '2 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000002'), now() - interval '7 days', NULL),
    ('44000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000004', 903, 'TRANSACTIONAL', 'Zajęcia odwołane', 'Pole dance początkujący zostały odwołane. Wejście wróciło na karnet.',
     '{"eventId": "19000000-0000-0000-0000-000000000004"}', NULL,
     'SEED_CLASS_CANCELLED:30000000-0000-0000-0000-000000000008', now() - interval '1 day', now() - interval '1 day', now() - interval '20 hours'),
    ('44000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000004', 904, 'TRANSACTIONAL', 'Trening personalny', 'Zabierz proszę matę na trening.',
     NULL, '42000000-0000-0000-0000-000000000003', NULL, now() - interval '2 days', now() - interval '2 days', NULL),
    ('44000000-0000-0000-0000-000000000004', '03000000-0000-0000-0000-000000000005', 904, 'TRANSACTIONAL', 'Nowy grafik', 'Od przyszłego miesiąca nowy grafik zajęć w aplikacji.',
     NULL, '42000000-0000-0000-0000-000000000001', NULL, now() - interval '7 days', now() - interval '7 days', now() - interval '6 days'),
    ('44000000-0000-0000-0000-000000000005', '03000000-0000-0000-0000-000000000004', 904, 'TRANSACTIONAL', 'Nowy grafik', 'Od przyszłego miesiąca nowy grafik zajęć w aplikacji.',
     NULL, '42000000-0000-0000-0000-000000000001', NULL, now() - interval '7 days', now() - interval '7 days', NULL),
    ('44000000-0000-0000-0000-000000000006', '03000000-0000-0000-0000-000000000006', 904, 'MARKETING',     'Promocja -20% na karnety', 'Tylko w tym tygodniu karnety 20% taniej.',
     NULL, '42000000-0000-0000-0000-000000000004', NULL, now() - interval '1 day', now() - interval '1 day', NULL),
    ('44000000-0000-0000-0000-000000000007', '03000000-0000-0000-0000-000000000006', 902, 'TRANSACTIONAL', 'Karnet wkrótce wygaśnie', 'Karnet dziecięcy Zosi jest ważny jeszcze 10 dni.',
     '{"passId": "28000000-0000-0000-0000-000000000002"}', NULL,
     'SEED_PASS_EXPIRING:28000000-0000-0000-0000-000000000002', now() + interval '7 days', now() - interval '1 hour', NULL)
ON CONFLICT DO NOTHING;

INSERT INTO notification_delivery (id, notification_id, channel, status, provider_message_id, error, attempted_at) VALUES
    ('45000000-0000-0000-0000-000000000001', '44000000-0000-0000-0000-000000000002', 'PUSH',  'SENT',   'seed-fcm-msg-01', NULL,          now() - interval '1 day'),
    ('45000000-0000-0000-0000-000000000002', '44000000-0000-0000-0000-000000000002', 'EMAIL', 'SENT',   'seed-mail-01',    NULL,          now() - interval '1 day'),
    ('45000000-0000-0000-0000-000000000003', '44000000-0000-0000-0000-000000000003', 'PUSH',  'FAILED', NULL,              'UNREGISTERED', now() - interval '2 days'),
    ('45000000-0000-0000-0000-000000000004', '44000000-0000-0000-0000-000000000004', 'PUSH',  'SENT',   'seed-fcm-msg-02', NULL,          now() - interval '7 days'),
    ('45000000-0000-0000-0000-000000000005', '44000000-0000-0000-0000-000000000006', 'PUSH',  'QUEUED', NULL,              NULL,          now() - interval '1 day')
ON CONFLICT DO NOTHING;

INSERT INTO device_token (id, account_id, token, platform, created_at, last_seen_at, revoked_at) VALUES
    ('46000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', 'seed-fcm-token-katarzyna-android', 'ANDROID', now() - interval '90 days', now() - interval '1 day',  NULL),
    ('46000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000004', 'seed-fcm-token-katarzyna-old-ios', 'IOS',     now() - interval '99 days', now() - interval '60 days', now() - interval '2 days'),
    ('46000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000005', 'seed-fcm-token-tomasz-ios',        'IOS',     now() - interval '80 days', now() - interval '5 days',  NULL),
    ('46000000-0000-0000-0000-000000000004', '03000000-0000-0000-0000-000000000001', 'seed-fcm-token-anna-web',          'WEB',     now() - interval '30 days', now() - interval '1 day',  NULL)
ON CONFLICT DO NOTHING;

INSERT INTO calendar_feed (account_id, token_hash, created_at, revoked_at) VALUES
    ('03000000-0000-0000-0000-000000000004', encode(sha256('seed-ical-katarzyna'::bytea), 'hex'), now() - interval '50 days', NULL),
    ('03000000-0000-0000-0000-000000000002', encode(sha256('seed-ical-marek'::bytea), 'hex'),     now() - interval '100 days', NULL)
ON CONFLICT DO NOTHING;

-- audit_log has an identity key, so rows are matched by correlation_id instead of ON CONFLICT.
INSERT INTO audit_log (occurred_at, actor_account_id, module, entity_type, entity_id, action, changes, reason, correlation_id)
SELECT v.occurred_at, v.actor_account_id::uuid, v.module, v.entity_type, v.entity_id::uuid, v.action, v.changes::jsonb, v.reason, v.correlation_id::uuid
FROM (VALUES
    (now() - interval '100 days', '03000000-0000-0000-0000-000000000001', 'identity',   'account',        '03000000-0000-0000-0000-000000000004', 'ACCOUNT_APPROVED',
     '{"status": {"old": "PENDING_APPROVAL", "new": "ACTIVE"}}', 'Zweryfikowana na recepcji', '47000000-0000-0000-0000-000000000001'),
    (now() - interval '150 days', '03000000-0000-0000-0000-000000000001', 'identity',   'account',        '03000000-0000-0000-0000-000000000002', 'ROLE_GRANTED',
     '{"role": {"old": null, "new": "TRAINER"}}', NULL, '47000000-0000-0000-0000-000000000002'),
    (now() - interval '1 day',    '03000000-0000-0000-0000-000000000001', 'scheduling', 'schedule_event', '19000000-0000-0000-0000-000000000004', 'EVENT_CANCELLED',
     '{"status": {"old": "SCHEDULED", "new": "CANCELLED"}}', 'Urlop instruktorki, brak zastępstwa.', '47000000-0000-0000-0000-000000000003'),
    (now() - interval '80 days',  '03000000-0000-0000-0000-000000000001', 'membership', 'customer_pass',  '28000000-0000-0000-0000-000000000004', 'PASS_ADJUSTED',
     '{"entries_remaining": {"old": 4, "new": 0}}', 'Wejścia wykorzystane przed wdrożeniem aplikacji', '47000000-0000-0000-0000-000000000004'),
    (now() - interval '30 days',  '03000000-0000-0000-0000-000000000001', 'identity',   'account',        '03000000-0000-0000-0000-000000000009', 'ACCOUNT_SUSPENDED',
     '{"status": {"old": "ACTIVE", "new": "SUSPENDED"}}', 'Zaległa płatność za karnet', '47000000-0000-0000-0000-000000000005')
) AS v(occurred_at, actor_account_id, module, entity_type, entity_id, action, changes, reason, correlation_id)
WHERE NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.correlation_id = v.correlation_id::uuid);

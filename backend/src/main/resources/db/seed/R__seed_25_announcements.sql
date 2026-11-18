-- DEV/STAGE ONLY. Announcements sent by the administrator Beata Lis (#157), for the
-- "Komunikacja → Komunikaty" history in the panel and the notification inbox in the app.
-- Insert-only. Katarzyna has accepted the consent to marketing notifications in the app
-- (MARKETING_PUSH, created by migration V23), so she is the only one who got the promotion.
-- IDs end in 25NNNN: announcements 42000000-…, their notifications 44000000-…, deliveries 45000000-….

INSERT INTO document_acceptance (id, document_version_id, customer_id, accepted_by, accepted_at, source)
SELECT '39000000-0000-0000-0000-000000250001', v.id, '07000000-0000-0000-0000-000000000001',
       '03000000-0000-0000-0000-000000000004', now() - interval '20 days', 'APP'
FROM document_version v JOIN document d ON d.id = v.document_id
WHERE d.code = 'MARKETING_PUSH' AND v.version_no = 1
ON CONFLICT DO NOTHING;

INSERT INTO announcement (id, author_id, audience, target_account_id, training_group_id, event_id, is_marketing,
                          title, body, created_at, sent_at, skipped_without_consent) VALUES
    ('42000000-0000-0000-0000-000000250001', '03000000-0000-0000-0000-000000000010', 'ALL',    NULL, NULL, NULL, FALSE,
     'Nowy grafik od grudnia', 'Od 1 grudnia zmieniamy grafik zajęć. Nowe godziny są już w aplikacji, zapisy działają jak dotąd.',
     now() - interval '3 days', now() - interval '3 days', 0),
    ('42000000-0000-0000-0000-000000250002', '03000000-0000-0000-0000-000000000010', 'GROUP',  NULL, '16000000-0000-0000-0000-000000000001', NULL, FALSE,
     'Zmiana sali w czwartek', 'W czwartek ćwiczymy w Sali 2 (piętro wyżej). Godzina bez zmian.',
     now() - interval '26 hours', now() - interval '26 hours', 0),
    ('42000000-0000-0000-0000-000000250003', '03000000-0000-0000-0000-000000000010', 'ALL',    NULL, NULL, NULL, TRUE,
     'Promocja −20% na karnety', 'Tylko do niedzieli wszystkie karnety 20% taniej. Kup w recepcji lub zapytaj trenera.',
     now() - interval '20 hours', now() - interval '20 hours', 3),
    ('42000000-0000-0000-0000-000000250004', '03000000-0000-0000-0000-000000000010', 'PERSON', '03000000-0000-0000-0000-000000000005', NULL, NULL, FALSE,
     'Trening personalny w piątek', 'Zabierz proszę matę na piątkowy trening.',
     now() - interval '2 hours', now() - interval '2 hours', 0)
ON CONFLICT DO NOTHING;

INSERT INTO notification (id, recipient_id, notification_type_id, recipient_role, subject_customer_id, category, title, body,
                          payload, announcement_id, dedup_key, scheduled_for, created_at, read_at)
SELECT n.id::UUID, n.recipient::UUID, 14, n.role, n.subject::UUID, n.category, n.title, a.body,
       jsonb_strip_nulls(jsonb_build_object('announcementId', a.id::TEXT, 'marketing', a.is_marketing::TEXT,
                                            'author', 'Beata Lis', 'audience', n.audience, 'about', n.about)),
       a.id, 'ANNOUNCEMENT:' || a.id || ':' || n.recipient, a.sent_at, a.sent_at,
       CASE WHEN n.read THEN a.sent_at + interval '1 hour' END
FROM (VALUES
    -- Nowy grafik: every active client (Karolina is also a trainer, so her title names the role).
    ('44000000-0000-0000-0000-000000250001', '42000000-0000-0000-0000-000000250001', '03000000-0000-0000-0000-000000000004', 'CLIENT', NULL, 'TRANSACTIONAL', 'Nowy grafik od grudnia',           NULL, NULL, TRUE),
    ('44000000-0000-0000-0000-000000250002', '42000000-0000-0000-0000-000000250001', '03000000-0000-0000-0000-000000000005', 'CLIENT', NULL, 'TRANSACTIONAL', 'Nowy grafik od grudnia',           NULL, NULL, TRUE),
    ('44000000-0000-0000-0000-000000250003', '42000000-0000-0000-0000-000000250001', '03000000-0000-0000-0000-000000000006', 'CLIENT', NULL, 'TRANSACTIONAL', 'Nowy grafik od grudnia',           NULL, NULL, FALSE),
    ('44000000-0000-0000-0000-000000250004', '42000000-0000-0000-0000-000000250001', '03000000-0000-0000-0000-000000000011', 'CLIENT', NULL, 'TRANSACTIONAL', 'Klient: Nowy grafik od grudnia',   NULL, NULL, FALSE),
    -- Zmiana sali: the members of the beginners' pole dance group (Katarzyna, Monika).
    ('44000000-0000-0000-0000-000000250005', '42000000-0000-0000-0000-000000250002', '03000000-0000-0000-0000-000000000004', 'CLIENT', NULL, 'TRANSACTIONAL', 'Zmiana sali w czwartek',           'grupa Pole dance początkujący wt/czw 18:00', NULL, FALSE),
    ('44000000-0000-0000-0000-000000250006', '42000000-0000-0000-0000-000000250002', '03000000-0000-0000-0000-000000000006', 'CLIENT', NULL, 'TRANSACTIONAL', 'Zmiana sali w czwartek',           'grupa Pole dance początkujący wt/czw 18:00', NULL, TRUE),
    -- Promocja: only Katarzyna has the marketing consent.
    ('44000000-0000-0000-0000-000000250007', '42000000-0000-0000-0000-000000250003', '03000000-0000-0000-0000-000000000004', 'CLIENT', NULL, 'MARKETING',     'Promocja −20% na karnety',         NULL, NULL, FALSE),
    -- Trening personalny: a message to Tomasz only.
    ('44000000-0000-0000-0000-000000250008', '42000000-0000-0000-0000-000000250004', '03000000-0000-0000-0000-000000000005', 'CLIENT', NULL, 'TRANSACTIONAL', 'Trening personalny w piątek',      NULL, NULL, FALSE)
) AS n(id, announcement, recipient, role, subject, category, title, audience, about, read)
JOIN announcement a ON a.id = n.announcement::UUID
ON CONFLICT DO NOTHING;

INSERT INTO notification_delivery (id, notification_id, channel, status, provider_message_id, error, attempted_at) VALUES
    ('45000000-0000-0000-0000-000000250001', '44000000-0000-0000-0000-000000250001', 'PUSH', 'SENT',      'seed-fcm-msg-2201', NULL, now() - interval '3 days'),
    ('45000000-0000-0000-0000-000000250002', '44000000-0000-0000-0000-000000250002', 'PUSH', 'SENT',      'seed-fcm-msg-2202', NULL, now() - interval '3 days'),
    ('45000000-0000-0000-0000-000000250003', '44000000-0000-0000-0000-000000250003', 'PUSH', 'NO_DEVICE', NULL,                NULL, now() - interval '3 days'),
    ('45000000-0000-0000-0000-000000250004', '44000000-0000-0000-0000-000000250005', 'PUSH', 'SENT',      'seed-fcm-msg-2205', NULL, now() - interval '26 hours'),
    ('45000000-0000-0000-0000-000000250005', '44000000-0000-0000-0000-000000250007', 'PUSH', 'SENT',      'seed-fcm-msg-2207', NULL, now() - interval '20 hours'),
    ('45000000-0000-0000-0000-000000250006', '44000000-0000-0000-0000-000000250008', 'PUSH', 'SENT',      'seed-fcm-msg-2208', NULL, now() - interval '2 hours')
ON CONFLICT DO NOTHING;

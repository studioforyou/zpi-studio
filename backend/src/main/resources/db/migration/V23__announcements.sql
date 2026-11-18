-- Announcements the administrator sends from the panel (#157): a notification type for them,
-- how many people a marketing announcement skipped, and the voluntary consent to marketing
-- notifications in the app that such an announcement requires.

INSERT INTO notification_type (id, code, description, is_enabled, lead_time_minutes, default_channels) VALUES
    (14, 'ANNOUNCEMENT', 'A message the studio sent to one person, a training group, the participants of a class or all clients.', TRUE, NULL, 'PUSH');

ALTER TABLE announcement
    ADD COLUMN skipped_without_consent INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN announcement.audience IS 'PERSON (target_account_id), GROUP (training_group_id), EVENT (event_id) or ALL (all active clients).';
COMMENT ON COLUMN announcement.skipped_without_consent IS 'Marketing announcement only: how many matching people were left out because they have not accepted the MARKETING_PUSH consent.';

CREATE INDEX ix_notification_announcement ON notification (announcement_id) WHERE announcement_id IS NOT NULL;

INSERT INTO document (code, title, kind, audience, is_required, blocks_booking, is_active) VALUES
    ('MARKETING_PUSH', 'Zgoda na powiadomienia marketingowe w aplikacji', 'MARKETING_PUSH', 'ADULT', FALSE, FALSE, TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO document_version (document_id, version_no, content, requires_reacceptance, published_at)
SELECT id, 1,
       'Wyrażam zgodę na otrzymywanie w aplikacji studia powiadomień marketingowych, np. o promocjach i nowych zajęciach. Zgodę mogę w każdej chwili wycofać w sekcji Dokumenty i zgody.',
       FALSE, now()
FROM document WHERE code = 'MARKETING_PUSH'
ON CONFLICT (document_id, version_no) DO NOTHING;

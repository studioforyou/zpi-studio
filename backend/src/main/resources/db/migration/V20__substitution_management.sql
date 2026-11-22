ALTER TABLE substitution_request
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN decision_note VARCHAR(1000);

-- Slots 5–6 belong to waitlists; 7–9 to time off; 10–12 to schedule notifications.
INSERT INTO notification_type (id, code, description, is_enabled, default_channels)
VALUES (13, 'SUBSTITUTION_AVAILABLE', 'A qualified trainer can offer to take an upcoming substitution.', TRUE, 'PUSH');

INSERT INTO notification_template (notification_type_id, channel, locale, subject, body_template)
VALUES (13, 'PUSH', 'pl', 'Dostępne zastępstwo', '{{zajęcia}} · {{dzień}}, {{godzina}} · {{sala}}');

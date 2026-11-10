-- Notifications about new messages (#153): every other participant of a conversation gets a push
-- when a message is written. Configured by the studio administrator like the other types.

INSERT INTO notification_type (id, code, description, is_enabled, lead_time_minutes, default_channels) VALUES
    (4, 'NEW_MESSAGE', 'A new message in a conversation the recipient takes part in.', TRUE, NULL, 'PUSH');

INSERT INTO notification_template (notification_type_id, channel, locale, subject, body_template) VALUES
    (4, 'PUSH', 'pl', '{{od}}', '{{treść}}');

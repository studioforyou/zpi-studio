-- Notifications to a trainer about their time off (#124): the studio approved it (also time off
-- the studio recorded itself), rejected it or cancelled it. Configured by the studio administrator
-- like the other types.

INSERT INTO notification_type (id, code, description, is_enabled, lead_time_minutes, default_channels) VALUES
    (7, 'TIME_OFF_APPROVED', 'The studio approved a trainer''s time off or recorded it for the trainer.', TRUE, NULL, 'PUSH'),
    (8, 'TIME_OFF_REJECTED', 'The studio rejected a trainer''s time-off request.', TRUE, NULL, 'PUSH'),
    (9, 'TIME_OFF_CANCELLED', 'The studio cancelled a trainer''s time off.', TRUE, NULL, 'PUSH');

INSERT INTO notification_template (notification_type_id, channel, locale, subject, body_template) VALUES
    (7, 'PUSH', 'pl', 'Nieobecność zatwierdzona', '{{rodzaj}}: {{okres}}. {{notatka}}'),
    (8, 'PUSH', 'pl', 'Wniosek o nieobecność odrzucony', '{{rodzaj}}: {{okres}}. {{notatka}}'),
    (9, 'PUSH', 'pl', 'Studio anulowało nieobecność', '{{rodzaj}}: {{okres}}. Ten czas wraca do Twojego grafiku.');

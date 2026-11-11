-- Notifications about passes (#146): a reminder before a pass expires and a notice when a booking
-- used its last entry. Both are configured by the studio administrator like CLASS_REMINDER.

INSERT INTO notification_type (id, code, description, is_enabled, lead_time_minutes, default_channels) VALUES
    (2, 'PASS_EXPIRING', 'Reminder before the last day of a pass, so the participant can buy the next one.', TRUE, NULL, 'PUSH'),
    (3, 'PASS_LAST_ENTRY_USED', 'A booking used the last entry of a pass.', TRUE, NULL, 'PUSH');

-- Counted back from 9:00 on the last day of the pass: 3 days and 1 day before.
INSERT INTO notification_lead_time (notification_type_id, minutes_before) VALUES
    (2, 4320),
    (2, 1440);

INSERT INTO notification_template (notification_type_id, channel, locale, subject, body_template) VALUES
    (2, 'PUSH', 'pl', 'Karnet wkrótce wygaśnie',
     '{{karnet}} jest ważny do {{ważny do}}. Kup kolejny, aby dalej zapisywać się na zajęcia.'),
    (3, 'PUSH', 'pl', 'Wykorzystano ostatnie wejście',
     'W karnecie {{karnet}} nie zostało już żadne wejście. Kup kolejny, aby dalej zapisywać się na zajęcia.');

-- Applied after V17 (time-off notifications); IDs 5 and 6 are reserved for the waiting list.
INSERT INTO notification_type (id, code, description, is_enabled, default_channels) VALUES
    (5, 'WAITLIST_PLACE_OFFERED', 'A waiting-list place is available and requires confirmation before its deadline.', TRUE, 'PUSH'),
    (6, 'WAITLIST_PLACE_GRANTED', 'A participant was automatically booked from the waiting list.', TRUE, 'PUSH');

INSERT INTO notification_template (notification_type_id, channel, locale, subject, body_template) VALUES
    (5, 'PUSH', 'pl', 'Zwolniło się miejsce', '{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Potwierdź miejsce do {{potwierdź do}}.'),
    (6, 'PUSH', 'pl', 'Masz miejsce na zajęciach', '{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Zapisano Cię z listy rezerwowej.');

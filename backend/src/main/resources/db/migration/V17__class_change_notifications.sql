-- Notifications about changes of classes (#144) and their automatic cancellation (#155):
-- participants and staff get a push when the time, the rooms or the staff of a class change and
-- when it is cancelled; the staff get a separate one when a class is cancelled automatically for
-- too few participants. Configured by the studio administrator like the other types.

INSERT INTO notification_type (id, code, description, is_enabled, lead_time_minutes, default_channels) VALUES
    (5, 'CLASS_CHANGED', 'The time, the rooms or the staff of a class or training changed.', TRUE, NULL, 'PUSH'),
    (6, 'CLASS_CANCELLED', 'A class or training was cancelled.', TRUE, NULL, 'PUSH'),
    (7, 'CLASS_AUTO_CANCELLED', 'A group class was cancelled automatically for too few participants (to its staff).', TRUE, NULL, 'PUSH');

INSERT INTO notification_template (notification_type_id, channel, locale, subject, body_template) VALUES
    (5, 'PUSH', 'pl', 'Zmiana zajęć', '{{zajęcia}}, {{dzień}} {{godzina}}: {{zmiany}}.'),
    (6, 'PUSH', 'pl', 'Zajęcia odwołane', 'Odwołano: {{zajęcia}}, {{dzień}} {{godzina}}. Powód: {{powód}}.'),
    (7, 'PUSH', 'pl', 'Zajęcia odwołane automatycznie',
     'Odwołano: {{zajęcia}}, {{dzień}} {{godzina}}. Zapisanych osób: {{zapisanych}}, wymagane minimum: {{minimum}}.');

COMMENT ON COLUMN reservation.cancel_kind IS 'IN_TIME or LATE: the participant (or staff for them) cancelled before or after the free cancellation deadline; STUDIO: cancelled together with its class, e.g. by the automatic cancellation for too few participants.';
COMMENT ON COLUMN schedule_event.cancel_reason IS 'Why the event was cancelled, e.g. TOO_FEW_PARTICIPANTS (automatic cancellation) or OTHER.';

-- Notification about a class whose booking opened (#143), for the participants interested in it:
-- those who marked its service type (customer_interest) or took part in a class of that type in
-- the last 60 days. Configured by the studio administrator like the other notification types.

INSERT INTO notification_type (id, code, description, is_enabled, default_channels) VALUES
    (14, 'CLASS_BOOKING_OPENED', 'Booking opened for a class the participant is interested in or recently attended.', TRUE, 'PUSH');

INSERT INTO notification_template (notification_type_id, channel, locale, subject, body_template) VALUES
    (14, 'PUSH', 'pl', 'Ruszyły zapisy na zajęcia',
     '{{zajęcia}}: {{dzień}}, godz. {{godzina}}, {{sala}}. Zapisz się, zanim zabraknie miejsc.');

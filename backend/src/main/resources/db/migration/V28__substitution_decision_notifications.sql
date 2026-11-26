-- Notifications to trainers about decisions on substitution requests (#156): the requesting trainer
-- hears that the event is covered or that the studio closed the request without a substitute; the
-- claiming trainer hears that the studio assigned the event to them or turned the claim down.
-- Configured by the studio administrator like the other types.

INSERT INTO notification_type (id, code, description, is_enabled, lead_time_minutes, default_channels) VALUES
    (16, 'SUBSTITUTION_COVERED', 'A substitute takes the event the trainer asked to be replaced at.', TRUE, NULL, 'PUSH'),
    (17, 'SUBSTITUTION_ASSIGNED', 'The studio approved a trainer''s claim of a substitution or assigned them to it.', TRUE, NULL, 'PUSH'),
    (18, 'SUBSTITUTION_CLAIM_REJECTED', 'The studio turned down a trainer''s claim of a substitution.', TRUE, NULL, 'PUSH'),
    (19, 'SUBSTITUTION_CLOSED', 'The studio closed a trainer''s substitution request without a substitute.', TRUE, NULL, 'PUSH');

INSERT INTO notification_template (notification_type_id, channel, locale, subject, body_template, default_subject, default_body)
SELECT id, 'PUSH', 'pl', subject, body, subject, body FROM (VALUES
    (16, 'Zastępstwo potwierdzone', '{{zastępca}} poprowadzi {{zajęcia}} · {{dzień}}, {{godzina}} · {{sala}}'),
    (17, 'Prowadzisz zastępstwo', '{{zajęcia}} · {{dzień}}, {{godzina}} · {{sala}}. {{notatka}}'),
    (18, 'Studio nie przyjęło Twojego zgłoszenia', 'Zastępstwo: {{zajęcia}} · {{dzień}}, {{godzina}}. {{notatka}}'),
    (19, 'Zastępstwo nie zostanie przydzielone', 'Prowadzisz {{zajęcia}} · {{dzień}}, {{godzina}} · {{sala}}. {{notatka}}')
) AS d(id, subject, body);

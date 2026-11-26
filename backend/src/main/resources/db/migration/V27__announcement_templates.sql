-- Message templates the administrator defines and edits (#160): reusable announcement templates,
-- and the default push content of every notification type, so that edited content can be restored.

CREATE TABLE announcement_template (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(100) NOT NULL,
    title               VARCHAR(160) NOT NULL,
    body                TEXT NOT NULL,
    is_marketing        BOOLEAN NOT NULL DEFAULT FALSE,
    created_by          UUID REFERENCES account(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by          UUID REFERENCES account(id),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_announcement_template_body_length CHECK (char_length(body) <= 2000)
);
CREATE UNIQUE INDEX ux_announcement_template_name ON announcement_template (lower(name));
COMMENT ON TABLE announcement_template IS '[communication] Reusable announcement content written by the administrator. Choosing a template fills the title, text and kind of a new announcement; a sent announcement keeps its own copy, so nothing points to the template.';
COMMENT ON COLUMN announcement_template.name IS 'Name shown in the panel, unique regardless of letter case.';
COMMENT ON COLUMN announcement_template.title IS 'Announcement title; same limit as announcement.title.';
COMMENT ON COLUMN announcement_template.body IS 'Announcement text, at most 2000 characters like a sent announcement.';
COMMENT ON COLUMN announcement_template.is_marketing IS 'TRUE: an offer ("Oferta" in the app), only for people with the MARKETING_PUSH consent; FALSE: a studio message ("Komunikat studia").';

ALTER TABLE notification_template
    ADD COLUMN default_subject VARCHAR(200),
    ADD COLUMN default_body    TEXT;
COMMENT ON COLUMN notification_template.default_subject IS 'Title the migration created; the administrator can restore it after editing subject. NULL when the type has no default.';
COMMENT ON COLUMN notification_template.default_body IS 'Text the migration created; the administrator can restore it after editing body_template. NULL when the type has no default.';

-- The defaults are written out instead of copied from subject and body_template, which the
-- administrator may already have changed. Types are matched by code, so a type added by a migration
-- that is not merged yet (CLASS_BOOKING_OPENED, #143) gets its default once it exists; a type added
-- after this migration sets default_subject and default_body in its own INSERT.
UPDATE notification_template t
SET default_subject = d.subject, default_body = d.body
FROM (VALUES
    ('CLASS_REMINDER',         'Przypomnienie o zajęciach',       '{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Sala: {{sala}}.'),
    ('PASS_EXPIRING',          'Karnet wkrótce wygaśnie',         '{{karnet}} jest ważny do {{ważny do}}. Kup kolejny, aby dalej zapisywać się na zajęcia.'),
    ('PASS_LAST_ENTRY_USED',   'Wykorzystano ostatnie wejście',   'W karnecie {{karnet}} nie zostało już żadne wejście. Kup kolejny, aby dalej zapisywać się na zajęcia.'),
    ('NEW_MESSAGE',            '{{od}}',                          '{{ile}} · {{treść}}'),
    ('WAITLIST_PLACE_OFFERED', 'Zwolniło się miejsce',            '{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Potwierdź miejsce do {{potwierdź do}}.'),
    ('WAITLIST_PLACE_GRANTED', 'Masz miejsce na zajęciach',       '{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Zapisano Cię z listy rezerwowej.'),
    ('TIME_OFF_APPROVED',      'Nieobecność zatwierdzona',        '{{rodzaj}}: {{okres}}. {{notatka}}'),
    ('TIME_OFF_REJECTED',      'Wniosek o nieobecność odrzucony', '{{rodzaj}}: {{okres}}. {{notatka}}'),
    ('TIME_OFF_CANCELLED',     'Studio anulowało nieobecność',    '{{rodzaj}}: {{okres}}. Ten czas wraca do Twojego grafiku.'),
    ('CLASS_CHANGED',          'Zmiana zajęć',                    '{{zajęcia}}, {{dzień}} {{godzina}}: {{zmiany}}.'),
    ('CLASS_CANCELLED',        'Zajęcia odwołane',                'Odwołano: {{zajęcia}}, {{dzień}} {{godzina}}. Powód: {{powód}}.'),
    ('CLASS_AUTO_CANCELLED',   'Zajęcia odwołane automatycznie',  'Odwołano: {{zajęcia}}, {{dzień}} {{godzina}}. Zapisanych osób: {{zapisanych}}, wymagane minimum: {{minimum}}.'),
    ('SUBSTITUTION_AVAILABLE', 'Dostępne zastępstwo',             '{{zajęcia}} · {{dzień}}, {{godzina}} · {{sala}}'),
    ('CLASS_BOOKING_OPENED',   'Ruszyły zapisy na zajęcia',       '{{zajęcia}}: {{dzień}}, godz. {{godzina}}, {{sala}}. Zapisz się, zanim zabraknie miejsc.')
) AS d(code, subject, body)
JOIN notification_type nt ON nt.code = d.code
WHERE t.notification_type_id = nt.id AND t.channel = 'PUSH' AND t.locale = 'pl';

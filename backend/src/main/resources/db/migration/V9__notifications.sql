-- Notifications: the class reminder type, reminder times configured by the administrator,
-- who a notification is about, and the Spring Modulith event publication registry.

-- Every notification type is a row here and is configured by the studio administrator in the
-- web panel (on/off, reminder times, content). A new type = a new row in a migration.
INSERT INTO notification_type (id, code, description, is_enabled, lead_time_minutes, default_channels) VALUES
    (1, 'CLASS_REMINDER', 'Reminder about an upcoming class or training the participant has a reservation for.', TRUE, NULL, 'PUSH');

COMMENT ON COLUMN notification_type.lead_time_minutes IS 'Not used: reminder times are in notification_lead_time, a type can have several.';

CREATE TABLE notification_lead_time (
    notification_type_id SMALLINT NOT NULL REFERENCES notification_type(id),
    minutes_before       INTEGER  NOT NULL CHECK (minutes_before BETWEEN 5 AND 20160),
    PRIMARY KEY (notification_type_id, minutes_before)
);
COMMENT ON TABLE notification_lead_time IS '[communication] How long before the start a timed notification (e.g. CLASS_REMINDER) is sent; one row per reminder, set by the administrator. Between 5 minutes and 14 days.';

INSERT INTO notification_lead_time (notification_type_id, minutes_before) VALUES
    (1, 1440),
    (1, 120);

-- Default content, editable by the administrator. {{name}} placeholders (Polish names, so the
-- administrator can read them) are filled by the backend.
INSERT INTO notification_template (notification_type_id, channel, locale, subject, body_template) VALUES
    (1, 'PUSH', 'pl', 'Przypomnienie o zajęciach', '{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Sala: {{sala}}.');

ALTER TABLE notification
    ADD COLUMN recipient_role      VARCHAR(10),
    ADD COLUMN subject_customer_id UUID REFERENCES customer_profile(id);
COMMENT ON COLUMN notification.recipient_role IS 'Role the notification concerns (CLIENT, TRAINER, ADMIN), for accounts with several roles. NULL for older rows.';
COMMENT ON COLUMN notification.subject_customer_id IS 'Participant the notification is about when it is not the recipient, e.g. a child whose guardian gets it.';

CREATE INDEX ix_notification_delivery_notification ON notification_delivery (notification_id);
CREATE INDEX ix_device_token_account ON device_token (account_id) WHERE revoked_at IS NULL;

-- Spring Modulith event publication registry (schema v2 of spring-modulith-events-jdbc 2.1).
-- An event is stored in the same transaction as the business change and marked completed when
-- its listener succeeds; the notification job resubmits publications that stay incomplete.
CREATE TABLE event_publication (
    id                     UUID NOT NULL,
    listener_id            TEXT NOT NULL,
    event_type             TEXT NOT NULL,
    serialized_event       TEXT NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
COMMENT ON TABLE event_publication IS '[shared] Spring Modulith event publication registry: domain events waiting for, or completed by, their listeners.';

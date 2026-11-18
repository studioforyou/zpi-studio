ALTER TABLE staff_time_off ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

CREATE TABLE time_off_substitution (
    time_off_id UUID NOT NULL REFERENCES staff_time_off(id) ON DELETE CASCADE,
    event_id UUID NOT NULL REFERENCES schedule_event(id) ON DELETE CASCADE,
    selected BOOLEAN NOT NULL,
    PRIMARY KEY (time_off_id, event_id)
);

ALTER TABLE substitution_request
    ADD COLUMN time_off_id UUID REFERENCES staff_time_off(id) ON DELETE SET NULL;
CREATE INDEX substitution_request_time_off_idx ON substitution_request(time_off_id)
    WHERE time_off_id IS NOT NULL;

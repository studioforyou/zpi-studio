-- V12
--
-- Schedule change notices (scheduling)
-- Every saved change of an event's time, rooms or staff leaves one notice for each person it
-- concerns: the participants with an active reservation or on the waiting list, and every
-- staff member before and after the change. The app shows them until the person closes them.

CREATE TABLE schedule_change_notice (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id           UUID NOT NULL REFERENCES schedule_event(id) ON DELETE CASCADE,
    customer_id        UUID REFERENCES customer_profile(id) ON DELETE CASCADE,
    staff_id           UUID REFERENCES staff_profile(id) ON DELETE CASCADE,
    kind               VARCHAR(12) NOT NULL CHECK (kind IN ('CHANGED', 'ASSIGNED', 'UNASSIGNED')),
    event_name         VARCHAR(200),
    previous_starts_at TIMESTAMPTZ NOT NULL,
    previous_ends_at   TIMESTAMPTZ NOT NULL,
    starts_at          TIMESTAMPTZ NOT NULL,
    ends_at            TIMESTAMPTZ NOT NULL,
    previous_rooms     TEXT[] NOT NULL,
    rooms              TEXT[] NOT NULL,
    previous_leads     TEXT[] NOT NULL,
    leads              TEXT[] NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    dismissed_at       TIMESTAMPTZ,
    CHECK ((customer_id IS NULL) <> (staff_id IS NULL))
);

CREATE INDEX ix_schedule_change_notice_customer ON schedule_change_notice (customer_id) WHERE dismissed_at IS NULL;
CREATE INDEX ix_schedule_change_notice_staff ON schedule_change_notice (staff_id) WHERE dismissed_at IS NULL;
CREATE INDEX ix_schedule_change_notice_event ON schedule_change_notice (event_id);

COMMENT ON TABLE schedule_change_notice IS '[scheduling] One saved change of an event (time, rooms or staff) told to one participant (customer_id) or one staff member (staff_id), with the values before and after. Shown in the app until the person closes it.';
COMMENT ON COLUMN schedule_change_notice.kind IS 'CHANGED: the event moved or got other rooms or staff; ASSIGNED: the staff member now works at it; UNASSIGNED: the staff member no longer does.';
COMMENT ON COLUMN schedule_change_notice.dismissed_at IS 'When the person closed the notice; open notices are counted on the schedule tab.';

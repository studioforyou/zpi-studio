-- V15
--
-- Trainee card kept by trainers (trainings, #99)
-- One card per participant, shared by all of the participant's current trainers, so concurrent
-- edits are detected with an optimistic-locking version. Health notes are never edited: a trainer
-- archives a note that no longer applies, and the archive records who did it.

ALTER TABLE trainee_card
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE health_note
    ADD COLUMN archived_by UUID REFERENCES account(id);

CREATE INDEX ix_health_note_customer ON health_note (customer_id);

COMMENT ON COLUMN trainee_card.version IS 'Optimistic locking: an update sends the version it is based on; a stale one returns VERSION_CONFLICT. A participant without a row has version 0.';
COMMENT ON COLUMN health_note.kind IS 'INJURY, CONDITION, CONTRAINDICATION or OTHER.';
COMMENT ON COLUMN health_note.archived_by IS 'Account that archived the note; NULL while the note applies, or when unknown.';

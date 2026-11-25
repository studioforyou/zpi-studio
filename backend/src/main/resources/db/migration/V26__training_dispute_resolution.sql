ALTER TABLE training_dispute ADD COLUMN resolution_effect VARCHAR(24);

CREATE INDEX ix_dispute_status ON training_dispute (status, raised_at);

COMMENT ON COLUMN training_dispute.status IS 'OPEN until an administrator decides on it, then RESOLVED.';
COMMENT ON COLUMN training_dispute.resolution IS
    'UPHELD (the entry or make-up credit was given back), UPHELD_MAKEUP (a new make-up credit was granted, the entry stays used) or REJECTED (the entry stays used).';
COMMENT ON COLUMN training_dispute.resolution_effect IS
    'What the decision did to the session''s funding: ENTRY_RETURNED, MAKEUP_RETURNED, MAKEUP_GRANTED or NO_CHANGE. Kept for history.';
COMMENT ON COLUMN training_dispute.resolution_note IS 'The administrator''s explanation of the decision, shown to the client.';

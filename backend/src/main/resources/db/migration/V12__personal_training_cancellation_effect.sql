ALTER TABLE reservation ADD COLUMN personal_cancellation_effect VARCHAR(24);
COMMENT ON COLUMN reservation.personal_cancellation_effect IS
    'Actual personal-session cancellation result: MAKEUP_GRANTED, MAKEUP_RETURNED, ENTRY_FORFEITED or NO_ENTRY. Kept for history and idempotent retries.';
COMMENT ON COLUMN reservation.settlement_status IS
    'Stored personal-training decision. Read models derive FINAL from an elapsed, undisputed OPEN window using trainer_confirmed_at and the studio dispute-window setting. Explicit DISPUTED/FINAL decisions are preserved.';

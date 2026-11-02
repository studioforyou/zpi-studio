-- Resolved terms belong to the reservation. Later studio/service changes cannot alter them.
-- Existing rows intentionally remain null: without a historical snapshot the API cannot
-- promise a safe cancellation consequence and reports CANCELLATION_TERMS_UNAVAILABLE.
ALTER TABLE reservation
    ADD COLUMN cancellation_allowed_snapshot BOOLEAN,
    ADD COLUMN late_cancel_allowed_snapshot BOOLEAN,
    ADD COLUMN late_cancel_forfeits_entry_snapshot BOOLEAN,
    ADD COLUMN entry_returned BOOLEAN;

COMMENT ON COLUMN reservation.cancellation_allowed_snapshot IS 'Effective cancellation permission captured at booking time; null for legacy reservations without a safe snapshot.';
COMMENT ON COLUMN reservation.late_cancel_allowed_snapshot IS 'Effective late cancellation permission captured at booking time.';
COMMENT ON COLUMN reservation.late_cancel_forfeits_entry_snapshot IS 'Effective late cancellation entry consequence captured at booking time.';
COMMENT ON COLUMN reservation.entry_returned IS 'Actual pass entry return consequence of cancellation; null before cancellation or when historical outcome is unknown.';

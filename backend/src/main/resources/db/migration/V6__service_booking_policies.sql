ALTER TABLE booking_policy ADD COLUMN cancellation_allowed BOOLEAN;
ALTER TABLE booking_policy ADD COLUMN late_cancel_allowed BOOLEAN;

COMMENT ON COLUMN booking_policy.cancellation_allowed IS 'Whether a participant may cancel a reservation. NULL inherits the parent policy.';
COMMENT ON COLUMN booking_policy.late_cancel_allowed IS 'Whether cancellation is allowed after free_cancel_hours before the event. NULL inherits the parent policy; late_cancel_forfeits_entry controls the entry consequence.';
COMMENT ON COLUMN booking_policy.waitlist_confirm_minutes IS 'NULL inherits the parent policy; 0 = automatic promotion; a positive value = minutes to confirm an offered place.';

CREATE UNIQUE INDEX ux_service_booking_policy_current
    ON booking_policy (service_type_id)
    WHERE scope = 'SERVICE_TYPE' AND valid_to IS NULL;

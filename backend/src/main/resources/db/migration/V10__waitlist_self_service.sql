-- Participants join the waiting list of a full group class themselves and get a freed place
-- automatically (#94). The queue order (created_at, id) and the one-waiting-entry index come from V2.
ALTER TABLE waitlist_entry ADD COLUMN customer_pass_id UUID REFERENCES customer_pass(id);

COMMENT ON COLUMN waitlist_entry.customer_pass_id IS 'Pass chosen when joining. A promotion charges it, or another eligible pass of the participant when it can no longer be used; NULL for entries added without a choice.';
COMMENT ON COLUMN waitlist_entry.status IS 'WAITING: in the queue; OFFERED: a freed place is held until offer_expires_at for the participant to confirm (booking_policy.waitlist_confirm_minutes > 0); PROMOTED: got the place from the list (reservation_id); BOOKED: booked a free place directly while waiting (reservation_id); CANCELLED: left the queue or declined the offer; EXPIRED: did not confirm in time.';
COMMENT ON COLUMN waitlist_entry.offer_expires_at IS 'End of the time to confirm an OFFERED place: the confirmation time after the offer, at most until booking closes.';
COMMENT ON COLUMN reservation.source IS 'SELF, GUARDIAN, STAFF, GROUP_AUTO, or WAITLIST: a freed place given automatically to the first eligible participant waiting for it.';

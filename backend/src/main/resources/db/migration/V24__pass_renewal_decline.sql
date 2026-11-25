-- A participant (or a guardian) can say they will not renew a pass (#74): reminders to buy the next
-- one (PASS_EXPIRING, PASS_LAST_ENTRY_USED) then stop for that pass. The decision can be undone.

ALTER TABLE customer_pass ADD COLUMN renewal_declined_at TIMESTAMPTZ;

COMMENT ON COLUMN customer_pass.renewal_declined_at IS 'When the participant or a guardian said they will not renew the pass; no more reminders to buy the next one are sent for it. NULL = no decision, reminders are sent.';

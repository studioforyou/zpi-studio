-- Pass change history for administrators (#85): a change of the last valid day made by a
-- suspension, or by its cancellation, points at that suspension, so the history can show the
-- suspended period. A suspension's end moves when it is cancelled while in progress, so the period
-- cannot be read back from pass_suspension alone.

ALTER TABLE pass_ledger ADD COLUMN suspension_id UUID REFERENCES pass_suspension(id);

COMMENT ON COLUMN pass_ledger.suspension_id IS 'EXTENSION entries only: the suspension that moved the last valid day (a later day), or whose cancellation moved it back (an earlier day). NULL for an extension agreed with the participant.';

-- Entries written before this column. A suspension and its entry are saved in the same request,
-- by the same account and with the same note.
UPDATE pass_ledger ledger
SET suspension_id = (
    SELECT suspension.id FROM pass_suspension suspension
    WHERE suspension.pass_id = ledger.pass_id
      AND suspension.created_by IS NOT DISTINCT FROM ledger.actor_account_id
      AND suspension.reason IS NOT DISTINCT FROM ledger.reason
      AND abs(extract(EPOCH FROM suspension.created_at - ledger.occurred_at)) < 60
    ORDER BY abs(extract(EPOCH FROM suspension.created_at - ledger.occurred_at))
    LIMIT 1)
WHERE ledger.kind = 'EXTENSION' AND ledger.suspension_id IS NULL
  AND ledger.valid_until_after > ledger.valid_until_before;

-- A cancellation either cancelled a suspension that had not started (cancelled_at is set at the
-- same moment) or ended one in progress the day before the studio date of the cancellation.
UPDATE pass_ledger ledger
SET suspension_id = (
    SELECT suspension.id FROM pass_suspension suspension
    WHERE suspension.pass_id = ledger.pass_id
      AND ((suspension.cancelled_at IS NOT NULL
              AND abs(extract(EPOCH FROM suspension.cancelled_at - ledger.occurred_at)) < 60)
           OR (suspension.cancelled_at IS NULL
              AND suspension.ends_on = (ledger.occurred_at AT TIME ZONE
                  coalesce((SELECT time_zone FROM studio LIMIT 1), 'Europe/Warsaw'))::DATE - 1))
    ORDER BY suspension.created_at DESC
    LIMIT 1)
WHERE ledger.kind = 'EXTENSION' AND ledger.suspension_id IS NULL
  AND ledger.reason = 'Suspension cancelled'
  AND ledger.valid_until_after < ledger.valid_until_before;

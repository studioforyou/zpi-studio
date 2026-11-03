-- DEV/STAGE ONLY. Repair the original, manually granted Monika credit example:
-- Open has no make-up policy. Use a separate expired entry pass with a policy.
-- Keep the credit's original grant/expiry dates, status and usage; do not renew it.
-- This narrowly scoped fixture repair is the exception to insert-only seeding:
-- only the untouched, unused seed credit linked to the original Open is relinked.

INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total,
                           entries_remaining, price_paid, status, valid_from, valid_until,
                           created_by, created_at)
SELECT '28000000-0000-0000-0000-000000002001'::uuid, credit.customer_id,
       '14000000-0000-0000-0000-000000000001'::uuid, 'Karnet Pole Dance - 4 wejścia',
       4, 4, 0, 'EXPIRED',
       (credit.granted_at AT TIME ZONE 'Europe/Warsaw')::date - 30,
       (credit.granted_at AT TIME ZONE 'Europe/Warsaw')::date - 1,
       credit.granted_by, credit.granted_at - interval '30 days'
FROM makeup_credit credit
WHERE credit.id = '33000000-0000-0000-0000-000000000002'::uuid
  AND credit.customer_id = '07000000-0000-0000-0000-000000000003'::uuid
  AND credit.source_pass_id = '28000000-0000-0000-0000-000000000005'::uuid
  AND credit.granted_by = '03000000-0000-0000-0000-000000000001'::uuid
  AND credit.source_reservation_id IS NULL
  AND credit.status = 'AVAILABLE' AND credit.used_at IS NULL AND credit.used_reservation_id IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, valid_until_after,
                         reason, actor_account_id, occurred_at)
SELECT '34000000-0000-0000-0000-000000002001'::uuid, pass.id, 'ACTIVATION',
       pass.entries_total, pass.valid_until, 'Karnet podarowany przez studio',
       pass.created_by, pass.created_at
FROM customer_pass pass
WHERE pass.id = '28000000-0000-0000-0000-000000002001'::uuid
  AND pass.customer_id = '07000000-0000-0000-0000-000000000003'::uuid
ON CONFLICT DO NOTHING;

UPDATE makeup_credit credit
SET source_pass_id = pass.id
FROM customer_pass pass
WHERE credit.id = '33000000-0000-0000-0000-000000000002'::uuid
  AND credit.customer_id = '07000000-0000-0000-0000-000000000003'::uuid
  AND credit.source_pass_id = '28000000-0000-0000-0000-000000000005'::uuid
  AND credit.granted_by = '03000000-0000-0000-0000-000000000001'::uuid
  AND credit.source_reservation_id IS NULL
  AND credit.status = 'AVAILABLE' AND credit.used_at IS NULL AND credit.used_reservation_id IS NULL
  AND pass.id = '28000000-0000-0000-0000-000000002001'::uuid
  AND pass.customer_id = credit.customer_id
  AND pass.variant_id = '14000000-0000-0000-0000-000000000001'::uuid;

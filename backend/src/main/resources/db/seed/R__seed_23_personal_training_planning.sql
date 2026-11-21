-- Seed 23: personal-session planning (#105), DEV/STAGE only.
-- Karolina has no active cooperation with Marek: planning their first session starts it.
-- Two eligible passes let the trainer choose the funding, including a last-entry example.
-- Monika's exhausted PT package and Julia's expired package remain negative examples (seed 10).
-- Existing dates, balances, reservations and trainer decisions are never reset.
INSERT INTO pass_product_variant (id, product_id, entries_count, validity_days, price, currency, is_active) VALUES
    ('14000000-0000-0000-0000-000000023001', '13000000-0000-0000-0000-000000010001', 4, 60, 600.00, 'PLN', TRUE),
    ('14000000-0000-0000-0000-000000023002', '13000000-0000-0000-0000-000000010001', 1, 30, 150.00, 'PLN', TRUE)
ON CONFLICT DO NOTHING;

WITH examples (id, variant_id, name, entries, days, price) AS (VALUES
    ('28000000-0000-0000-0000-000000023001'::UUID, '14000000-0000-0000-0000-000000023001'::UUID,
     'Pakiet PT — 4 treningi', 4, 60, 600.00),
    ('28000000-0000-0000-0000-000000023002'::UUID, '14000000-0000-0000-0000-000000023002'::UUID,
     'Trening personalny — 1 wejście', 1, 30, 150.00)
)
INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by)
SELECT id, '07000000-0000-0000-0000-000000000009', variant_id, name, entries, entries, price, 'ACTIVE',
       (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE,
       (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + days - 1,
       '03000000-0000-0000-0000-000000000001'
FROM examples
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, valid_until_after, actor_account_id, reason)
SELECT CASE p.id WHEN '28000000-0000-0000-0000-000000023001'::UUID
            THEN '34000000-0000-0000-0000-000000023001'::UUID
            ELSE '34000000-0000-0000-0000-000000023002'::UUID END,
       p.id, 'ACTIVATION', p.entries_total, p.valid_until, p.created_by, 'Pakiet treningów personalnych'
FROM customer_pass p
WHERE p.id IN ('28000000-0000-0000-0000-000000023001', '28000000-0000-0000-0000-000000023002')
ON CONFLICT DO NOTHING;

-- A sample of already accepted seed terms makes the positive booking scenario usable.
-- Do not accept newer documents or restore an acceptance the tester revoked.
INSERT INTO document_acceptance (id, document_version_id, customer_id, accepted_by, source)
SELECT '39000000-0000-0000-0000-000000023001', v.id,
       '07000000-0000-0000-0000-000000000009', '03000000-0000-0000-0000-000000000011', 'PAPER'
FROM document_version v
WHERE v.id = '38000000-0000-0000-0000-000000000002'
AND NOT EXISTS (
    SELECT 1 FROM document_acceptance a JOIN document_version accepted ON accepted.id = a.document_version_id
    WHERE a.customer_id = '07000000-0000-0000-0000-000000000009' AND accepted.document_id = v.document_id
)
ON CONFLICT DO NOTHING;

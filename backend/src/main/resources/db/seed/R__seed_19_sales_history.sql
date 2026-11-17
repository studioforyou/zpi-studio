-- DEV/STAGE ONLY. Sales history for the "Sprzedaż" statistics (#167).
-- Insert-only: adds its own clients and their passes, never changes existing rows.
-- About half a year of passes sold before the day the seed first ran, more in recent months:
--   * clients buy most of them in the app: a paid order, a confirmed payment and the pass;
--   * the studio assigns some at the desk (no order), a few of them for free;
--   * Pole Dance 4 entries sold at its old price (160 zł) until the price change 120 days ago.
-- Clients: 24 adults without an account (07000000-…-0000190000NN) and 4 children of
-- the first four (…-0000190001NN). Passes still valid are ACTIVE with all their entries,
-- older ones EXPIRED. IDs end in DDDDNN19: DDDD = days before the first run, NN = sale of that day.

CREATE OR REPLACE FUNCTION pg_temp.seed_sales_day(day_offset INT)
RETURNS DATE LANGUAGE sql AS $$
    SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + day_offset
$$;

INSERT INTO customer_profile (id, kind, first_name, last_name, status, created_by, created_at)
SELECT ('07000000-0000-0000-0000-0000190000' || lpad(n::TEXT, 2, '0'))::UUID, 'ADULT', person.first_name,
       person.last_name, 'ACTIVE', '03000000-0000-0000-0000-000000000010',
       now() - make_interval(days => 190 - n * 7)
FROM (VALUES (1, 'Agnieszka', 'Pawlak'), (2, 'Magdalena', 'Sikora'), (3, 'Joanna', 'Baran'),
             (4, 'Paweł', 'Duda'), (5, 'Natalia', 'Szewczyk'), (6, 'Aleksandra', 'Ostrowska'),
             (7, 'Weronika', 'Tomaszewska'), (8, 'Karolina', 'Pietrzak'), (9, 'Michał', 'Jabłoński'),
             (10, 'Patrycja', 'Wróblewska'), (11, 'Dominika', 'Zając'), (12, 'Marta', 'Król'),
             (13, 'Ewelina', 'Wieczorek'), (14, 'Kamila', 'Adamczyk'), (15, 'Sylwia', 'Dudek'),
             (16, 'Łukasz', 'Nowicki'), (17, 'Izabela', 'Majewska'), (18, 'Oliwia', 'Olszewska'),
             (19, 'Paulina', 'Jaworska'), (20, 'Justyna', 'Malinowska'), (21, 'Klaudia', 'Górska'),
             (22, 'Emilia', 'Witkowska'), (23, 'Bartosz', 'Walczak'), (24, 'Zuzanna', 'Rutkowska'))
     AS person(n, first_name, last_name)
ON CONFLICT DO NOTHING;

INSERT INTO customer_profile (id, kind, first_name, last_name, birth_date, status, created_by, created_at)
SELECT ('07000000-0000-0000-0000-0000190001' || lpad(n::TEXT, 2, '0'))::UUID, 'CHILD', child.first_name,
       child.last_name, child.birth_date, 'ACTIVE', '03000000-0000-0000-0000-000000000010',
       now() - make_interval(days => 185 - n * 7)
FROM (VALUES (1, 'Hania', 'Pawlak', DATE '2016-04-12'), (2, 'Lena', 'Sikora', DATE '2017-09-03'),
             (3, 'Maja', 'Baran', DATE '2015-01-27'), (4, 'Ola', 'Duda', DATE '2018-06-15'))
     AS child(n, first_name, last_name, birth_date)
ON CONFLICT DO NOTHING;

INSERT INTO guardianship (guardian_id, child_id, relation, is_payer)
SELECT ('07000000-0000-0000-0000-0000190000' || lpad(n::TEXT, 2, '0'))::UUID,
       ('07000000-0000-0000-0000-0000190001' || lpad(n::TEXT, 2, '0'))::UUID, 'PARENT', TRUE
FROM generate_series(1, 4) AS n
ON CONFLICT DO NOTHING;

-- One row per sale: which day, what, for whom and how. hashtext keeps the mix stable between runs.
CREATE OR REPLACE FUNCTION pg_temp.seed_sales()
RETURNS TABLE (suffix TEXT, sold_at TIMESTAMPTZ, valid_from DATE, valid_until DATE, variant_id UUID,
               product_name TEXT, entries SMALLINT, price NUMERIC(10,2), buyer_id UUID, beneficiary_id UUID,
               online BOOLEAN, method TEXT)
LANGUAGE sql AS $$
    WITH slots AS (
        SELECT d, s, abs(hashtext('sale-' || d || '-' || s)) AS h
        FROM generate_series(1, 180) AS d
        CROSS JOIN generate_series(1, 5) AS s
        -- From about 4 sales every 3 days half a year ago to about 3 a day lately.
        WHERE abs(hashtext('count-' || d || '-' || s)) % 100 < 75 - d * 0.28 - (s - 1) * 10
    ), picked AS (
        SELECT d, s, h, h % 100 AS pick, (h / 100) % 24 + 1 AS client, (h / 2400) % 100 AS way
        FROM slots
    ), sales AS (
        SELECT d, s, client, way, h,
               CASE WHEN pick < 26 AND d > 120 THEN '14000000-0000-0000-0000-000000000007'
                    WHEN pick < 26 THEN '14000000-0000-0000-0000-000000000001'
                    WHEN pick < 46 THEN '14000000-0000-0000-0000-000000000002'
                    WHEN pick < 71 THEN '14000000-0000-0000-0000-000000000003'
                    WHEN pick < 84 THEN '14000000-0000-0000-0000-000000000005'
                    WHEN pick < 94 THEN '14000000-0000-0000-0000-000000000006'
                    ELSE '14000000-0000-0000-0000-000000000004' END::UUID AS variant_id
        FROM picked
    )
    SELECT lpad(d::TEXT, 4, '0') || lpad(s::TEXT, 2, '0'),
           (pg_temp.seed_sales_day(-d) + TIME '09:00' + make_interval(mins => (h % 660)::INT))
               AT TIME ZONE 'Europe/Warsaw',
           pg_temp.seed_sales_day(-d),
           pg_temp.seed_sales_day(-d) + variant.validity_days - 1,
           variant.id,
           product.name || CASE WHEN product.kind IN ('ENTRY_PASS', 'PT_PACKAGE') AND variant.entries_count > 1
                                THEN ' - ' || variant.entries_count ||
                                     CASE product.kind WHEN 'PT_PACKAGE' THEN ' sesji' ELSE ' wejść' END
                                ELSE '' END,
           variant.entries_count,
           CASE WHEN way >= 75 AND way < 79 THEN 0.00 ELSE variant.price END,
           ('07000000-0000-0000-0000-0000190000' || lpad(
               (CASE WHEN product.audience = 'CHILD' THEN client % 4 + 1 ELSE client END)::TEXT, 2, '0'))::UUID,
           ('07000000-0000-0000-0000-000019000' || CASE WHEN product.audience = 'CHILD'
               THEN '1' || lpad((client % 4 + 1)::TEXT, 2, '0') ELSE '0' || lpad(client::TEXT, 2, '0') END)::UUID,
           way < 75,
           CASE WHEN way % 3 = 0 THEN 'CARD' WHEN way < 75 THEN 'BLIK' ELSE 'CASH' END
    FROM sales
    JOIN pass_product_variant variant ON variant.id = sales.variant_id
    JOIN pass_product product ON product.id = variant.product_id
$$;

INSERT INTO customer_order (id, buyer_id, status, total_amount, currency, created_at, paid_at)
SELECT ('24000000-0000-0000-0000-0000' || suffix || '19')::UUID, buyer_id, 'PAID', price, 'PLN',
       sold_at - interval '2 minutes', sold_at
FROM pg_temp.seed_sales() WHERE online
ON CONFLICT DO NOTHING;

INSERT INTO order_item (id, order_id, variant_id, beneficiary_id, product_name, unit_price, quantity)
SELECT ('25000000-0000-0000-0000-0000' || suffix || '19')::UUID, ('24000000-0000-0000-0000-0000' || suffix || '19')::UUID,
       variant_id, beneficiary_id, product_name, price, 1
FROM pg_temp.seed_sales() WHERE online
ON CONFLICT DO NOTHING;

INSERT INTO payment (id, order_id, provider, method, status, amount, currency, created_at, confirmed_at)
SELECT ('26000000-0000-0000-0000-0000' || suffix || '19')::UUID, ('24000000-0000-0000-0000-0000' || suffix || '19')::UUID,
       'PRZELEWY24', method, 'CONFIRMED', price, 'PLN', sold_at - interval '1 minute', sold_at
FROM pg_temp.seed_sales() WHERE online
ON CONFLICT DO NOTHING;

INSERT INTO customer_pass (id, customer_id, variant_id, order_item_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by, created_at, updated_at)
SELECT ('28000000-0000-0000-0000-0000' || suffix || '19')::UUID, beneficiary_id, variant_id,
       CASE WHEN online THEN ('25000000-0000-0000-0000-0000' || suffix || '19')::UUID END,
       product_name, entries, entries, price,
       CASE WHEN valid_until < pg_temp.seed_sales_day(0) THEN 'EXPIRED' ELSE 'ACTIVE' END,
       valid_from, valid_until,
       CASE WHEN online THEN NULL ELSE '03000000-0000-0000-0000-000000000010'::UUID END, sold_at, sold_at
FROM pg_temp.seed_sales()
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, valid_until_after, reason, actor_account_id, occurred_at)
SELECT ('34000000-0000-0000-0000-0000' || suffix || '19')::UUID, ('28000000-0000-0000-0000-0000' || suffix || '19')::UUID,
       'ACTIVATION', coalesce(entries, 0), valid_until,
       CASE WHEN online THEN 'Opłacone zamówienie' WHEN price = 0 THEN 'Karnet w prezencie od studia'
            WHEN method = 'CASH' THEN 'Płatność gotówką w studiu' ELSE 'Płatność kartą w studiu' END,
       CASE WHEN online THEN NULL ELSE '03000000-0000-0000-0000-000000000010'::UUID END, sold_at
FROM pg_temp.seed_sales()
ON CONFLICT DO NOTHING;

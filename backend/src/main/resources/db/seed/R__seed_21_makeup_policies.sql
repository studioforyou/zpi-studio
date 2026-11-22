-- Seed 21: make-up policies and a trainee's make-up credits (#84, #78)
-- DEV/STAGE TEST DATA ONLY. Conventions: see R__seed_01_identity.sql.
-- Insert-only: dedicated rows with suffixes 21001.., never changes a row a tester may have edited.
--   Policies: "Odrabianie 3 tygodnie" (active, at most 3 per pass, used by "Karnet Calisthenics")
--             and "Odrabianie warsztatów" (inactive, checked against the booking date, not used).
--   Anna / Piotr: a Calisthenics pass that gave two credits out of three, one for a class he
--             cancelled in time (expires in 5 days) and one for a class the studio cancelled.
--   Anna / Julia: keeps the credit of seed 10 from her expired PT package, which reached its limit (2 of 2).

CREATE OR REPLACE FUNCTION pg_temp.seed_policy_at(day_offset INT, local_time TIME)
RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT ((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + day_offset + local_time) AT TIME ZONE 'Europe/Warsaw'
$$;

INSERT INTO makeup_policy (id, name, validity_days, max_per_pass, grant_on_in_time_cancel, grant_on_studio_cancel,
                           expiry_checked_against, is_active, created_at) VALUES
    ('12000000-0000-0000-0000-000000021001', 'Odrabianie 3 tygodnie', 21, 3, TRUE, TRUE, 'EVENT_DATE', TRUE,
     pg_temp.seed_policy_at(-60, '09:00')),
    ('12000000-0000-0000-0000-000000021002', 'Odrabianie warsztatów', 7, 1, FALSE, TRUE, 'BOOK_DATE', FALSE,
     pg_temp.seed_policy_at(-90, '09:00'))
ON CONFLICT DO NOTHING;

INSERT INTO pass_product (id, name, description, kind, audience, makeup_policy_id, suspension_allowed,
                          max_suspension_days, is_active) VALUES
    ('13000000-0000-0000-0000-000000021001', 'Karnet Calisthenics', 'Wejścia na calisthenics i warsztaty stretchingu.',
     'ENTRY_PASS', 'ADULT', '12000000-0000-0000-0000-000000021001', FALSE, NULL, TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO pass_product_variant (id, product_id, entries_count, validity_days, price, currency, is_active, valid_from) VALUES
    ('14000000-0000-0000-0000-000000021001', '13000000-0000-0000-0000-000000021001', 8, 30, 240.00, 'PLN', TRUE,
     pg_temp.seed_policy_at(-60, '09:00'))
ON CONFLICT DO NOTHING;

INSERT INTO pass_product_service (product_id, service_type_id) VALUES
    ('13000000-0000-0000-0000-000000021001', '10000000-0000-0000-0000-000000000003'),
    ('13000000-0000-0000-0000-000000021001', '10000000-0000-0000-0000-000000000006')
ON CONFLICT DO NOTHING;

-- Assigned at the desk by Beata; two entries went for the two classes he did not attend.
INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by, created_at) VALUES
    ('28000000-0000-0000-0000-000000021001', '07000000-0000-0000-0000-000000000005', '14000000-0000-0000-0000-000000021001',
     'Karnet Calisthenics - 8 wejść', 8, 6, 240.00, 'ACTIVE',
     (pg_temp.seed_policy_at(-18, '09:00') AT TIME ZONE 'Europe/Warsaw')::DATE,
     (pg_temp.seed_policy_at(11, '09:00') AT TIME ZONE 'Europe/Warsaw')::DATE,
     '03000000-0000-0000-0000-000000000010', pg_temp.seed_policy_at(-18, '09:00'))
ON CONFLICT DO NOTHING;

-- 21001: a class Piotr cancelled in time; 21002: a class the studio cancelled (trainer absent).
INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at, capacity, visibility, status,
                            cancel_reason, cancelled_by, cancelled_at, created_by) VALUES
    ('19000000-0000-0000-0000-000000021001', 'CLASS', '10000000-0000-0000-0000-000000000003', 'Calisthenics',
     pg_temp.seed_policy_at(-16, '18:00'), pg_temp.seed_policy_at(-16, '19:15'), 12, 'PUBLIC', 'COMPLETED',
     NULL, NULL, NULL, '03000000-0000-0000-0000-000000000001'),
    ('19000000-0000-0000-0000-000000021002', 'CLASS', '10000000-0000-0000-0000-000000000003', 'Calisthenics',
     pg_temp.seed_policy_at(-3, '18:00'), pg_temp.seed_policy_at(-3, '19:15'), 12, 'PUBLIC', 'CANCELLED',
     'STAFF_ABSENCE', '03000000-0000-0000-0000-000000000001', pg_temp.seed_policy_at(-4, '10:00'),
     '03000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

INSERT INTO event_staff (event_id, staff_id, role) VALUES
    ('19000000-0000-0000-0000-000000021001', '08000000-0000-0000-0000-000000000002', 'LEAD'),
    ('19000000-0000-0000-0000-000000021002', '08000000-0000-0000-0000-000000000002', 'LEAD')
ON CONFLICT DO NOTHING;

INSERT INTO event_resource (event_id, resource_id, blocks_whole_room) VALUES
    ('19000000-0000-0000-0000-000000021001', '15000000-0000-0000-0000-000000000002', FALSE),
    ('19000000-0000-0000-0000-000000021002', '15000000-0000-0000-0000-000000000002', FALSE)
ON CONFLICT DO NOTHING;

INSERT INTO reservation (id, event_id, customer_id, booked_by, source, status, customer_pass_id, booking_policy_id,
                         free_cancel_until, cancelled_at, cancelled_by, cancel_kind, entry_returned, created_at) VALUES
    ('30000000-0000-0000-0000-000000021001', '19000000-0000-0000-0000-000000021001', '07000000-0000-0000-0000-000000000005',
     '03000000-0000-0000-0000-000000000007', 'SELF', 'CANCELLED', '28000000-0000-0000-0000-000000021001',
     '11000000-0000-0000-0000-000000000001', pg_temp.seed_policy_at(-16, '06:00'), pg_temp.seed_policy_at(-17, '20:00'),
     '03000000-0000-0000-0000-000000000007', 'IN_TIME', FALSE, pg_temp.seed_policy_at(-18, '10:00')),
    ('30000000-0000-0000-0000-000000021002', '19000000-0000-0000-0000-000000021002', '07000000-0000-0000-0000-000000000005',
     '03000000-0000-0000-0000-000000000007', 'SELF', 'CANCELLED', '28000000-0000-0000-0000-000000021001',
     '11000000-0000-0000-0000-000000000001', pg_temp.seed_policy_at(-3, '06:00'), pg_temp.seed_policy_at(-4, '10:00'),
     '03000000-0000-0000-0000-000000000001', 'STUDIO', FALSE, pg_temp.seed_policy_at(-10, '10:00'))
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, valid_until_after, reservation_id, reason,
                         actor_account_id, occurred_at)
SELECT '34000000-0000-0000-0000-000000021001'::UUID, pass.id, 'ACTIVATION', 8, pass.valid_until, NULL,
       'Karnet przypisany w recepcji', pass.created_by, pass.created_at
FROM customer_pass pass WHERE pass.id = '28000000-0000-0000-0000-000000021001'
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, reservation_id, actor_account_id, occurred_at) VALUES
    ('34000000-0000-0000-0000-000000021002', '28000000-0000-0000-0000-000000021001', 'ENTRY_CONSUMED', -1,
     '30000000-0000-0000-0000-000000021001', '03000000-0000-0000-0000-000000000007', pg_temp.seed_policy_at(-17, '20:00')),
    ('34000000-0000-0000-0000-000000021003', '28000000-0000-0000-0000-000000021001', 'ENTRY_CONSUMED', -1,
     '30000000-0000-0000-0000-000000021002', '03000000-0000-0000-0000-000000000001', pg_temp.seed_policy_at(-4, '10:00'))
ON CONFLICT DO NOTHING;

-- Each credit expires 21 days after it was granted, as the policy said at the time.
INSERT INTO makeup_credit (id, customer_id, source_pass_id, source_reservation_id, granted_by, granted_at, expires_at, status) VALUES
    ('33000000-0000-0000-0000-000000021001', '07000000-0000-0000-0000-000000000005', '28000000-0000-0000-0000-000000021001',
     '30000000-0000-0000-0000-000000021001', '03000000-0000-0000-0000-000000000007',
     pg_temp.seed_policy_at(-16, '23:59'), pg_temp.seed_policy_at(5, '23:59'), 'AVAILABLE'),
    ('33000000-0000-0000-0000-000000021002', '07000000-0000-0000-0000-000000000005', '28000000-0000-0000-0000-000000021001',
     '30000000-0000-0000-0000-000000021002', '03000000-0000-0000-0000-000000000001',
     pg_temp.seed_policy_at(-4, '10:00'), pg_temp.seed_policy_at(17, '10:00'), 'AVAILABLE')
ON CONFLICT DO NOTHING;

-- Seed 04: orders, payments, passes, reservations, waitlist, make-up credits, pass ledger
-- DEV/STAGE TEST DATA ONLY. Conventions: see R__seed_01_identity.sql.
--
-- Passes (28...NN) and their ledger balance:
--   01 Katarzyna  Karnet Pole Dance 8 wejść   ACTIVE           5 left (1 used, 2 booked, 1 returned)
--   02 Zosia      Karnet dziecięcy 4 wejścia  ACTIVE           3 left
--   03 Tomasz     Pakiet PT 5 sesji           ACTIVE           4 left
--   04 Katarzyna  Karnet Pole Dance 4 wejścia EXPIRED          0 left
--   05 Monika     Karnet Open (manual, gift)  ACTIVE           time pass, suspended once
--   06 Piotr      Wejście jednorazowe         PENDING_PAYMENT

INSERT INTO customer_order (id, buyer_id, status, total_amount, currency, created_by, created_at, paid_at) VALUES
    ('24000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000001', 'PAID',      260.00, 'PLN', '03000000-0000-0000-0000-000000000004', now() - interval '10 days',  now() - interval '10 days'),
    ('24000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000003', 'PAID',      160.00, 'PLN', '03000000-0000-0000-0000-000000000006', now() - interval '20 days',  now() - interval '20 days'),
    ('24000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000002', 'PAID',      750.00, 'PLN', '03000000-0000-0000-0000-000000000002', now() - interval '30 days',  now() - interval '30 days'),
    ('24000000-0000-0000-0000-000000000004', '07000000-0000-0000-0000-000000000005', 'CREATED',   55.00,  'PLN', '03000000-0000-0000-0000-000000000007', now() - interval '1 day',    NULL),
    ('24000000-0000-0000-0000-000000000005', '07000000-0000-0000-0000-000000000001', 'PAID',      180.00, 'PLN', '03000000-0000-0000-0000-000000000004', now() - interval '100 days', now() - interval '100 days'),
    ('24000000-0000-0000-0000-000000000006', '07000000-0000-0000-0000-000000000006', 'CANCELLED', 180.00, 'PLN', '03000000-0000-0000-0000-000000000009', now() - interval '40 days',  NULL)
ON CONFLICT DO NOTHING;

INSERT INTO order_item (id, order_id, variant_id, beneficiary_id, product_name, unit_price, quantity) VALUES
    ('25000000-0000-0000-0000-000000000001', '24000000-0000-0000-0000-000000000001', '14000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000001', 'Karnet Pole Dance - 8 wejść',  260.00, 1),
    ('25000000-0000-0000-0000-000000000002', '24000000-0000-0000-0000-000000000002', '14000000-0000-0000-0000-000000000006', '07000000-0000-0000-0000-000000000004', 'Karnet dziecięcy - 4 wejścia', 160.00, 1),
    ('25000000-0000-0000-0000-000000000003', '24000000-0000-0000-0000-000000000003', '14000000-0000-0000-0000-000000000004', '07000000-0000-0000-0000-000000000002', 'Pakiet PT - 5 sesji',          750.00, 1),
    ('25000000-0000-0000-0000-000000000004', '24000000-0000-0000-0000-000000000004', '14000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000005', 'Wejście jednorazowe',          55.00,  1),
    ('25000000-0000-0000-0000-000000000005', '24000000-0000-0000-0000-000000000005', '14000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000001', 'Karnet Pole Dance - 4 wejścia',180.00, 1),
    ('25000000-0000-0000-0000-000000000006', '24000000-0000-0000-0000-000000000006', '14000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000006', 'Karnet Pole Dance - 4 wejścia',180.00, 1)
ON CONFLICT DO NOTHING;

INSERT INTO payment (id, order_id, provider, method, status, amount, currency, provider_session_id, provider_transaction_id, registered_by, created_at, confirmed_at) VALUES
    ('26000000-0000-0000-0000-000000000001', '24000000-0000-0000-0000-000000000001', 'PRZELEWY24', 'BLIK',     'CONFIRMED', 260.00, 'PLN', 'seed-p24-session-01', 'seed-p24-tx-01', NULL,                                   now() - interval '10 days',  now() - interval '10 days'),
    ('26000000-0000-0000-0000-000000000002', '24000000-0000-0000-0000-000000000001', 'PRZELEWY24', 'CARD',     'FAILED',    260.00, 'PLN', 'seed-p24-session-02', NULL,             NULL,                                   now() - interval '10 days' - interval '5 minutes', NULL),
    ('26000000-0000-0000-0000-000000000003', '24000000-0000-0000-0000-000000000002', 'PRZELEWY24', 'CARD',     'CONFIRMED', 160.00, 'PLN', 'seed-p24-session-03', 'seed-p24-tx-03', NULL,                                   now() - interval '20 days',  now() - interval '20 days'),
    ('26000000-0000-0000-0000-000000000004', '24000000-0000-0000-0000-000000000003', 'MANUAL',     'CASH',     'CONFIRMED', 750.00, 'PLN', NULL,                  NULL,             '03000000-0000-0000-0000-000000000002', now() - interval '30 days',  now() - interval '30 days'),
    ('26000000-0000-0000-0000-000000000005', '24000000-0000-0000-0000-000000000004', 'PRZELEWY24', 'TRANSFER', 'INITIATED', 55.00,  'PLN', 'seed-p24-session-05', NULL,             NULL,                                   now() - interval '1 day',    NULL),
    ('26000000-0000-0000-0000-000000000006', '24000000-0000-0000-0000-000000000005', 'PRZELEWY24', 'BLIK',     'CONFIRMED', 180.00, 'PLN', 'seed-p24-session-06', 'seed-p24-tx-06', NULL,                                   now() - interval '100 days', now() - interval '100 days'),
    ('26000000-0000-0000-0000-000000000007', '24000000-0000-0000-0000-000000000006', 'PRZELEWY24', 'BLIK',     'FAILED',    180.00, 'PLN', 'seed-p24-session-07', NULL,             NULL,                                   now() - interval '40 days',  NULL)
ON CONFLICT DO NOTHING;

INSERT INTO payment_webhook_event (id, provider, provider_event_id, payload, received_at, processed_at, result) VALUES
    ('27000000-0000-0000-0000-000000000001', 'PRZELEWY24', 'seed-p24-event-01',
     '{"sessionId": "seed-p24-session-01", "orderId": "seed-p24-tx-01", "amount": 26000, "currency": "PLN", "status": "success"}',
     now() - interval '10 days', now() - interval '10 days', 'PROCESSED'),
    ('27000000-0000-0000-0000-000000000002', 'PRZELEWY24', 'seed-p24-event-02',
     '{"sessionId": "seed-p24-session-01", "orderId": "seed-p24-tx-01", "amount": 26000, "currency": "PLN", "status": "success"}',
     now() - interval '10 days', now() - interval '10 days', 'DUPLICATE'),
    ('27000000-0000-0000-0000-000000000003', 'PRZELEWY24', 'seed-p24-event-03',
     '{"sessionId": "seed-p24-session-07", "amount": 18000, "currency": "PLN", "status": "failed"}',
     now() - interval '40 days', now() - interval '40 days', 'PROCESSED'),
    ('27000000-0000-0000-0000-000000000004', 'PRZELEWY24', 'seed-p24-event-04',
     '{"sessionId": "seed-p24-session-05", "status": "pending"}',
     now() - interval '1 hour', NULL, NULL)
ON CONFLICT DO NOTHING;

INSERT INTO customer_pass (id, customer_id, variant_id, order_item_id, product_name, entries_total, entries_remaining, price_paid, status, valid_from, valid_until, created_by, created_at) VALUES
    ('28000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000001', '14000000-0000-0000-0000-000000000002', '25000000-0000-0000-0000-000000000001', 'Karnet Pole Dance - 8 wejść',   8,    5,    260.00, 'ACTIVE',          CURRENT_DATE - 10,  CURRENT_DATE + 20, '03000000-0000-0000-0000-000000000004', now() - interval '10 days'),
    ('28000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000004', '14000000-0000-0000-0000-000000000006', '25000000-0000-0000-0000-000000000002', 'Karnet dziecięcy - 4 wejścia',  4,    3,    160.00, 'ACTIVE',          CURRENT_DATE - 20,  CURRENT_DATE + 10, '03000000-0000-0000-0000-000000000006', now() - interval '20 days'),
    ('28000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000002', '14000000-0000-0000-0000-000000000004', '25000000-0000-0000-0000-000000000003', 'Pakiet PT - 5 sesji',           5,    4,    750.00, 'ACTIVE',          CURRENT_DATE - 30,  CURRENT_DATE + 30, '03000000-0000-0000-0000-000000000002', now() - interval '30 days'),
    ('28000000-0000-0000-0000-000000000004', '07000000-0000-0000-0000-000000000001', '14000000-0000-0000-0000-000000000001', '25000000-0000-0000-0000-000000000005', 'Karnet Pole Dance - 4 wejścia', 4,    0,    180.00, 'EXPIRED',         CURRENT_DATE - 100, CURRENT_DATE - 70, '03000000-0000-0000-0000-000000000004', now() - interval '100 days'),
    ('28000000-0000-0000-0000-000000000005', '07000000-0000-0000-0000-000000000003', '14000000-0000-0000-0000-000000000005', NULL,                                   'Karnet Open - miesiąc',         NULL, NULL, 0.00,   'ACTIVE',          CURRENT_DATE - 15,  CURRENT_DATE + 22, '03000000-0000-0000-0000-000000000001', now() - interval '15 days'),
    ('28000000-0000-0000-0000-000000000006', '07000000-0000-0000-0000-000000000005', '14000000-0000-0000-0000-000000000003', '25000000-0000-0000-0000-000000000004', 'Wejście jednorazowe',           1,    1,    55.00,  'PENDING_PAYMENT', NULL,               NULL,              '03000000-0000-0000-0000-000000000007', now() - interval '1 day')
ON CONFLICT DO NOTHING;

INSERT INTO pass_suspension (id, pass_id, starts_on, ends_on, reason, created_by, created_at, cancelled_at) VALUES
    ('29000000-0000-0000-0000-000000000001', '28000000-0000-0000-0000-000000000005', CURRENT_DATE - 12, CURRENT_DATE - 6,  'Choroba',          '03000000-0000-0000-0000-000000000001', now() - interval '12 days', NULL),
    ('29000000-0000-0000-0000-000000000002', '28000000-0000-0000-0000-000000000005', CURRENT_DATE + 5,  CURRENT_DATE + 10, 'Wyjazd służbowy',  '03000000-0000-0000-0000-000000000006', now() - interval '3 days',  now() - interval '2 days')
ON CONFLICT DO NOTHING;

INSERT INTO reservation (id, event_id, customer_id, booked_by, source, status, customer_pass_id, booking_policy_id, free_cancel_until,
                         cancelled_at, cancelled_by, cancel_kind, attendance, attendance_marked_by, attendance_marked_at,
                         trainer_confirmed_at, client_confirmed_at, settlement_status, created_at) VALUES
    -- Katarzyna: past class, attended
    ('30000000-0000-0000-0000-000000000001', '19000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', 'SELF', 'ACTIVE',
     '28000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', (SELECT starts_at - interval '12 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000001'),
     NULL, NULL, NULL, 'PRESENT', '03000000-0000-0000-0000-000000000001', (SELECT ends_at FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000001'),
     NULL, NULL, 'FINAL', now() - interval '9 days'),
    -- Katarzyna: auto-booked from the fixed group
    ('30000000-0000-0000-0000-000000000002', '19000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000001', 'GROUP_AUTO', 'ACTIVE',
     '28000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', (SELECT starts_at - interval '12 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000002'),
     NULL, NULL, NULL, 'UNMARKED', NULL, NULL, NULL, NULL, 'OPEN', now() - interval '7 days'),
    -- Event 03 is full (capacity 2): Katarzyna + Monika, others on the waitlist
    ('30000000-0000-0000-0000-000000000003', '19000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', 'SELF', 'ACTIVE',
     '28000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', (SELECT starts_at - interval '12 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000003'),
     NULL, NULL, NULL, 'UNMARKED', NULL, NULL, NULL, NULL, 'OPEN', now() - interval '5 days'),
    ('30000000-0000-0000-0000-000000000004', '19000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', 'SELF', 'ACTIVE',
     '28000000-0000-0000-0000-000000000005', '11000000-0000-0000-0000-000000000001', (SELECT starts_at - interval '12 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000003'),
     NULL, NULL, NULL, 'UNMARKED', NULL, NULL, NULL, NULL, 'OPEN', now() - interval '4 days'),
    -- Zosia (child), booked by her mother
    ('30000000-0000-0000-0000-000000000005', '19000000-0000-0000-0000-000000000006', '07000000-0000-0000-0000-000000000004', '03000000-0000-0000-0000-000000000006', 'GUARDIAN', 'ACTIVE',
     '28000000-0000-0000-0000-000000000002', '11000000-0000-0000-0000-000000000003', (SELECT starts_at - interval '12 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000006'),
     NULL, NULL, NULL, 'UNMARKED', NULL, NULL, NULL, NULL, 'OPEN', now() - interval '3 days'),
    -- Tomasz: past personal training, confirmed by the trainer, disputed by the client
    ('30000000-0000-0000-0000-000000000006', '19000000-0000-0000-0000-000000000007', '07000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000002', 'STAFF', 'ACTIVE',
     '28000000-0000-0000-0000-000000000003', '11000000-0000-0000-0000-000000000002', (SELECT starts_at - interval '24 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000007'),
     NULL, NULL, NULL, 'PRESENT', '03000000-0000-0000-0000-000000000002', (SELECT ends_at FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000007'),
     (SELECT ends_at FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000007'), NULL, 'DISPUTED', now() - interval '14 days'),
    -- Katarzyna: upcoming personal training, paid on site
    ('30000000-0000-0000-0000-000000000007', '19000000-0000-0000-0000-000000000008', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000003', 'STAFF', 'ACTIVE',
     NULL, '11000000-0000-0000-0000-000000000002', (SELECT starts_at - interval '24 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000008'),
     NULL, NULL, NULL, 'UNMARKED', NULL, NULL, NULL, NULL, 'OPEN', now() - interval '2 days'),
    -- Katarzyna: class cancelled by the studio -> entry returned + make-up credit
    ('30000000-0000-0000-0000-000000000008', '19000000-0000-0000-0000-000000000004', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000001', 'GROUP_AUTO', 'CANCELLED',
     '28000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001', (SELECT starts_at - interval '12 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000004'),
     now() - interval '1 day', '03000000-0000-0000-0000-000000000001', 'STUDIO', 'UNMARKED', NULL, NULL, NULL, NULL, 'OPEN', now() - interval '7 days'),
    -- Monika: no-show on a past class
    ('30000000-0000-0000-0000-000000000010', '19000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', 'SELF', 'ACTIVE',
     '28000000-0000-0000-0000-000000000005', '11000000-0000-0000-0000-000000000001', (SELECT starts_at - interval '12 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000001'),
     NULL, NULL, NULL, 'ABSENT', '03000000-0000-0000-0000-000000000001', (SELECT ends_at FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000001'),
     NULL, NULL, 'FINAL', now() - interval '10 days'),
    -- Monika: workshop (event-level booking policy)
    ('30000000-0000-0000-0000-000000000011', '19000000-0000-0000-0000-000000000009', '07000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', 'SELF', 'ACTIVE',
     NULL, '11000000-0000-0000-0000-000000000004', (SELECT starts_at - interval '48 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000009'),
     NULL, NULL, NULL, 'UNMARKED', NULL, NULL, NULL, NULL, 'OPEN', now() - interval '3 days'),
    -- Tomasz: auto-booked into the calisthenics group
    ('30000000-0000-0000-0000-000000000012', '19000000-0000-0000-0000-000000000005', '07000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000002', 'GROUP_AUTO', 'ACTIVE',
     NULL, '11000000-0000-0000-0000-000000000001', (SELECT starts_at - interval '12 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000005'),
     NULL, NULL, NULL, 'UNMARKED', NULL, NULL, NULL, NULL, 'OPEN', now() - interval '7 days'),
    -- Katarzyna: workshop cancelled in time by the client
    ('30000000-0000-0000-0000-000000000013', '19000000-0000-0000-0000-000000000009', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', 'SELF', 'CANCELLED',
     NULL, '11000000-0000-0000-0000-000000000004', (SELECT starts_at - interval '48 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000009'),
     now() - interval '1 day', '03000000-0000-0000-0000-000000000004', 'IN_TIME', 'UNMARKED', NULL, NULL, NULL, NULL, 'OPEN', now() - interval '4 days')
ON CONFLICT DO NOTHING;

INSERT INTO waitlist_entry (id, event_id, customer_id, added_by, status, offer_expires_at, reservation_id, created_at, resolved_at) VALUES
    ('31000000-0000-0000-0000-000000000001', '19000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000005', 'WAITING', NULL, NULL, now() - interval '3 days', NULL),
    ('31000000-0000-0000-0000-000000000002', '19000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000008', '03000000-0000-0000-0000-000000000001', 'WAITING', NULL, NULL, now() - interval '2 days', NULL),
    ('31000000-0000-0000-0000-000000000003', '19000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000006', '03000000-0000-0000-0000-000000000009', 'EXPIRED', NULL, NULL, now() - interval '12 days', now() - interval '8 days'),
    ('31000000-0000-0000-0000-000000000004', '19000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000003', '03000000-0000-0000-0000-000000000006', 'PROMOTED', NULL, '30000000-0000-0000-0000-000000000010', now() - interval '11 days', now() - interval '10 days')
ON CONFLICT DO NOTHING;

INSERT INTO training_dispute (id, reservation_id, raised_by, reason, status, resolution, resolution_note, resolved_by, raised_at, resolved_at) VALUES
    ('32000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000006', '03000000-0000-0000-0000-000000000005', 'Trening trwał 30 minut zamiast 60.', 'OPEN', NULL, NULL, NULL, now() - interval '5 days', NULL)
ON CONFLICT DO NOTHING;

-- Make-up credit 01 and reservation 09 point at each other, so they go in one statement: both foreign keys
-- are checked only after both rows exist.
WITH credit AS (
    INSERT INTO makeup_credit (id, customer_id, source_pass_id, source_reservation_id, granted_by, granted_at, expires_at, status, used_reservation_id, used_at)
    VALUES ('33000000-0000-0000-0000-000000000001', '07000000-0000-0000-0000-000000000001', '28000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000008',
            NULL, now() - interval '1 day', now() + interval '30 days', 'USED', '30000000-0000-0000-0000-000000000009', now() - interval '12 hours')
    ON CONFLICT DO NOTHING
)
INSERT INTO reservation (id, event_id, customer_id, booked_by, source, status, makeup_credit_id, booking_policy_id, free_cancel_until, created_at)
VALUES ('30000000-0000-0000-0000-000000000009', '19000000-0000-0000-0000-000000000012', '07000000-0000-0000-0000-000000000001', '03000000-0000-0000-0000-000000000004', 'SELF', 'ACTIVE',
        '33000000-0000-0000-0000-000000000001', '11000000-0000-0000-0000-000000000001',
        (SELECT starts_at - interval '12 hours' FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000000012'), now() - interval '12 hours')
ON CONFLICT DO NOTHING;

INSERT INTO makeup_credit (id, customer_id, source_pass_id, source_reservation_id, granted_by, granted_at, expires_at, status, used_reservation_id, used_at) VALUES
    ('33000000-0000-0000-0000-000000000002', '07000000-0000-0000-0000-000000000003', '28000000-0000-0000-0000-000000000005', NULL, '03000000-0000-0000-0000-000000000001', now() - interval '5 days',  now() + interval '25 days', 'AVAILABLE', NULL, NULL),
    ('33000000-0000-0000-0000-000000000003', '07000000-0000-0000-0000-000000000001', '28000000-0000-0000-0000-000000000004', NULL, '03000000-0000-0000-0000-000000000001', now() - interval '90 days', now() - interval '60 days', 'EXPIRED',   NULL, NULL)
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, valid_until_before, valid_until_after, reservation_id, reason, actor_account_id, occurred_at) VALUES
    ('34000000-0000-0000-0000-000000000001', '28000000-0000-0000-0000-000000000001', 'ACTIVATION',     8,  NULL,              CURRENT_DATE + 20, NULL,                                   'Opłacone zamówienie',          '03000000-0000-0000-0000-000000000004', now() - interval '10 days'),
    ('34000000-0000-0000-0000-000000000002', '28000000-0000-0000-0000-000000000001', 'ENTRY_CONSUMED', -1, NULL,              NULL,              '30000000-0000-0000-0000-000000000001', NULL,                           '03000000-0000-0000-0000-000000000004', now() - interval '9 days'),
    ('34000000-0000-0000-0000-000000000003', '28000000-0000-0000-0000-000000000001', 'ENTRY_RESERVED', -1, NULL,              NULL,              '30000000-0000-0000-0000-000000000002', NULL,                           '03000000-0000-0000-0000-000000000001', now() - interval '7 days'),
    ('34000000-0000-0000-0000-000000000004', '28000000-0000-0000-0000-000000000001', 'ENTRY_RESERVED', -1, NULL,              NULL,              '30000000-0000-0000-0000-000000000003', NULL,                           '03000000-0000-0000-0000-000000000004', now() - interval '5 days'),
    ('34000000-0000-0000-0000-000000000005', '28000000-0000-0000-0000-000000000001', 'ENTRY_RESERVED', -1, NULL,              NULL,              '30000000-0000-0000-0000-000000000008', NULL,                           '03000000-0000-0000-0000-000000000001', now() - interval '7 days'),
    ('34000000-0000-0000-0000-000000000006', '28000000-0000-0000-0000-000000000001', 'ENTRY_RETURNED', 1,  NULL,              NULL,              '30000000-0000-0000-0000-000000000008', 'Zajęcia odwołane przez studio','03000000-0000-0000-0000-000000000001', now() - interval '1 day'),
    ('34000000-0000-0000-0000-000000000007', '28000000-0000-0000-0000-000000000002', 'ACTIVATION',     4,  NULL,              CURRENT_DATE + 10, NULL,                                   'Opłacone zamówienie',          '03000000-0000-0000-0000-000000000006', now() - interval '20 days'),
    ('34000000-0000-0000-0000-000000000008', '28000000-0000-0000-0000-000000000002', 'ENTRY_RESERVED', -1, NULL,              NULL,              '30000000-0000-0000-0000-000000000005', NULL,                           '03000000-0000-0000-0000-000000000006', now() - interval '3 days'),
    ('34000000-0000-0000-0000-000000000009', '28000000-0000-0000-0000-000000000003', 'ACTIVATION',     5,  NULL,              CURRENT_DATE + 30, NULL,                                   'Płatność gotówką',             '03000000-0000-0000-0000-000000000002', now() - interval '30 days'),
    ('34000000-0000-0000-0000-000000000010', '28000000-0000-0000-0000-000000000003', 'ENTRY_CONSUMED', -1, NULL,              NULL,              '30000000-0000-0000-0000-000000000006', NULL,                           '03000000-0000-0000-0000-000000000002', now() - interval '6 days'),
    ('34000000-0000-0000-0000-000000000011', '28000000-0000-0000-0000-000000000004', 'ACTIVATION',     4,  NULL,              CURRENT_DATE - 70, NULL,                                   'Opłacone zamówienie',          '03000000-0000-0000-0000-000000000004', now() - interval '100 days'),
    ('34000000-0000-0000-0000-000000000012', '28000000-0000-0000-0000-000000000004', 'CORRECTION',     -4, NULL,              NULL,              NULL,                                   'Wejścia wykorzystane przed wdrożeniem aplikacji', '03000000-0000-0000-0000-000000000001', now() - interval '80 days'),
    ('34000000-0000-0000-0000-000000000013', '28000000-0000-0000-0000-000000000004', 'EXPIRATION',     0,  NULL,              NULL,              NULL,                                   NULL,                           NULL,                                   now() - interval '70 days'),
    ('34000000-0000-0000-0000-000000000014', '28000000-0000-0000-0000-000000000005', 'ACTIVATION',     0,  NULL,              CURRENT_DATE + 15, NULL,                                   'Karnet w prezencie od studia', '03000000-0000-0000-0000-000000000001', now() - interval '15 days'),
    ('34000000-0000-0000-0000-000000000015', '28000000-0000-0000-0000-000000000005', 'EXTENSION',      0,  CURRENT_DATE + 15, CURRENT_DATE + 22, NULL,                                   'Zawieszenie na 7 dni',         '03000000-0000-0000-0000-000000000001', now() - interval '12 days')
ON CONFLICT DO NOTHING;

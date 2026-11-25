-- DEV/STAGE ONLY. Two personal-session disputes Beata already decided, for the history of resolved disputes.
-- Tomasz's separate package keeps its balance consistent with the ledger: 8 - 2 used + 1 returned = 7.
INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by)
VALUES ('28000000-0000-0000-0000-000000027001', '07000000-0000-0000-0000-000000000002',
        '14000000-0000-0000-0000-000000010001', 'Pakiet PT — rozstrzygnięte zgłoszenia', 8, 7, 1120,
        'ACTIVE', CURRENT_DATE - 20, CURRENT_DATE + 40, '03000000-0000-0000-0000-000000000010')
ON CONFLICT DO NOTHING;

INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at, capacity, visibility, status, created_by)
VALUES ('19000000-0000-0000-0000-000000027001', 'PERSONAL_TRAINING', '10000000-0000-0000-0000-000000000004',
        'Personalny — zgłoszenie uznane', CURRENT_TIMESTAMP - INTERVAL '8 days',
        CURRENT_TIMESTAMP - INTERVAL '8 days' + INTERVAL '1 hour', 1, 'PRIVATE', 'COMPLETED',
        '03000000-0000-0000-0000-000000000002'),
       ('19000000-0000-0000-0000-000000027002', 'PERSONAL_TRAINING', '10000000-0000-0000-0000-000000000004',
        'Personalny — zgłoszenie odrzucone', CURRENT_TIMESTAMP - INTERVAL '6 days',
        CURRENT_TIMESTAMP - INTERVAL '6 days' + INTERVAL '1 hour', 1, 'PRIVATE', 'COMPLETED',
        '03000000-0000-0000-0000-000000000002')
ON CONFLICT DO NOTHING;

INSERT INTO event_staff (event_id, staff_id, role)
VALUES ('19000000-0000-0000-0000-000000027001', '08000000-0000-0000-0000-000000000002', 'LEAD'),
       ('19000000-0000-0000-0000-000000027002', '08000000-0000-0000-0000-000000000002', 'LEAD')
ON CONFLICT DO NOTHING;

INSERT INTO event_resource (event_id, resource_id, blocks_whole_room)
VALUES ('19000000-0000-0000-0000-000000027001', '15000000-0000-0000-0000-000000000002', FALSE),
       ('19000000-0000-0000-0000-000000027002', '15000000-0000-0000-0000-000000000002', FALSE)
ON CONFLICT DO NOTHING;

INSERT INTO reservation (id, event_id, customer_id, booked_by, source, customer_pass_id, free_cancel_until,
                         cancellation_allowed_snapshot, late_cancel_allowed_snapshot, late_cancel_forfeits_entry_snapshot,
                         trainer_confirmed_at, attendance, attendance_marked_by, attendance_marked_at,
                         settlement_status, version)
SELECT ('30000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, event.id,
       '07000000-0000-0000-0000-000000000002', '03000000-0000-0000-0000-000000000002', 'STAFF',
       '28000000-0000-0000-0000-000000027001', event.starts_at - INTERVAL '24 hours', TRUE, TRUE, TRUE,
       event.ends_at + INTERVAL '15 minutes', 'PRESENT', '03000000-0000-0000-0000-000000000002',
       event.ends_at + INTERVAL '15 minutes', 'FINAL', 3
FROM generate_series(27001, 27002) slot JOIN schedule_event event
  ON event.id = ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, reservation_id, kind, entries_delta, reason, actor_account_id, occurred_at)
VALUES ('34000000-0000-0000-0000-000000027001', '28000000-0000-0000-0000-000000027001', NULL, 'ACTIVATION', 8,
        'Pakiet treningów personalnych', '03000000-0000-0000-0000-000000000010', CURRENT_TIMESTAMP - INTERVAL '20 days'),
       ('34000000-0000-0000-0000-000000027002', '28000000-0000-0000-0000-000000027001',
        '30000000-0000-0000-0000-000000027001', 'ENTRY_RESERVED', -1, NULL, '03000000-0000-0000-0000-000000000002',
        CURRENT_TIMESTAMP - INTERVAL '10 days'),
       ('34000000-0000-0000-0000-000000027003', '28000000-0000-0000-0000-000000027001',
        '30000000-0000-0000-0000-000000027001', 'ENTRY_CONSUMED', 0, NULL, '03000000-0000-0000-0000-000000000002',
        CURRENT_TIMESTAMP - INTERVAL '8 days' + INTERVAL '75 minutes'),
       ('34000000-0000-0000-0000-000000027004', '28000000-0000-0000-0000-000000027001',
        '30000000-0000-0000-0000-000000027001', 'CORRECTION', 1,
        'Trener potwierdził, że zajęcia skończyły się po 20 minutach. Wejście wraca do pakietu.',
        '03000000-0000-0000-0000-000000000010', CURRENT_TIMESTAMP - INTERVAL '7 days'),
       ('34000000-0000-0000-0000-000000027005', '28000000-0000-0000-0000-000000027001',
        '30000000-0000-0000-0000-000000027002', 'ENTRY_RESERVED', -1, NULL, '03000000-0000-0000-0000-000000000002',
        CURRENT_TIMESTAMP - INTERVAL '9 days'),
       ('34000000-0000-0000-0000-000000027006', '28000000-0000-0000-0000-000000027001',
        '30000000-0000-0000-0000-000000027002', 'ENTRY_CONSUMED', 0, NULL, '03000000-0000-0000-0000-000000000002',
        CURRENT_TIMESTAMP - INTERVAL '6 days' + INTERVAL '75 minutes')
ON CONFLICT DO NOTHING;

INSERT INTO training_dispute (id, reservation_id, raised_by, reason, status, resolution, resolution_effect,
                              resolution_note, resolved_by, raised_at, resolved_at)
VALUES ('32000000-0000-0000-0000-000000027001', '30000000-0000-0000-0000-000000027001',
        '03000000-0000-0000-0000-000000000005', 'Trener musiał wyjść po 20 minutach, trening się nie odbył.',
        'RESOLVED', 'UPHELD', 'ENTRY_RETURNED',
        'Trener potwierdził, że zajęcia skończyły się po 20 minutach. Wejście wraca do pakietu.',
        '03000000-0000-0000-0000-000000000010', CURRENT_TIMESTAMP - INTERVAL '8 days' + INTERVAL '3 hours',
        CURRENT_TIMESTAMP - INTERVAL '7 days'),
       ('32000000-0000-0000-0000-000000027002', '30000000-0000-0000-0000-000000027002',
        '03000000-0000-0000-0000-000000000005', 'Nie było mnie na tym treningu.',
        'RESOLVED', 'REJECTED', 'NO_CHANGE',
        'Obecność potwierdził trener, a rezerwacja nie została odwołana. Wejście pozostaje wykorzystane.',
        '03000000-0000-0000-0000-000000000010', CURRENT_TIMESTAMP - INTERVAL '6 days' + INTERVAL '2 hours',
        CURRENT_TIMESTAMP - INTERVAL '5 days')
ON CONFLICT DO NOTHING;

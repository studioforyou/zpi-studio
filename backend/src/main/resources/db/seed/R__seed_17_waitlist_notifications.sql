-- DEV/STAGE ONLY. Two live queue scenarios, a child's queue, and three inbox examples.
-- Inserts only: restarting never reopens an offer, renews a deadline or resets a cancellation.
CREATE OR REPLACE FUNCTION pg_temp.waitlist_seed_at(day_offset INT, local_time TIME)
RETURNS TIMESTAMPTZ LANGUAGE sql AS $$
    SELECT (((CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Warsaw')::DATE + day_offset) + local_time)
           AT TIME ZONE 'Europe/Warsaw'
$$;

INSERT INTO customer_pass (id, customer_id, variant_id, product_name, entries_total, entries_remaining,
                           price_paid, status, valid_from, valid_until, created_by)
SELECT ('28000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       ('07000000-0000-0000-0000-' || lpad(customer_no::TEXT, 12, '0'))::UUID,
       ('14000000-0000-0000-0000-' || lpad(variant_no::TEXT, 12, '0'))::UUID,
       name, 4, remaining, price, 'ACTIVE', CURRENT_DATE, CURRENT_DATE + 30,
       '03000000-0000-0000-0000-000000000001'
FROM (VALUES (14001, 3, 1, 'Pole dance — 4 wejścia', 3, 180.00),
             (14002, 4, 6, 'Pole dance dzieci — 4 wejścia', 4, 160.00),
             (14003, 1, 1, 'Pole dance — 4 wejścia', 2, 180.00),
             (14004, 10, 6, 'Pole dance dzieci — 4 wejścia', 3, 160.00))
     AS passes(slot, customer_no, variant_no, name, remaining, price)
ON CONFLICT DO NOTHING;

INSERT INTO schedule_event (id, kind, service_type_id, title, starts_at, ends_at, capacity, visibility, created_by)
SELECT ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, 'CLASS',
       ('10000000-0000-0000-0000-' || lpad(service_no::TEXT, 12, '0'))::UUID, title,
       pg_temp.waitlist_seed_at(day_offset, '17:00'), pg_temp.waitlist_seed_at(day_offset, '18:00'), 1, 'PUBLIC',
       '03000000-0000-0000-0000-000000000001'
FROM (VALUES (14001, 1, 'Pole dance — lista rezerwowa', 2),
             (14002, 1, 'Pole dance — zapis automatyczny', 3),
             (14003, 5, 'Pole dance dzieci — lista rezerwowa', 4),
             (14004, 1, 'Pole dance — wolne miejsce', 5),
             (14005, 1, 'Pole dance — wcześniejsza oferta', 6),
             (14006, 1, 'Pole dance — rezerwacja z listy', 7))
     AS sessions(slot, service_no, title, day_offset)
ON CONFLICT DO NOTHING;

INSERT INTO event_staff (event_id, staff_id, role)
SELECT id, '08000000-0000-0000-0000-000000000001', 'LEAD' FROM schedule_event
WHERE id BETWEEN '19000000-0000-0000-0000-000000014001' AND '19000000-0000-0000-0000-000000014006'
ON CONFLICT DO NOTHING;

INSERT INTO event_resource (event_id, resource_id, blocks_whole_room)
SELECT id, '15000000-0000-0000-0000-000000000001', FALSE FROM schedule_event
WHERE id BETWEEN '19000000-0000-0000-0000-000000014001' AND '19000000-0000-0000-0000-000000014006'
ON CONFLICT DO NOTHING;

INSERT INTO booking_policy (id, scope, event_id, waitlist_enabled, waitlist_confirm_minutes,
                            cancellation_allowed, free_cancel_hours, late_cancel_allowed, late_cancel_forfeits_entry)
SELECT ('11000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, 'EVENT',
       ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, TRUE, minutes, TRUE, 24, TRUE, TRUE
FROM (VALUES (14001, 30), (14002, 0), (14003, 30), (14004, 360), (14005, 30), (14006, 0)) AS policies(slot, minutes)
ON CONFLICT DO NOTHING;

INSERT INTO reservation (id, event_id, customer_id, booked_by, source, customer_pass_id, booking_policy_id,
                         free_cancel_until, cancellation_allowed_snapshot, late_cancel_allowed_snapshot, late_cancel_forfeits_entry_snapshot)
SELECT ('30000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, event.id,
       ('07000000-0000-0000-0000-' || lpad(customer_no::TEXT, 12, '0'))::UUID,
       '03000000-0000-0000-0000-000000000001', source,
       ('28000000-0000-0000-0000-' || lpad(pass_no::TEXT, 12, '0'))::UUID,
       ('11000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       event.starts_at - INTERVAL '24 hours', TRUE, TRUE, TRUE
FROM (VALUES (14001, 1, 14003, 'STAFF'), (14002, 1, 14003, 'STAFF'),
             (14003, 10, 14004, 'STAFF'), (14006, 3, 14001, 'WAITLIST')) AS reservations(slot, customer_no, pass_no, source)
JOIN schedule_event event ON event.id = ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID
ON CONFLICT DO NOTHING;

INSERT INTO pass_ledger (id, pass_id, kind, entries_delta, reservation_id, reason, occurred_at)
SELECT ('34000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       ('28000000-0000-0000-0000-' || lpad(pass_no::TEXT, 12, '0'))::UUID, kind, delta,
       CASE WHEN reservation_no IS NULL THEN NULL ELSE ('30000000-0000-0000-0000-' || lpad(reservation_no::TEXT, 12, '0'))::UUID END,
       reason, CURRENT_TIMESTAMP - INTERVAL '2 days'
FROM (VALUES (14001, 14001, 'ACTIVATION', 4, NULL, 'Przyznanie karnetu'),
             (14002, 14002, 'ACTIVATION', 4, NULL, 'Przyznanie karnetu'),
             (14003, 14003, 'ACTIVATION', 4, NULL, 'Przyznanie karnetu'),
             (14004, 14004, 'ACTIVATION', 4, NULL, 'Przyznanie karnetu'),
             (14011, 14003, 'ENTRY_RESERVED', -1, 14001, 'Zapis na zajęcia'),
             (14012, 14003, 'ENTRY_RESERVED', -1, 14002, 'Zapis na zajęcia'),
             (14013, 14004, 'ENTRY_RESERVED', -1, 14003, 'Zapis na zajęcia'),
             (14016, 14001, 'ENTRY_RESERVED', -1, 14006, 'Zapis z listy rezerwowej'))
     AS ledger(slot, pass_no, kind, delta, reservation_no, reason)
ON CONFLICT DO NOTHING;

INSERT INTO waitlist_entry (id, event_id, customer_id, added_by, customer_pass_id, status, reservation_id,
                            created_at, resolved_at, offer_expires_at)
SELECT ('31000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       ('19000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       ('07000000-0000-0000-0000-' || lpad(customer_no::TEXT, 12, '0'))::UUID,
       '03000000-0000-0000-0000-000000000006',
       ('28000000-0000-0000-0000-' || lpad(pass_no::TEXT, 12, '0'))::UUID, status,
       CASE WHEN slot = 14006 THEN '30000000-0000-0000-0000-000000014006'::UUID ELSE NULL END,
       CURRENT_TIMESTAMP - INTERVAL '3 hours',
       CASE WHEN status IN ('EXPIRED', 'PROMOTED') THEN CURRENT_TIMESTAMP - INTERVAL '2 hours' ELSE NULL END,
       CASE WHEN slot = 14004 THEN CURRENT_TIMESTAMP + INTERVAL '6 hours'
            WHEN slot = 14005 THEN CURRENT_TIMESTAMP - INTERVAL '1 hour' ELSE NULL END
FROM (VALUES (14001, 3, 14001, 'WAITING'), (14002, 3, 14001, 'WAITING'), (14003, 4, 14002, 'WAITING'),
             (14004, 3, 14001, 'OFFERED'), (14005, 3, 14001, 'EXPIRED'), (14006, 3, 14001, 'PROMOTED'))
     AS waiting(slot, customer_no, pass_no, status)
ON CONFLICT DO NOTHING;

-- These are historical inbox examples. Real allocations in the first three classes publish real events.
INSERT INTO notification (id, recipient_id, notification_type_id, recipient_role, title, body, payload, dedup_key, scheduled_for, created_at)
SELECT ('44000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       '03000000-0000-0000-0000-000000000006', type_id, 'CLIENT', 'Monika: ' || template.subject,
       replace(replace(replace(replace(template.body_template, '{{zajęcia}}', event.title),
         '{{dzień}}', to_char(event.starts_at AT TIME ZONE 'Europe/Warsaw', 'DD.MM.YYYY')),
         '{{godzina}}', to_char(event.starts_at AT TIME ZONE 'Europe/Warsaw', 'HH24:MI')),
         '{{potwierdź do}}', coalesce(to_char(waiting.offer_expires_at AT TIME ZONE 'Europe/Warsaw', 'DD.MM, HH24:MI'), '')),
       jsonb_strip_nulls(jsonb_build_object('eventId', event.id, 'eventName', event.title, 'eventStartsAt', event.starts_at, 'profileId', waiting.customer_id,
         'waitlistEntryId', waiting.id, 'offerExpiresAt', waiting.offer_expires_at, 'reservationId', waiting.reservation_id)),
       'SEED_WAITLIST:' || waiting.id, CURRENT_TIMESTAMP - age, CURRENT_TIMESTAMP - age
FROM (VALUES (14004, 5, INTERVAL '15 minutes'), (14005, 5, INTERVAL '2 hours'), (14006, 6, INTERVAL '1 hour')) AS inbox(slot, type_id, age)
JOIN waitlist_entry waiting ON waiting.id = ('31000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID
JOIN schedule_event event ON event.id = waiting.event_id
JOIN notification_template template ON template.notification_type_id = type_id AND template.channel = 'PUSH' AND template.locale = 'pl'
ON CONFLICT DO NOTHING;

INSERT INTO notification_delivery (id, notification_id, channel, status)
SELECT ('45000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID,
       ('44000000-0000-0000-0000-' || lpad(slot::TEXT, 12, '0'))::UUID, 'PUSH', 'NO_DEVICE'
FROM (VALUES (14004), (14005), (14006)) AS inbox(slot)
ON CONFLICT DO NOTHING;

-- DEV/STAGE ONLY. Announcement templates of the administrator Beata Lis (#160), for
-- "Komunikacja → Komunikaty → Szablony" in the panel and the "Szablon" field of a new announcement.
-- Insert-only. Table number 48 (announcement_template): IDs 48000000-0000-0000-0000-0000002600NN.

INSERT INTO announcement_template (id, name, title, body, is_marketing, created_by, created_at, updated_by, updated_at) VALUES
    ('48000000-0000-0000-0000-000000260001', 'Zamknięcie studia w święto', 'Studio zamknięte w święto',
     'W dzień świąteczny studio jest zamknięte, zajęcia się nie odbywają. Zapraszamy następnego dnia według grafiku.', FALSE,
     '03000000-0000-0000-0000-000000000010', now() - interval '30 days', '03000000-0000-0000-0000-000000000010', now() - interval '30 days'),
    ('48000000-0000-0000-0000-000000260002', 'Zmiana sali', 'Zmiana sali',
     'Dzisiejsze zajęcia odbędą się w innej sali. Godzina bez zmian.', FALSE,
     '03000000-0000-0000-0000-000000000010', now() - interval '20 days', '03000000-0000-0000-0000-000000000010', now() - interval '5 days'),
    ('48000000-0000-0000-0000-000000260003', 'Promocja na karnety', 'Promocja −20% na karnety',
     'Tylko do niedzieli wszystkie karnety 20% taniej. Kup w recepcji lub zapytaj trenera.', TRUE,
     '03000000-0000-0000-0000-000000000010', now() - interval '10 days', '03000000-0000-0000-0000-000000000010', now() - interval '10 days')
ON CONFLICT DO NOTHING;

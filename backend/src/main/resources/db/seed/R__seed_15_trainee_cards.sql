-- Seed 15: trainee cards kept by trainers (#99)
-- DEV/STAGE TEST DATA ONLY. Conventions: see R__seed_01_identity.sql.
-- Adds dedicated rows (suffixes 15001..) next to the cards and notes of seed 05. Never changes a
-- card or a note a tester has edited, archived or added.
--
-- Trainers read health notes with CUSTOMER_HEALTH_READ (given to the TRAINER role in seed 01) and
-- the participant's consent on their own assignment (staff_assignment.can_view_health, seed 02/10):
--   Marek / Tomasz     consent      card and notes: two current, one archived
--   Ewa   / Katarzyna  consent      card and notes: one current, one archived
--   Marek / Monika     no consent   card only; her own note stays hidden
--   Anna  / Piotr      no consent   card only
--   Anna  / Adam       no consent   card only; his own note stays hidden
--   Anna  / Julia      no consent   nobody has filled in the card yet

INSERT INTO trainee_card (customer_id, goals, sport_experience, updated_by, updated_at) VALUES
    ('07000000-0000-0000-0000-000000000003', 'Silniejsze mięśnie brzucha i pleców, lepsza postawa przy pracy przy biurku.',
     'Joga 1 rok, regularne spacery z kijkami.', '03000000-0000-0000-0000-000000000002', now() - interval '12 days'),
    ('07000000-0000-0000-0000-000000000005', 'Pierwsze figury na rurce i mocniejszy chwyt.',
     'Wspinaczka na ściance rekreacyjnie od 2 lat.', '03000000-0000-0000-0000-000000000001', now() - interval '9 days'),
    ('07000000-0000-0000-0000-000000000006', 'Powrót do formy po rocznej przerwie, lepsza wytrzymałość.',
     'Crossfit 3 lata, bieganie okazjonalnie.', '03000000-0000-0000-0000-000000000001', now() - interval '25 days')
ON CONFLICT DO NOTHING;

INSERT INTO health_note (id, customer_id, kind, description, reported_by, created_at, archived_at, archived_by) VALUES
    -- Tomasz: added by Marek, who sees it.
    ('35000000-0000-0000-0000-000000015001', '07000000-0000-0000-0000-000000000002', 'OTHER',
     'Alergia na lateks – używać gum oporowych bez lateksu.', '03000000-0000-0000-0000-000000000002',
     now() - interval '18 days', NULL, NULL),
    -- Katarzyna: an injury she reported herself, archived by Ewa once it healed.
    ('35000000-0000-0000-0000-000000015002', '07000000-0000-0000-0000-000000000001', 'INJURY',
     'Skręcona prawa kostka – bez obrotów na rurze do wyleczenia.', '03000000-0000-0000-0000-000000000004',
     now() - interval '40 days', now() - interval '12 days', '03000000-0000-0000-0000-000000000003'),
    -- Monika: reported by herself; Marek has no consent, so he does not see it.
    ('35000000-0000-0000-0000-000000015003', '07000000-0000-0000-0000-000000000003', 'CONTRAINDICATION',
     'Rozejście mięśni prostych brzucha – bez brzuszków i deski.', '03000000-0000-0000-0000-000000000006',
     now() - interval '30 days', NULL, NULL),
    -- Adam: reported by himself before his account was suspended; hidden from Anna, who has no consent.
    ('35000000-0000-0000-0000-000000015004', '07000000-0000-0000-0000-000000000006', 'CONDITION',
     'Nadciśnienie tętnicze, przyjmuje leki – unikać wstrzymywania oddechu przy dużym obciążeniu.',
     '03000000-0000-0000-0000-000000000009', now() - interval '60 days', NULL, NULL)
ON CONFLICT DO NOTHING;

-- Seed 05 archived Tomasz's shoulder note before notes recorded who archived them; Marek did.
-- Only the untouched original row changes.
UPDATE health_note
SET archived_by = '03000000-0000-0000-0000-000000000002'
WHERE id = '35000000-0000-0000-0000-000000000003' AND archived_at IS NOT NULL AND archived_by IS NULL;

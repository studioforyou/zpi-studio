#  Studio4You - model bazy danych

```text
Studio4You - Model bazy danych
├── 1  Artefakty
├── 2  Konwencje
├── 3  Pełna lista pól (67 tabel)
└── 4  Q&A
```

## 1 &nbsp; &nbsp; Artefakty

| Plik | Zawartość |
|---|---|
| `DATABASE_SCHEMA.md` | Ten plik: konwencje, pełna lista pól każdej tabeli i Q&A. |
| `DATABASE_SCHEMA.drawio` | Diagram wszystkich tabel i relacji do otwarcia w draw.io. |
| `backend/src/main/resources/db/migration/V2__domain_model.sql` | Migracja Flyway, która tworzy ten schemat w bazie. |

## 2 &nbsp; &nbsp; Konwencje

| Obszar | Konwencja |
|---|---|
| Klucze | `UUID` z `gen_random_uuid()` <br> Wyjątkiem jest `audit_log`, gdzie używany jest `BIGINT IDENTITY`, bo to dopisywany dziennik. |
| Czas | `TIMESTAMPTZ` w UTC dla zdarzeń. <br> Reguły cykliczne i dostępność trzymają czas lokalny (`TIME` + dzień tygodnia). |
| Kwoty | `NUMERIC(10,2)` + `currency CHAR(3)`, domyślnie PLN. |
| Historia zamiast usuwania | Statusy, `ended_at`, `archived_at`, `revoked_at`. Kasowanie danych osobowych (RODO) to osobny proces anonimizacji, do ustalenia. |
| Snapshoty | Zakup zapisuje nazwę i cenę produktu (`order_item`, `customer_pass`), rezerwacja zapisuje zastosowaną politykę i termin bezpłatnego odwołania. Zmiana cennika lub zasad nie wpływa na przeszłe rekordy. |
| Współbieżność | Kolumna `version` (optimistic locking) na `schedule_event`, `reservation`, `customer_pass`; unikalne indeksy częściowe pilnują „jednej aktywnej rezerwacji” i „jednej aktywnej pozycji na liście rezerwowej”. |
| Idempotencja | `payment_webhook_event (provider, provider_event_id)` UNIQUE, `pass_ledger (pass_id, reservation_id, kind)` UNIQUE, `notification.dedup_key` UNIQUE. |
| Moduły | Każda tabela ma jednego właściciela-moduł, podanego w nawiasie kwadratowym w komentarzu tabeli i w sekcji 3. |

## 3 &nbsp; &nbsp; Pełna lista pól

### `studio`

*Moduł:* `configuration`. Jeden wiersz: dane studia, strefa czasowa i globalne ustawienia o stałym typie. Nie oznacza multi-tenancy.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| name | varchar(120) |  |  |  |  |
| time_zone | varchar(64) |  |  | 'Europe/Warsaw' |  |
| currency | char(3) |  |  | 'PLN' |  |
| group_auto_book_weeks | smallint |  |  | 4 | Horyzont autozapisów stałej grupy w tygodniach; grupa może nadpisać. |
| pt_dispute_window_hours | smallint |  |  | 24 | Czas klienta na zgłoszenie niezgodności po potwierdzeniu treningu personalnego przez trenera. |
| substitution_needs_approval | boolean |  |  | true |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |

### `stored_file`

*Moduł:* `files`. Metadane pliku w Cloud Storage; treść poza bazą. Używane przez załączniki czatu, dokumenty, zdjęcia.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| owner_module | varchar(40) |  |  |  |  |
| uploaded_by | uuid | tak | FK → account |  |  |
| bucket | varchar(100) |  |  |  |  |
| object_key | varchar(500) |  | UNIQUE |  |  |
| original_name | varchar(255) |  |  |  |  |
| mime_type | varchar(100) |  |  |  |  |
| size_bytes | bigint |  |  |  |  |
| sha256 | char(64) | tak |  |  |  |
| sensitivity | varchar(20) |  |  | 'NORMAL' | HEALTH = osobny bucket/prefiks i ostrzejsza kontrola dostępu. |
| created_at | timestamptz |  |  | now() |  |
| deleted_at | timestamptz | tak |  |  |  |

### `account`

*Moduł:* `identity`. Konto logowania, bez danych biznesowych.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| email | varchar(254) |  |  |  |  |
| status | varchar(20) |  |  | 'PENDING_APPROVAL' | INVITED: konto założone przez studio, czeka na pierwsze logowanie; PENDING_APPROVAL: samodzielna rejestracja czeka na zatwierdzenie. |
| created_by | uuid | tak | FK → account |  | NULL przy samodzielnej rejestracji; inaczej pracownik, który założył konto (audyt aktywacji). |
| activated_at | timestamptz | tak |  |  |  |
| last_login_at | timestamptz | tak |  |  |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |

Ograniczenia: `UNIQUE (lower((email)))`

### `identity_link`

*Moduł:* `identity`. Powiązanie konta z dostawcą tożsamości (Firebase UID). Używane, jeśli jako dostawcę logowania wybierzemy Firebase; hasło nie jest wtedy przechowywane w bazie.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| account_id | uuid |  | FK → account |  |  |
| provider | varchar(20) |  |  |  |  |
| external_uid | varchar(128) |  |  |  |  |
| created_at | timestamptz |  |  | now() |  |

Ograniczenia: UNIQUE (provider, external_uid); UNIQUE (account_id, provider)

### `local_credential`

*Moduł:* `identity`. TYLKO jeśli aplikacja użyje własnego logowania Spring Security zamiast Firebase. Przy Firebase tabela znika (reset hasła robi Firebase).

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| account_id | uuid |  | PK, FK → account |  |  |
| password_hash | varchar(255) |  |  |  |  |
| password_changed_at | timestamptz |  |  | now() |  |

### `account_invitation`

*Moduł:* `identity`. Zaproszenie e-mail od studia, po którym klient lub trener zakłada dostęp.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| account_id | uuid |  | FK → account |  |  |
| token_hash | char(64) |  | UNIQUE |  | Hash (SHA-256) tokenu z linku; sam token nie jest przechowywany. |
| sent_by | uuid |  | FK → account |  |  |
| sent_at | timestamptz |  |  | now() |  |
| expires_at | timestamptz |  |  |  |  |
| used_at | timestamptz | tak |  |  |  |
| revoked_at | timestamptz | tak |  |  |  |

### `role`

*Moduł:* `identity`. Rola biznesowa z domyślnym zestawem uprawnień. Słownik ładowany migracją.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | smallint |  | PK |  |  |
| code | varchar(30) |  | UNIQUE |  |  |
| display_name | varchar(60) |  |  |  |  |

### `permission`

*Moduł:* `identity`. Kontrolowane uprawnienie opisane biznesowo, np. SCHEDULE_MANAGE_ALL, SCHEDULE_EDIT_OWN, AVAILABILITY_EDIT_OWN, CUSTOMER_HEALTH_READ, PASS_ADJUST, ATTENDANCE_CORRECT.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | smallint |  | PK |  |  |
| code | varchar(60) |  | UNIQUE |  |  |
| category | varchar(30) |  |  |  |  |
| description | varchar(255) |  |  |  |  |

### `role_permission`

*Moduł:* `identity`. Domyślne uprawnienia roli.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| role_id | smallint |  | PK, FK → role |  |  |
| permission_id | smallint |  | PK, FK → permission |  |  |

Ograniczenia: PK (role_id, permission_id)

### `account_role`

*Moduł:* `identity`. Role konta (wiele ról na jednym loginie). Historia nadań/odebrań w audit_log.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| account_id | uuid |  | PK, FK → account |  |  |
| role_id | smallint |  | PK, FK → role |  |  |
| granted_by | uuid | tak | FK → account |  |  |
| granted_at | timestamptz |  |  | now() |  |

Ograniczenia: PK (account_id, role_id)

### `account_permission_override`

*Moduł:* `identity`. Dodatkowe lub odebrane uprawnienie konkretnego pracownika ponad domyślne rolę (np. jeden trener edytuje tylko swoje zajęcia, inny cały grafik).

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| account_id | uuid |  | PK, FK → account |  |  |
| permission_id | smallint |  | PK, FK → permission |  |  |
| effect | varchar(6) |  |  |  |  |
| granted_by | uuid |  | FK → account |  |  |
| granted_at | timestamptz |  |  | now() |  |

Ograniczenia: PK (account_id, permission_id)

### `account_status_change`

*Moduł:* `identity`. Historia statusu konta: każda zmiana statusu (zatwierdzenie, odrzucenie, zawieszenie, przywrócenie) z autorem i powodem.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| account_id | uuid |  | FK → account |  |  |
| from_status | varchar(20) | tak |  |  |  |
| to_status | varchar(20) |  |  |  |  |
| changed_by | uuid | tak | FK → account |  |  |
| reason | text | tak |  |  |  |
| changed_at | timestamptz |  |  | now() |  |

### `audit_log`

*Moduł:* `audit`. Wspólny dziennik zmian "kto, co, kiedy, dlaczego". Tylko INSERT.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | bigint |  | PK | identity |  |
| occurred_at | timestamptz |  |  | now() |  |
| actor_account_id | uuid | tak | FK → account |  |  |
| module | varchar(40) |  |  |  |  |
| entity_type | varchar(60) |  |  |  |  |
| entity_id | uuid |  |  |  |  |
| action | varchar(60) |  |  |  |  |
| changes | jsonb | tak |  |  | Pola przed/po, np. {"starts_at":{"old":"...","new":"..."}}. Bez danych zdrowotnych w treści. |
| reason | text | tak |  |  |  |
| correlation_id | uuid | tak |  |  |  |

### `customer_profile`

*Moduł:* `customer`. Uczestnik usług: dorosły klient albo dziecko (dziecko nie ma konta, opiekuna wskazuje guardianship). Każda rezerwacja, karnet, zgoda i obecność wskazuje ten profil.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| account_id | uuid | tak | FK → account, UNIQUE |  | Dorosły: własne konto (1:1). Dziecko: NULL, zarządza nim opiekun przez guardianship; po pełnoletności można podpiąć konto bez utraty historii. |
| kind | varchar(10) |  |  | 'ADULT' |  |
| first_name | varchar(80) |  |  |  |  |
| last_name | varchar(80) |  |  |  |  |
| birth_date | date | tak |  |  | Potrzebne do grup wiekowych i zgód rodzica dla niepełnoletnich. |
| phone | varchar(20) | tak |  |  |  |
| emergency_contact_name | varchar(120) | tak |  |  |  |
| emergency_phone | varchar(20) | tak |  |  |  |
| status | varchar(10) |  |  | 'ACTIVE' |  |
| created_by | uuid | tak | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |

### `guardianship`

*Moduł:* `customer`. Rodzic/opiekun zarządza profilem dziecka; jedno konto, wiele dzieci, dziecko może mieć dwoje opiekunów.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| guardian_id | uuid |  | PK, FK → customer_profile |  |  |
| child_id | uuid |  | PK, FK → customer_profile |  |  |
| relation | varchar(20) |  |  | 'PARENT' |  |
| is_payer | boolean |  |  | true |  |
| created_at | timestamptz |  |  | now() |  |
| ended_at | timestamptz | tak |  |  |  |

Ograniczenia: PK (guardian_id, child_id)

### `staff_profile`

*Moduł:* `staff`. Profil pracownika (trener, instruktor, recepcja, dietetyk, admin).

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| account_id | uuid |  | FK → account, UNIQUE |  |  |
| first_name | varchar(80) |  |  |  |  |
| last_name | varchar(80) |  |  |  |  |
| phone | varchar(20) | tak |  |  |  |
| bio | text | tak |  |  | Opis widoczny dla klientów. Notatki wewnętrzne o klientach są w trainer_note. |
| photo_file_id | uuid | tak | FK → stored_file |  |  |
| calendar_color | char(7) | tak |  |  |  |
| status | varchar(10) |  |  | 'ACTIVE' |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |

### `staff_assignment`

*Moduł:* `customer`. Relacja klient - trener/dietetyk, wiele do wielu, z historią. Podstawa dostępu trenera do danych klienta.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| customer_id | uuid |  | FK → customer_profile |  |  |
| staff_id | uuid |  | FK → staff_profile |  |  |
| scope | varchar(20) |  |  | 'PERSONAL_TRAINING' |  |
| can_view_health | boolean |  |  | false | Dostęp do danych zdrowotnych wynika z relacji + zgody, nie z samej roli. |
| assigned_by | uuid |  | FK → account |  |  |
| started_at | timestamptz |  |  | now() |  |
| ended_at | timestamptz | tak |  |  |  |
| end_reason | varchar(255) | tak |  |  |  |

Ograniczenia: `UNIQUE (customer_id, staff_id, scope) WHERE (ended_at IS NULL)`

### `customer_interest`

*Moduł:* `customer`. Zajęcia, którymi klient jest zainteresowany, do powiadomień o wolnych miejscach.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| customer_id | uuid |  | PK, FK → customer_profile |  |  |
| service_type_id | uuid |  | PK, FK → service_type |  |  |
| created_at | timestamptz |  |  | now() |  |

Ograniczenia: PK (customer_id, service_type_id)

### `service_type`

*Moduł:* `offering`. Typ usługi z regułami: "Kalistenika - średniozaawansowana", "Trening personalny".

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| name | varchar(120) |  |  |  |  |
| description | text | tak |  |  |  |
| category | varchar(30) |  |  |  |  |
| level | varchar(20) | tak |  |  | Filtr poziomu. Jeśli studio chce własne nazwy poziomów, zamienić na słownik. |
| min_age | smallint | tak |  |  |  |
| max_age | smallint | tak |  |  |  |
| default_duration_minutes | smallint |  |  |  |  |
| min_participants | smallint | tak |  |  |  |
| max_participants | smallint | tak |  |  |  |
| calendar_color | char(7) | tak |  |  |  |
| is_active | boolean |  |  | true |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |

### `staff_qualification`

*Moduł:* `staff`. Jakie usługi może prowadzić pracownik; wybór "uprawnionych instruktorów" do zastępstw.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| staff_id | uuid |  | PK, FK → staff_profile |  |  |
| service_type_id | uuid |  | PK, FK → service_type |  |  |

Ograniczenia: PK (staff_id, service_type_id)

### `booking_policy`

*Moduł:* `configuration`. Zasady zapisu, anulowania i autoodwołania z zakresem STUDIO > typ usługi > grupa > wydarzenie; NULL w polu = dziedzicz z wyższego poziomu. Wersjonowane przez valid_from/valid_to.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| scope | varchar(20) |  |  |  |  |
| service_type_id | uuid | tak | FK → service_type |  |  |
| training_group_id | uuid | tak | FK → training_group |  |  |
| event_id | uuid | tak | FK → schedule_event |  |  |
| free_cancel_hours | smallint | tak |  |  |  |
| late_cancel_forfeits_entry | boolean | tak |  |  |  |
| booking_opens_days_before | smallint | tak |  |  |  |
| booking_closes_minutes_before | smallint | tak |  |  |  |
| waitlist_enabled | boolean | tak |  |  |  |
| waitlist_confirm_minutes | smallint | tak |  |  | NULL = automatyczny awans z listy rezerwowej; wartość = czas na potwierdzenie (na przyszłość). |
| auto_cancel_enabled | boolean | tak |  |  | FALSE na poziomie grupy/wydarzenia = wyłączenie reguły. |
| auto_cancel_min_participants | smallint | tak |  |  |  |
| auto_cancel_hours_before | smallint | tak |  |  |  |
| valid_from | timestamptz |  |  | now() |  |
| valid_to | timestamptz | tak |  |  |  |
| created_by | uuid | tak | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |

### `makeup_policy`

*Moduł:* `membership`. Zasady przyznawania i ważności odrobień, przypinane do produktu.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| name | varchar(120) |  |  |  |  |
| validity_days | smallint |  |  |  |  |
| max_per_pass | smallint | tak |  |  |  |
| grant_on_in_time_cancel | boolean |  |  | true |  |
| grant_on_studio_cancel | boolean |  |  | true |  |
| expiry_checked_against | varchar(10) |  |  | 'EVENT_DATE' | Przypadek brzegowy: czy ważność liczy się do dnia zapisu, czy dnia zajęć. |
| is_active | boolean |  |  | true |  |
| created_at | timestamptz |  |  | now() |  |

### `pass_product`

*Moduł:* `offering`. Produkt oferty: karnet na wejścia, wejście jednorazowe, pakiet treningów personalnych, karnet czasowy, voucher.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| name | varchar(120) |  |  |  |  |
| description | text | tak |  |  |  |
| kind | varchar(20) |  |  |  |  |
| audience | varchar(10) |  |  | 'ANY' |  |
| makeup_policy_id | uuid | tak | FK → makeup_policy |  |  |
| suspension_allowed | boolean |  |  | false |  |
| max_suspension_days | smallint | tak |  |  |  |
| is_active | boolean |  |  | true |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |

### `pass_product_variant`

*Moduł:* `offering`. Wariant cenowy: liczba wejść x ważność x cena. Siatka wariantów obsługuje "suwaki" bez wymyślania formuły. Zmiana ceny = nowy wariant.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| product_id | uuid |  | FK → pass_product |  |  |
| entries_count | smallint | tak |  |  | NULL = bez limitu wejść (karnet czasowy). |
| validity_days | smallint |  |  |  |  |
| price | numeric(10,2) |  |  |  |  |
| currency | char(3) |  |  | 'PLN' |  |
| is_active | boolean |  |  | true |  |
| valid_from | timestamptz |  |  | now() |  |
| valid_to | timestamptz | tak |  |  |  |
| created_at | timestamptz |  |  | now() |  |

Ograniczenia: `UNIQUE (product_id, entries_count, validity_days) WHERE is_active`

### `pass_product_service`

*Moduł:* `offering`. Które usługi finansuje produkt, np. karnet tylko na workout albo tylko pole dance.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| product_id | uuid |  | PK, FK → pass_product |  |  |
| service_type_id | uuid |  | PK, FK → service_type |  |  |

Ograniczenia: PK (product_id, service_type_id)

### `resource`

*Moduł:* `scheduling`. Sala lub inny zasób (sprzęt). Sala pole dance i dwie sale workout to trzy wiersze.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| name | varchar(80) |  | UNIQUE |  |  |
| kind | varchar(20) |  |  | 'ROOM' |  |
| parent_resource_id | uuid | tak | FK → resource |  |  |
| capacity_units | smallint |  |  | 1 | Ile równoległych "stanowisk" mieści zasób, np. 3 treningi personalne naraz na sali workout. Konfigurowalne, nie wpisane w kod. |
| max_people | smallint | tak |  |  | Maksymalna liczba osób na zajęciach grupowych w tej sali. |
| description | text | tak |  |  |  |
| is_active | boolean |  |  | true |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |

### `service_type_resource`

*Moduł:* `scheduling`. Zgodność usługi z salą i zużycie pojemności: grupa zajmuje całą salę (units_required NULL), trening personalny 1 stanowisko, pole dance tylko w sali z rurami.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| service_type_id | uuid |  | PK, FK → service_type |  |  |
| resource_id | uuid |  | PK, FK → resource |  |  |
| units_required | smallint | tak |  |  |  |
| is_default | boolean |  |  | false |  |

Ograniczenia: PK (service_type_id, resource_id)

### `training_group`

*Moduł:* `group_training`. Stała grupa, np. "Pole dance początkujący wt/czw 18:00".

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| name | varchar(120) |  |  |  |  |
| service_type_id | uuid |  | FK → service_type |  |  |
| auto_book_weeks | smallint | tak |  |  | NULL = wartość ze studio.group_auto_book_weeks. |
| is_active | boolean |  |  | true |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |

### `group_membership`

*Moduł:* `group_training`. Przynależność klienta do stałej grupy z historią.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| group_id | uuid |  | FK → training_group |  |  |
| customer_id | uuid |  | FK → customer_profile |  |  |
| auto_book | boolean |  |  | true |  |
| joined_on | date |  |  | CURRENT_DATE |  |
| left_on | date | tak |  |  |  |
| added_by | uuid | tak | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |

Ograniczenia: `UNIQUE (group_id, customer_id) WHERE (left_on IS NULL)`

### `recurrence_series`

*Moduł:* `scheduling`. Reguła cyklu generująca wystąpienia. Czas lokalny studia ("wtorek 18:00"), nie UTC.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| service_type_id | uuid |  | FK → service_type |  |  |
| training_group_id | uuid | tak | FK → training_group |  |  |
| title | varchar(120) | tak |  |  |  |
| weekday | smallint |  |  |  | ISO: 1 = poniedziałek. Kilka dni tygodnia = kilka serii (prościej niż RRULE; do decyzji). |
| local_start_time | time without time zone |  |  |  |  |
| duration_minutes | smallint |  |  |  |  |
| interval_weeks | smallint |  |  | 1 |  |
| starts_on | date |  |  |  |  |
| ends_on | date | tak |  |  |  |
| default_resource_id | uuid | tak | FK → resource |  |  |
| default_staff_id | uuid | tak | FK → staff_profile |  |  |
| capacity | smallint | tak |  |  |  |
| status | varchar(10) |  |  | 'ACTIVE' |  |
| created_by | uuid | tak | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |

### `schedule_event`

*Moduł:* `scheduling`. Jedno wydarzenie w kalendarzu: zajęcia, trening personalny, warsztat, konsultacja albo blokada sali/czasu. Jeden model czasu dla wszystkiego.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| kind | varchar(20) |  |  |  |  |
| service_type_id | uuid | tak | FK → service_type |  |  |
| series_id | uuid | tak | FK → recurrence_series |  |  |
| series_original_start | timestamptz | tak |  |  | Pierwotny termin wystąpienia w cyklu; klucz wystąpienia, nawet gdy termin przesunięto jednorazowo. |
| is_series_exception | boolean |  |  | false |  |
| training_group_id | uuid | tak | FK → training_group |  |  |
| title | varchar(120) | tak |  |  |  |
| starts_at | timestamptz |  |  |  |  |
| ends_at | timestamptz |  |  |  |  |
| capacity | smallint | tak |  |  |  |
| visibility | varchar(10) |  |  | 'PUBLIC' |  |
| status | varchar(12) |  |  | 'SCHEDULED' |  |
| force_run | boolean |  |  | false | Admin wymusza odbycie mimo braku minimum. |
| cancel_reason | varchar(20) | tak |  |  |  |
| cancel_note | text | tak |  |  |  |
| cancelled_by | uuid | tak | FK → account |  |  |
| cancelled_at | timestamptz | tak |  |  |  |
| block_reason | varchar(20) | tak |  |  |  |
| guest_name | varchar(120) | tak |  |  | Konsultacja bez konta klienta. |
| guest_phone | varchar(20) | tak |  |  |  |
| staff_note | text | tak |  |  | Notatka prowadzącego po zajęciach. |
| created_by | uuid | tak | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |
| version | integer |  |  | 0 | Optimistic locking: równoległa edycja wydarzenia i zapis klienta. |

Ograniczenia: `UNIQUE (series_id, series_original_start) WHERE (series_id IS NOT NULL)`

### `event_staff`

*Moduł:* `scheduling`. Prowadzący wydarzenia. Zmiana prowadzącego = zmiana wiersza + wpis w audit_log.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| event_id | uuid |  | PK, FK → schedule_event |  |  |
| staff_id | uuid |  | PK, FK → staff_profile |  |  |
| role | varchar(10) |  |  | 'LEAD' |  |
| assigned_at | timestamptz |  |  | now() |  |

Ograniczenia: PK (event_id, staff_id)

### `event_resource`

*Moduł:* `scheduling`. Zajęcie zasobu przez wydarzenie. units_used NULL = cała sala. Suma jednostek nakładających się wydarzeń <= resource.capacity_units, pilnowane w transakcji z blokadą.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| event_id | uuid |  | PK, FK → schedule_event |  |  |
| resource_id | uuid |  | PK, FK → resource |  |  |
| units_used | smallint | tak |  |  |  |

Ograniczenia: PK (event_id, resource_id)

### `trainer_availability`

*Moduł:* `staff`. Regularna dostępność trenera; jednorazowa dodatkowa dostępność to wiersz z valid_from = valid_to.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| staff_id | uuid |  | FK → staff_profile |  |  |
| weekday | smallint |  |  |  |  |
| local_start_time | time without time zone |  |  |  |  |
| local_end_time | time without time zone |  |  |  |  |
| valid_from | date |  |  | CURRENT_DATE |  |
| valid_to | date | tak |  |  |  |
| created_by | uuid | tak | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |

### `staff_time_off`

*Moduł:* `staff`. Urlop lub niedostępność z decyzją administratora.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| staff_id | uuid |  | FK → staff_profile |  |  |
| kind | varchar(15) |  |  |  |  |
| starts_at | timestamptz |  |  |  |  |
| ends_at | timestamptz |  |  |  |  |
| status | varchar(10) |  |  | 'REQUESTED' |  |
| reason | text | tak |  |  |  |
| requested_at | timestamptz |  |  | now() |  |
| decided_by | uuid | tak | FK → account |  |  |
| decided_at | timestamptz | tak |  |  |  |
| decision_note | text | tak |  |  |  |

### `substitution_request`

*Moduł:* `staff`. Zgłoszenie potrzeby zastępstwa, przyjęcie przez innego trenera i decyzja admina (jeśli studio.substitution_needs_approval).

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| event_id | uuid |  | FK → schedule_event |  |  |
| original_staff_id | uuid |  | FK → staff_profile |  |  |
| requested_by | uuid |  | FK → account |  |  |
| reason | text | tak |  |  |  |
| status | varchar(12) |  |  | 'OPEN' |  |
| substitute_staff_id | uuid | tak | FK → staff_profile |  |  |
| claimed_at | timestamptz | tak |  |  |  |
| decided_by | uuid | tak | FK → account |  |  |
| decided_at | timestamptz | tak |  |  |  |
| created_at | timestamptz |  |  | now() |  |

Ograniczenia: `UNIQUE (event_id) WHERE ((status)= ANY ((ARRAY['OPEN', 'CLAIMED'])))`

### `staff_rate`

*Moduł:* `staff`. DO USTALENIA. Szkic stawki zależnej od trenera, usługi i okresu; godziny wyliczane z event_staff + zakończonych wydarzeń.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| staff_id | uuid | tak | FK → staff_profile |  |  |
| service_type_id | uuid | tak | FK → service_type |  |  |
| rate_kind | varchar(15) |  |  |  |  |
| amount | numeric(10,2) |  |  |  |  |
| currency | char(3) |  |  | 'PLN' |  |
| valid_from | date |  |  |  |  |
| valid_to | date | tak |  |  |  |

### `customer_order`

*Moduł:* `payments`. Zamówienie: kto kupuje i za ile.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| buyer_id | uuid |  | FK → customer_profile |  |  |
| status | varchar(20) |  |  | 'CREATED' |  |
| total_amount | numeric(10,2) |  |  |  |  |
| currency | char(3) |  |  | 'PLN' |  |
| created_by | uuid | tak | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |
| paid_at | timestamptz | tak |  |  |  |

### `order_item`

*Moduł:* `payments`. Pozycja zamówienia ze snapshotem nazwy i ceny. beneficiary = uczestnik (np. dziecko), może różnić się od nabywcy.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| order_id | uuid |  | FK → customer_order |  |  |
| variant_id | uuid |  | FK → pass_product_variant |  |  |
| beneficiary_id | uuid |  | FK → customer_profile |  |  |
| product_name | varchar(120) |  |  |  |  |
| unit_price | numeric(10,2) |  |  |  |  |
| quantity | smallint |  |  | 1 |  |

### `payment`

*Moduł:* `payments`. Próba płatności: Przelewy24 albo ręcznie oznaczona gotówka/przelew.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| order_id | uuid |  | FK → customer_order |  |  |
| provider | varchar(15) |  |  |  |  |
| method | varchar(15) |  |  |  |  |
| status | varchar(12) |  |  | 'INITIATED' |  |
| amount | numeric(10,2) |  |  |  |  |
| currency | char(3) |  |  | 'PLN' |  |
| provider_session_id | varchar(100) | tak | UNIQUE |  |  |
| provider_transaction_id | varchar(100) | tak | UNIQUE |  |  |
| registered_by | uuid | tak | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |
| confirmed_at | timestamptz | tak |  |  |  |

### `payment_webhook_event`

*Moduł:* `payments`. Idempotencja callbacków: powtórzony webhook nie aktywuje pakietu drugi raz.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| provider | varchar(15) |  |  |  |  |
| provider_event_id | varchar(150) |  |  |  |  |
| payload | jsonb |  |  |  |  |
| received_at | timestamptz |  |  | now() |  |
| processed_at | timestamptz | tak |  |  |  |
| result | varchar(20) | tak |  |  |  |

Ograniczenia: UNIQUE (provider, provider_event_id)

### `customer_pass`

*Moduł:* `membership`. Nabyty karnet/pakiet konkretnego uczestnika ze snapshotem warunków z chwili zakupu.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| customer_id | uuid |  | FK → customer_profile |  |  |
| variant_id | uuid |  | FK → pass_product_variant |  |  |
| order_item_id | uuid | tak | FK → order_item, UNIQUE |  |  |
| product_name | varchar(120) |  |  |  |  |
| entries_total | smallint | tak |  |  |  |
| entries_remaining | smallint | tak |  |  | Saldo utrzymywane w tej samej transakcji co wpis w pass_ledger; źródłem prawdy jest ledger. |
| price_paid | numeric(10,2) |  |  |  |  |
| status | varchar(16) |  |  | 'PENDING_PAYMENT' | SCHEDULED = kupiony przed końcem poprzedniego, startuje później. |
| valid_from | date | tak |  |  |  |
| valid_until | date | tak |  |  |  |
| created_by | uuid | tak | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |
| version | integer |  |  | 0 |  |

### `pass_suspension`

*Moduł:* `membership`. Zawieszenie karnetu, jeśli produkt na to pozwala; przesuwa valid_until.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| pass_id | uuid |  | FK → customer_pass |  |  |
| starts_on | date |  |  |  |  |
| ends_on | date |  |  |  |  |
| reason | text | tak |  |  |  |
| created_by | uuid |  | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |
| cancelled_at | timestamptz | tak |  |  |  |

### `reservation`

*Moduł:* `booking`. Miejsce uczestnika na wydarzeniu, z anulowaniem, obecnością i potwierdzeniem treningu personalnego.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| event_id | uuid |  | FK → schedule_event |  |  |
| customer_id | uuid |  | FK → customer_profile |  |  |
| booked_by | uuid |  | FK → account |  |  |
| source | varchar(20) |  |  | 'SELF' |  |
| status | varchar(12) |  |  | 'ACTIVE' |  |
| customer_pass_id | uuid | tak | FK → customer_pass |  |  |
| makeup_credit_id | uuid | tak | FK → makeup_credit |  |  |
| booking_policy_id | uuid | tak | FK → booking_policy |  |  |
| free_cancel_until | timestamptz | tak |  |  | Snapshot terminu bezpłatnego odwołania z chwili zapisu; późniejsza zmiana polityki go nie zmienia. |
| cancelled_at | timestamptz | tak |  |  |  |
| cancelled_by | uuid | tak | FK → account |  |  |
| cancel_kind | varchar(16) | tak |  |  |  |
| attendance | varchar(10) |  |  | 'UNMARKED' | Osobno od statusu: "nie pojawił/a się" (ABSENT) to nie to samo co odwołanie. |
| attendance_marked_by | uuid | tak | FK → account |  |  |
| attendance_marked_at | timestamptz | tak |  |  |  |
| trainer_confirmed_at | timestamptz | tak |  |  |  |
| client_confirmed_at | timestamptz | tak |  |  |  |
| settlement_status | varchar(10) |  |  | 'OPEN' | Trening personalny: OPEN do upływu okna sporu, DISPUTED gdy klient zgłosił niezgodność, FINAL po rozliczeniu. |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |
| version | integer |  |  | 0 |  |

Ograniczenia: `UNIQUE (event_id, customer_id) WHERE ((status)= 'ACTIVE')`

### `waitlist_entry`

*Moduł:* `booking`. Kolejka rezerwowa; kolejność wg created_at, id (deterministyczny awans). OFFERED/offer_expires_at na przyszłe potwierdzanie miejsca.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| event_id | uuid |  | FK → schedule_event |  |  |
| customer_id | uuid |  | FK → customer_profile |  |  |
| added_by | uuid |  | FK → account |  |  |
| status | varchar(10) |  |  | 'WAITING' |  |
| offer_expires_at | timestamptz | tak |  |  |  |
| reservation_id | uuid | tak | FK → reservation |  |  |
| created_at | timestamptz |  |  | now() |  |
| resolved_at | timestamptz | tak |  |  |  |

Ograniczenia: `UNIQUE (event_id, customer_id) WHERE ((status)= ANY ((ARRAY['WAITING', 'OFFERED'])))`

### `training_dispute`

*Moduł:* `booking`. Zgłoszona przez klienta niezgodność co do odbycia treningu personalnego i jej rozstrzygnięcie przez admina.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| reservation_id | uuid |  | FK → reservation |  |  |
| raised_by | uuid |  | FK → account |  |  |
| reason | text |  |  |  |  |
| status | varchar(10) |  |  | 'OPEN' |  |
| resolution | varchar(15) | tak |  |  |  |
| resolution_note | text | tak |  |  |  |
| resolved_by | uuid | tak | FK → account |  |  |
| raised_at | timestamptz |  |  | now() |  |
| resolved_at | timestamptz | tak |  |  |  |

Ograniczenia: `UNIQUE (reservation_id) WHERE ((status)= 'OPEN')`

### `makeup_credit`

*Moduł:* `membership`. Pojedyncze odrobienie: skąd powstało, do kiedy ważne, kiedy zużyte.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| customer_id | uuid |  | FK → customer_profile |  |  |
| source_pass_id | uuid |  | FK → customer_pass |  |  |
| source_reservation_id | uuid | tak | FK → reservation, UNIQUE |  |  |
| granted_by | uuid | tak | FK → account |  |  |
| granted_at | timestamptz |  |  | now() |  |
| expires_at | timestamptz |  |  |  |  |
| status | varchar(10) |  |  | 'AVAILABLE' |  |
| used_reservation_id | uuid | tak | FK → reservation, UNIQUE |  |  |
| used_at | timestamptz | tak |  |  |  |

### `pass_ledger`

*Moduł:* `membership`. Księga zdarzeń karnetu: aktywacja, rezerwacja/zużycie/zwrot wejścia, korekty, przedłużenia. Tylko INSERT. Ta sama rezerwacja nie zużyje wejścia dwa razy (unikalny indeks).

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| pass_id | uuid |  | FK → customer_pass |  |  |
| kind | varchar(20) |  |  |  |  |
| entries_delta | smallint |  |  | 0 |  |
| valid_until_before | date | tak |  |  |  |
| valid_until_after | date | tak |  |  |  |
| reservation_id | uuid | tak | FK → reservation |  |  |
| reason | text | tak |  |  |  |
| actor_account_id | uuid | tak | FK → account |  |  |
| occurred_at | timestamptz |  |  | now() |  |

Ograniczenia: `UNIQUE (pass_id, reservation_id, kind) WHERE (reservation_id IS NOT NULL)`

### `trainee_card`

*Moduł:* `personal_training`. Karta podopiecznego: cele i doświadczenie.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| customer_id | uuid |  | PK, FK → customer_profile |  |  |
| goals | text | tak |  |  |  |
| sport_experience | text | tak |  |  |  |
| updated_by | uuid | tak | FK → account |  |  |
| updated_at | timestamptz |  |  | now() |  |

### `health_note`

*Moduł:* `personal_training`. Ograniczenia zdrowotne i urazy. Dane wrażliwe: widoczne tylko dla trenera z staff_assignment.can_view_health.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| customer_id | uuid |  | FK → customer_profile |  |  |
| kind | varchar(20) |  |  |  |  |
| description | text |  |  |  |  |
| reported_by | uuid |  | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |
| archived_at | timestamptz | tak |  |  |  |

### `trainer_note`

*Moduł:* `personal_training`. Notatki trenera o podopiecznym.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| customer_id | uuid |  | FK → customer_profile |  |  |
| author_staff_id | uuid |  | FK → staff_profile |  |  |
| body | text |  |  |  |  |
| created_at | timestamptz |  |  | now() |  |
| updated_at | timestamptz |  |  | now() |  |

### `document`

*Moduł:* `documents`. Definicja dokumentu lub zgody: regulamin, RODO, zgoda na udział, zgoda rodzica, ankieta zdrowotna, zgody marketingowe e-mail/SMS osobno.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| code | varchar(40) |  | UNIQUE |  |  |
| title | varchar(160) |  |  |  |  |
| kind | varchar(25) |  |  |  |  |
| audience | varchar(10) |  |  | 'ALL' |  |
| is_required | boolean |  |  | false |  |
| blocks_booking | boolean |  |  | false |  |
| is_active | boolean |  |  | true |  |
| created_at | timestamptz |  |  | now() |  |

### `document_version`

*Moduł:* `documents`. Wersja treści; zaakceptowanej wersji nie wolno usunąć.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| document_id | uuid |  | FK → document |  |  |
| version_no | smallint |  |  |  |  |
| content | text | tak |  |  |  |
| file_id | uuid | tak | FK → stored_file |  |  |
| requires_reacceptance | boolean |  |  | true |  |
| published_at | timestamptz | tak |  |  |  |
| published_by | uuid | tak | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |

Ograniczenia: UNIQUE (document_id, version_no)

### `document_service_requirement`

*Moduł:* `documents`. Dokument wymagany tylko dla wybranych usług (np. zgoda na pole dance). Dokument is_required bez wierszy tutaj = wymagany globalnie.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| document_id | uuid |  | PK, FK → document |  |  |
| service_type_id | uuid |  | PK, FK → service_type |  |  |

Ograniczenia: PK (document_id, service_type_id)

### `document_acceptance`

*Moduł:* `documents`. Akceptacja konkretnej wersji przez konkretnego uczestnika; accepted_by = rodzic dla dziecka. Wycofanie zgody = revoked_at.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| document_version_id | uuid |  | FK → document_version |  |  |
| customer_id | uuid |  | FK → customer_profile |  |  |
| accepted_by | uuid |  | FK → account |  |  |
| accepted_at | timestamptz |  |  | now() |  |
| revoked_at | timestamptz | tak |  |  |  |
| source | varchar(10) |  |  | 'APP' |  |

Ograniczenia: `UNIQUE (document_version_id, customer_id) WHERE (revoked_at IS NULL)`

### `conversation`

*Moduł:* `communication`. Rozmowa 1:1 (klient-trener) lub kanał grupy/wydarzenia.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| kind | varchar(10) |  |  |  | prywatny / grupowy | 
| training_group_id | uuid | tak | FK → training_group |  |  |
| event_id | uuid | tak | FK → schedule_event |  |  |
| title | varchar(120) | tak |  |  |  |
| created_by | uuid |  | FK → account |  |  |
| created_at | timestamptz |  |  | now() |  |

### `conversation_participant`

*Moduł:* `communication`. Uczestnicy rozmowy i znacznik przeczytania.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| conversation_id | uuid |  | PK, FK → conversation |  |  |
| account_id | uuid |  | PK, FK → account |  |  |
| joined_at | timestamptz |  |  | now() |  |
| left_at | timestamptz | tak |  |  |  |
| last_read_at | timestamptz | tak |  |  |  |

Ograniczenia: PK (conversation_id, account_id)

### `message`

*Moduł:* `communication`. Wiadomość w rozmowie.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| conversation_id | uuid |  | FK → conversation |  |  |
| sender_id | uuid |  | FK → account |  |  |
| body | text | tak |  |  |  |
| created_at | timestamptz |  |  | now() |  |
| edited_at | timestamptz | tak |  |  |  |
| deleted_at | timestamptz | tak |  |  |  |

### `message_attachment`

*Moduł:* `communication`. Załączniki (zdjęcia, dokumenty, materiały trenera).

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| message_id | uuid |  | PK, FK → message |  |  |
| file_id | uuid |  | PK, FK → stored_file |  |  |

Ograniczenia: PK (message_id, file_id)

### `announcement`

*Moduł:* `communication`. Komunikat do osoby, grupy, uczestników wydarzenia lub wszystkich; marketingowy trafia tylko do osób ze zgodą. Odbiorcy materializowani jako notification.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| author_id | uuid |  | FK → account |  |  |
| audience | varchar(15) |  |  |  |  |
| target_account_id | uuid | tak | FK → account |  |  |
| training_group_id | uuid | tak | FK → training_group |  |  |
| event_id | uuid | tak | FK → schedule_event |  |  |
| is_marketing | boolean |  |  | false |  |
| title | varchar(160) |  |  |  |  |
| body | text |  |  |  |  |
| created_at | timestamptz |  |  | now() |  |
| sent_at | timestamptz | tak |  |  |  |

### `notification_type`

*Moduł:* `communication`. Słownik typów powiadomień i zarazem ich konfiguracja w studiu: czy wysyłać i z jakim wyprzedzeniem, np. CLASS_REMINDER 120 min, PASS_EXPIRING 4320 min. Pozostałe tabele wskazują typ kluczem obcym.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | smallint |  | PK |  |  |
| code | varchar(40) |  | UNIQUE |  | Czytelny kod typu używany w kodzie (enum po stronie Javy), np. CLASS_REMINDER; wiersze ładowane migracją. |
| description | varchar(255) |  |  |  |  |
| is_enabled | boolean |  |  | true |  |
| lead_time_minutes | integer | tak |  |  |  |
| default_channels | varchar(40) |  |  | 'PUSH' |  |
| updated_by | uuid | tak | FK → account |  |  |
| updated_at | timestamptz |  |  | now() |  |

### `notification_template`

*Moduł:* `communication`. Edytowalne szablony treści powiadomień.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| notification_type_id | smallint |  | FK → notification_type |  |  |
| channel | varchar(10) |  |  |  |  |
| locale | varchar(5) |  |  | 'pl' | Język szablonu (pl, en) |
| subject | varchar(200) | tak |  |  |  |
| body_template | text |  |  |  |  |
| updated_by | uuid | tak | FK → account |  |  |
| updated_at | timestamptz |  |  | now() |  |

Ograniczenia: UNIQUE (notification_type_id, channel, locale)

### `notification_preference`

*Moduł:* `communication`. Preferencje powiadomień użytkownika (transakcyjne). Zgody marketingowe są w documents.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| account_id | uuid |  | PK, FK → account |  |  |
| notification_type_id | smallint |  | PK, FK → notification_type |  |  |
| channel | varchar(10) |  | PK |  |  |
| enabled | boolean |  |  |  | Odstępstwo od domyślnych kanałów typu: FALSE = użytkownik wyłączył kanał, TRUE = włączył kanał spoza domyślnych. Brak wiersza = ustawienie domyślne z notification_type. |
| updated_at | timestamptz |  |  | now() |  |

Ograniczenia: PK (account_id, notification_type_id, channel)

### `notification`

*Moduł:* `communication`. Powiadomienie dla odbiorcy (skrzynka w aplikacji + historia wysyłek). dedup_key zabezpiecza zadania cykliczne przed duplikatami, np. "CLASS_REMINDER:<reservation_id>".

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| recipient_id | uuid |  | FK → account |  |  |
| notification_type_id | smallint |  | FK → notification_type |  |  |
| category | varchar(15) |  |  | 'TRANSACTIONAL' |  |
| title | varchar(200) |  |  |  |  |
| body | text | tak |  |  |  |
| payload | jsonb | tak |  |  |  |
| announcement_id | uuid | tak | FK → announcement |  |  |
| dedup_key | varchar(200) | tak | UNIQUE |  |  |
| scheduled_for | timestamptz |  |  | now() |  |
| created_at | timestamptz |  |  | now() |  |
| read_at | timestamptz | tak |  |  |  |

### `notification_delivery`

*Moduł:* `communication`. Próba doręczenia kanałem; błąd push nie cofa rezerwacji i nie blokuje e-maila.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| notification_id | uuid |  | FK → notification |  |  |
| channel | varchar(10) |  |  |  |  |
| status | varchar(20) |  |  | 'QUEUED' |  |
| provider_message_id | varchar(150) | tak |  |  |  |
| error | text | tak |  |  |  |
| attempted_at | timestamptz |  |  | now() |  |

### `device_token`

*Moduł:* `communication`. Tokeny FCM urządzeń; jedno konto może mieć kilka urządzeń. Rejestracja to upsert po token: ten sam telefon zalogowany na inne konto przepina wiersz. Wylogowanie lub odpowiedź FCM "UNREGISTERED" ustawia revoked_at.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| id | uuid |  | PK | uuid |  |
| account_id | uuid |  | FK → account |  |  |
| token | varchar(512) |  | UNIQUE |  |  |
| platform | varchar(10) |  |  |  |  |
| created_at | timestamptz |  |  | now() |  |
| last_seen_at | timestamptz |  |  | now() |  |
| revoked_at | timestamptz | tak |  |  |  |

### `calendar_feed`

*Moduł:* `integrations`. Prywatny link iCal do subskrypcji w Google Calendar (jednokierunkowo). Najprostszy wariant; pełna synchronizacja dwukierunkowa wymagałaby tabel mapowania.

| Kolumna | Typ | Nullable | Klucze | Domyślnie | Uwagi |
|---|---|---|---|---|---|
| account_id | uuid |  | PK, FK → account |  |  |
| token_hash | char(64) |  | UNIQUE |  |  |
| created_at | timestamptz |  |  | now() |  |
| revoked_at | timestamptz | tak |  |  |  |




## 4 &nbsp; &nbsp; Q&A

**Q: Co się dzieje z tokenem FCM, gdy użytkownik loguje się z nowego urządzenia? Czy powstaje drugi wiersz?**

A: Tak, nowe urządzenie dostaje nowy wiersz, bo jedno konto może mieć telefon i tablet, a push idzie na wszystkie aktywne tokeny. Token jest unikalny, więc rejestracja działa jak „wstaw albo przepnij”. 

Jeśli na tym samym telefonie zaloguje się ktoś inny, wiersz przechodzi na jego konto i poprzednia osoba przestaje dostawać tam powiadomienia. Po wylogowaniu albo gdy FCM odpowie, że token jest nieważny (np. po odinstalowaniu aplikacji), ustawiane jest `revoked_at`. 

Pole `last_seen_at` pozwala sprzątać tokeny dawno nieużywane.

<br>

**Q: Czym jest `dedup_key` i na czym polega?**

A: To unikalny klucz zbudowany z powodu powiadomienia, np. `CLASS_REMINDER:<id rezerwacji>`. Zadanie wysyłające przypomnienia robi `INSERT … ON CONFLICT (dedup_key) DO NOTHING`. Gdy ten sam powód pojawi się drugi raz, nowy wiersz nie powstaje i nic nie wychodzi podwójnie. 

Jest to potrzebne, bo zadania cykliczne i obsługa zdarzeń mogą wykonać się więcej niż raz, np. po ponowieniu albo gdy rezerwacja trafi w dwa kolejne przebiegi zadania. Komunikaty wysyłane ręcznie przez admina mogą mieć pusty klucz.

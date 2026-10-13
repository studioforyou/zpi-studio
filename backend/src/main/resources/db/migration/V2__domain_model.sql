-- V2
--
-- Domain model: all 67 tables of the application, grouped by owning module.
--
-- Conventions:
--  * Primary keys: UUID generated in the database (gen_random_uuid()), except audit_log (BIGINT identity).
--  * Event times: TIMESTAMPTZ in UTC. The studio time zone is stored in the studio table;
--    recurring rules keep local time (TIME) + weekday.
--  * Enumerated values are stored as VARCHAR text (e.g. 'ACTIVE').
--  * Money: NUMERIC(10,2) + CHAR(3) currency, PLN by default.
--  * Business records are not hard-deleted: statuses / archived_at / ended_at.
--  * Table names have no module prefix; the owning module is given in brackets in COMMENT ON TABLE.
--

-- ---------------------------------------------------------------------
-- STUDIO CONFIGURATION (module: configuration)
-- ---------------------------------------------------------------------
CREATE TABLE studio (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                        VARCHAR(120) NOT NULL,
    time_zone                   VARCHAR(64)  NOT NULL DEFAULT 'Europe/Warsaw',
    currency                    CHAR(3)      NOT NULL DEFAULT 'PLN',
    group_auto_book_weeks       SMALLINT     NOT NULL DEFAULT 4,
    pt_dispute_window_hours     SMALLINT     NOT NULL DEFAULT 24,
    substitution_needs_approval BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE studio IS '[configuration] A single row: studio details, time zone and global settings with a fixed type. Not multi-tenancy.';
COMMENT ON COLUMN studio.group_auto_book_weeks IS 'How many weeks ahead fixed groups are booked automatically; a group can override it.';
COMMENT ON COLUMN studio.pt_dispute_window_hours IS 'How long a client has to report a discrepancy after the trainer confirms a personal training session.';

-- ---------------------------------------------------------------------
-- FILES (module: platform / files)
-- ---------------------------------------------------------------------
CREATE TABLE stored_file (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_module        VARCHAR(40)  NOT NULL,
    uploaded_by         UUID,                 -- FK -> account (added below)
    bucket              VARCHAR(100) NOT NULL,
    object_key          VARCHAR(500) NOT NULL UNIQUE,
    original_name       VARCHAR(255) NOT NULL,
    mime_type           VARCHAR(100) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    sha256              CHAR(64),
    sensitivity         VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ
);
COMMENT ON TABLE stored_file IS '[files] Metadata of a file in Cloud Storage; the content lives outside the database. Used by chat attachments, documents and photos.';
COMMENT ON COLUMN stored_file.sensitivity IS 'HEALTH = separate bucket/prefix and stricter access control.';

-- ---------------------------------------------------------------------
-- IDENTITY & ACCESS (module: identity)
-- ---------------------------------------------------------------------
CREATE TABLE account (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(254) NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING_APPROVAL',
    created_by          UUID REFERENCES account(id),
    activated_at        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_account_email ON account (lower(email));
COMMENT ON TABLE account IS '[identity] Login account, without business data.';
COMMENT ON COLUMN account.status IS 'INVITED: account created by the studio, waiting for the first login; PENDING_APPROVAL: self-registration waiting for approval.';
COMMENT ON COLUMN account.created_by IS 'NULL for self-registration; otherwise the staff member who created the account (activation audit).';

ALTER TABLE stored_file ADD CONSTRAINT fk_stored_file_uploader FOREIGN KEY (uploaded_by) REFERENCES account(id);

CREATE TABLE identity_link (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID NOT NULL REFERENCES account(id),
    provider            VARCHAR(20)  NOT NULL,
    external_uid        VARCHAR(128) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (provider, external_uid),
    UNIQUE (account_id, provider)
);
COMMENT ON TABLE identity_link IS '[identity] Link between the account and the identity provider (Firebase UID). Used if Firebase is chosen as the login provider; the password is then not stored in the database.';

CREATE TABLE local_credential (
    account_id          UUID PRIMARY KEY REFERENCES account(id),
    password_hash       VARCHAR(255) NOT NULL,
    password_changed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE local_credential IS '[identity] ONLY if the app uses its own Spring Security login instead of Firebase. With Firebase this table goes away (Firebase handles password reset).';

CREATE TABLE account_invitation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID NOT NULL REFERENCES account(id),
    token_hash          CHAR(64)     NOT NULL UNIQUE,
    sent_by             UUID NOT NULL REFERENCES account(id),
    sent_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL,
    used_at             TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ
);
COMMENT ON TABLE account_invitation IS '[identity] E-mail invitation from the studio, after which a client or trainer sets up access.';
COMMENT ON COLUMN account_invitation.token_hash IS 'SHA-256 hash of the link token; the token itself is not stored.';

CREATE TABLE role (
    id                  SMALLINT PRIMARY KEY,
    code                VARCHAR(30)  NOT NULL UNIQUE,
    display_name        VARCHAR(60)  NOT NULL
);
COMMENT ON TABLE role IS '[identity] Business role with a default set of permissions. Dictionary loaded by a migration.';

CREATE TABLE permission (
    id                  SMALLINT PRIMARY KEY,
    code                VARCHAR(60)  NOT NULL UNIQUE,
    category            VARCHAR(30)  NOT NULL,
    description         VARCHAR(255) NOT NULL
);
COMMENT ON TABLE permission IS '[identity] Controlled permission described in business terms, e.g. SCHEDULE_MANAGE_ALL, SCHEDULE_EDIT_OWN, AVAILABILITY_EDIT_OWN, CUSTOMER_HEALTH_READ, PASS_ADJUST, ATTENDANCE_CORRECT.';

CREATE TABLE role_permission (
    role_id             SMALLINT NOT NULL REFERENCES role(id),
    permission_id       SMALLINT NOT NULL REFERENCES permission(id),
    PRIMARY KEY (role_id, permission_id)
);
COMMENT ON TABLE role_permission IS '[identity] Default permissions of a role.';

CREATE TABLE account_role (
    account_id          UUID     NOT NULL REFERENCES account(id),
    role_id             SMALLINT NOT NULL REFERENCES role(id),
    granted_by          UUID REFERENCES account(id),
    granted_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, role_id)
);
COMMENT ON TABLE account_role IS '[identity] Roles of an account (many roles on one login). Grants and revocations are recorded in audit_log.';

CREATE TABLE account_permission_override (
    account_id          UUID     NOT NULL REFERENCES account(id),
    permission_id       SMALLINT NOT NULL REFERENCES permission(id),
    effect              VARCHAR(6) NOT NULL,
    granted_by          UUID NOT NULL REFERENCES account(id),
    granted_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, permission_id)
);
COMMENT ON TABLE account_permission_override IS '[identity] Extra or revoked permission of a specific staff member on top of the role defaults (e.g. one trainer edits only their own classes, another the whole schedule).';

CREATE TABLE account_status_change (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID NOT NULL REFERENCES account(id),
    from_status         VARCHAR(20),
    to_status           VARCHAR(20) NOT NULL,
    changed_by          UUID REFERENCES account(id),
    reason              TEXT,
    changed_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_account_status_change_account ON account_status_change (account_id, changed_at);
COMMENT ON TABLE account_status_change IS '[identity] Account status history: every status change (approval, rejection, suspension, restoration) with its author and reason.';

-- ---------------------------------------------------------------------
-- AUDIT (module: platform / audit)
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_account_id    UUID REFERENCES account(id),
    module              VARCHAR(40)  NOT NULL,
    entity_type         VARCHAR(60)  NOT NULL,
    entity_id           UUID         NOT NULL,
    action              VARCHAR(60)  NOT NULL,
    changes             JSONB,
    reason              TEXT,
    correlation_id      UUID
);
CREATE INDEX ix_audit_entity ON audit_log (entity_type, entity_id, occurred_at);
COMMENT ON TABLE audit_log IS '[audit] Shared "who, what, when, why" change log. INSERT only.';
COMMENT ON COLUMN audit_log.changes IS 'Fields before/after, e.g. {"starts_at":{"old":"...","new":"..."}}. No health data in the content.';

-- ---------------------------------------------------------------------
-- CUSTOMER (module: customer)
-- ---------------------------------------------------------------------
CREATE TABLE customer_profile (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id              UUID UNIQUE REFERENCES account(id),
    kind                    VARCHAR(10)  NOT NULL DEFAULT 'ADULT',
    first_name              VARCHAR(80)  NOT NULL,
    last_name               VARCHAR(80)  NOT NULL,
    birth_date              DATE,
    phone                   VARCHAR(20),
    emergency_contact_name  VARCHAR(120),
    emergency_phone         VARCHAR(20),
    status                  VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_by              UUID REFERENCES account(id),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE customer_profile IS '[customer] Service participant: an adult client or a child (a child has no account; guardianship points to the guardian). Every reservation, pass, consent and attendance points to this profile.';
COMMENT ON COLUMN customer_profile.account_id IS 'Adult: own account (1:1). Child: NULL, managed by a guardian through guardianship; an account can be attached at adulthood without losing history.';
COMMENT ON COLUMN customer_profile.birth_date IS 'Needed for age groups and parental consents for minors.';

CREATE TABLE guardianship (
    guardian_id         UUID NOT NULL REFERENCES customer_profile(id),
    child_id            UUID NOT NULL REFERENCES customer_profile(id),
    relation            VARCHAR(20) NOT NULL DEFAULT 'PARENT',
    is_payer            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ,
    PRIMARY KEY (guardian_id, child_id)
);
COMMENT ON TABLE guardianship IS '[customer] A parent/guardian manages a child profile; one account can have many children, a child can have two guardians.';

CREATE TABLE staff_profile (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID NOT NULL UNIQUE REFERENCES account(id),
    first_name          VARCHAR(80)  NOT NULL,
    last_name           VARCHAR(80)  NOT NULL,
    phone               VARCHAR(20),
    bio                 TEXT,
    photo_file_id       UUID REFERENCES stored_file(id),
    calendar_color      CHAR(7),
    status              VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE staff_profile IS '[staff] Staff member profile (trainer, instructor, reception, dietitian, admin).';
COMMENT ON COLUMN staff_profile.bio IS 'Description visible to clients. Internal notes about clients are in trainer_note.';

CREATE TABLE staff_assignment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         UUID NOT NULL REFERENCES customer_profile(id),
    staff_id            UUID NOT NULL REFERENCES staff_profile(id),
    scope               VARCHAR(20) NOT NULL DEFAULT 'PERSONAL_TRAINING',
    can_view_health     BOOLEAN NOT NULL DEFAULT FALSE,
    assigned_by         UUID NOT NULL REFERENCES account(id),
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at            TIMESTAMPTZ,
    end_reason          VARCHAR(255)
);
CREATE UNIQUE INDEX ux_staff_assignment_active ON staff_assignment (customer_id, staff_id, scope) WHERE ended_at IS NULL;
COMMENT ON TABLE staff_assignment IS '[customer] Client - trainer/dietitian relationship, many-to-many, with history. Basis for trainer access to client data.';
COMMENT ON COLUMN staff_assignment.can_view_health IS 'Access to health data comes from the relationship + consent, not from the role alone.';

CREATE TABLE customer_interest (
    customer_id         UUID NOT NULL REFERENCES customer_profile(id),
    service_type_id     UUID NOT NULL,   -- FK -> service_type (added below)
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, service_type_id)
);
COMMENT ON TABLE customer_interest IS '[customer] Classes a client is interested in, for free-spot notifications.';

-- ---------------------------------------------------------------------
-- OFFERING (module: offering)
-- ---------------------------------------------------------------------
CREATE TABLE service_type (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                        VARCHAR(120) NOT NULL,
    description                 TEXT,
    category                    VARCHAR(30)  NOT NULL,
    level                       VARCHAR(20),
    min_age                     SMALLINT,
    max_age                     SMALLINT,
    default_duration_minutes    SMALLINT NOT NULL,
    min_participants            SMALLINT,
    max_participants            SMALLINT,
    calendar_color              CHAR(7),
    is_active                   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE service_type IS '[offering] Service type with its rules, e.g. "Calisthenics - intermediate", "Personal training".';
COMMENT ON COLUMN service_type.level IS 'Level filter. If the studio wants its own level names, turn this into a dictionary.';

ALTER TABLE customer_interest ADD CONSTRAINT fk_customer_interest_service FOREIGN KEY (service_type_id) REFERENCES service_type(id);

CREATE TABLE staff_qualification (
    staff_id            UUID NOT NULL REFERENCES staff_profile(id),
    service_type_id     UUID NOT NULL REFERENCES service_type(id),
    PRIMARY KEY (staff_id, service_type_id)
);
COMMENT ON TABLE staff_qualification IS '[staff] Which services a staff member may run; used to pick "qualified instructors" for substitutions.';

CREATE TABLE booking_policy (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope                           VARCHAR(20) NOT NULL,
    service_type_id                 UUID REFERENCES service_type(id),
    training_group_id               UUID,   -- FK -> training_group (added below)
    event_id                        UUID,   -- FK -> schedule_event (added below)
    free_cancel_hours               SMALLINT,
    late_cancel_forfeits_entry      BOOLEAN,
    booking_opens_days_before       SMALLINT,
    booking_closes_minutes_before   SMALLINT,
    waitlist_enabled                BOOLEAN,
    waitlist_confirm_minutes        SMALLINT,
    auto_cancel_enabled             BOOLEAN,
    auto_cancel_min_participants    SMALLINT,
    auto_cancel_hours_before        SMALLINT,
    valid_from                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to                        TIMESTAMPTZ,
    created_by                      UUID REFERENCES account(id),
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE booking_policy IS '[configuration] Booking, cancellation and auto-cancel rules scoped STUDIO > service type > group > event; NULL in a field = inherit from the level above. Versioned with valid_from/valid_to.';
COMMENT ON COLUMN booking_policy.waitlist_confirm_minutes IS 'NULL = automatic promotion from the waitlist; a value = time to confirm (future use).';
COMMENT ON COLUMN booking_policy.auto_cancel_enabled IS 'FALSE at group/event level = rule switched off.';

CREATE TABLE makeup_policy (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(120) NOT NULL,
    validity_days           SMALLINT NOT NULL,
    max_per_pass            SMALLINT,
    grant_on_in_time_cancel BOOLEAN NOT NULL DEFAULT TRUE,
    grant_on_studio_cancel  BOOLEAN NOT NULL DEFAULT TRUE,
    expiry_checked_against  VARCHAR(10) NOT NULL DEFAULT 'EVENT_DATE',
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE makeup_policy IS '[membership] Rules for granting make-up credits and their validity, attached to a product.';
COMMENT ON COLUMN makeup_policy.expiry_checked_against IS 'Edge case: whether validity is checked against the booking date or the class date.';

CREATE TABLE pass_product (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(120) NOT NULL,
    description         TEXT,
    kind                VARCHAR(20)  NOT NULL,
    audience            VARCHAR(10)  NOT NULL DEFAULT 'ANY',
    makeup_policy_id    UUID REFERENCES makeup_policy(id),
    suspension_allowed  BOOLEAN NOT NULL DEFAULT FALSE,
    max_suspension_days SMALLINT,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE pass_product IS '[offering] Offer product: entry pass, single entry, personal training package, time pass, voucher.';

CREATE TABLE pass_product_variant (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID NOT NULL REFERENCES pass_product(id),
    entries_count       SMALLINT,
    validity_days       SMALLINT NOT NULL,
    price               NUMERIC(10,2) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'PLN',
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from          TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to            TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_variant_active ON pass_product_variant (product_id, entries_count, validity_days) WHERE is_active;
COMMENT ON TABLE pass_product_variant IS '[offering] Price variant: number of entries x validity x price. A grid of variants supports the "sliders" without inventing a formula. A price change = a new variant.';
COMMENT ON COLUMN pass_product_variant.entries_count IS 'NULL = unlimited entries (time pass).';

CREATE TABLE pass_product_service (
    product_id          UUID NOT NULL REFERENCES pass_product(id),
    service_type_id     UUID NOT NULL REFERENCES service_type(id),
    PRIMARY KEY (product_id, service_type_id)
);
COMMENT ON TABLE pass_product_service IS '[offering] Which services a product pays for, e.g. a pass for workout only or pole dance only.';

-- ---------------------------------------------------------------------
-- SCHEDULING (module: scheduling)
-- ---------------------------------------------------------------------
CREATE TABLE resource (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(80)  NOT NULL UNIQUE,
    kind                VARCHAR(20)  NOT NULL DEFAULT 'ROOM',
    parent_resource_id  UUID REFERENCES resource(id),
    capacity_units      SMALLINT NOT NULL DEFAULT 1,
    max_people          SMALLINT,
    description         TEXT,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE resource IS '[scheduling] A room or another resource (equipment). The pole dance room and two workout rooms are three rows.';
COMMENT ON COLUMN resource.capacity_units IS 'How many parallel "slots" the resource holds, e.g. 3 personal training sessions at once in the workout room. Configurable, not hard-coded.';
COMMENT ON COLUMN resource.max_people IS 'Maximum number of people in group classes in this room.';

CREATE TABLE service_type_resource (
    service_type_id     UUID NOT NULL REFERENCES service_type(id),
    resource_id         UUID NOT NULL REFERENCES resource(id),
    units_required      SMALLINT,
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (service_type_id, resource_id)
);
COMMENT ON TABLE service_type_resource IS '[scheduling] Which rooms a service can use and how much capacity it takes: a group class takes the whole room (units_required NULL), personal training 1 slot, pole dance only in a room with poles.';

CREATE TABLE training_group (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(120) NOT NULL,
    service_type_id     UUID NOT NULL REFERENCES service_type(id),
    auto_book_weeks     SMALLINT,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE training_group IS '[group_training] Fixed group, e.g. "Pole dance beginners Tue/Thu 18:00".';
COMMENT ON COLUMN training_group.auto_book_weeks IS 'NULL = value from studio.group_auto_book_weeks.';

CREATE TABLE group_membership (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id            UUID NOT NULL REFERENCES training_group(id),
    customer_id         UUID NOT NULL REFERENCES customer_profile(id),
    auto_book           BOOLEAN NOT NULL DEFAULT TRUE,
    joined_on           DATE NOT NULL DEFAULT CURRENT_DATE,
    left_on             DATE,
    added_by            UUID REFERENCES account(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_group_membership_active ON group_membership (group_id, customer_id) WHERE left_on IS NULL;
COMMENT ON TABLE group_membership IS '[group_training] Client membership in a fixed group, with history.';

CREATE TABLE recurrence_series (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_type_id     UUID NOT NULL REFERENCES service_type(id),
    training_group_id   UUID REFERENCES training_group(id),
    title               VARCHAR(120),
    weekday             SMALLINT NOT NULL,
    local_start_time    TIME NOT NULL,
    duration_minutes    SMALLINT NOT NULL,
    interval_weeks      SMALLINT NOT NULL DEFAULT 1,
    starts_on           DATE NOT NULL,
    ends_on             DATE,
    default_resource_id UUID REFERENCES resource(id),
    default_staff_id    UUID REFERENCES staff_profile(id),
    capacity            SMALLINT,
    status              VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    created_by          UUID REFERENCES account(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE recurrence_series IS '[scheduling] Recurrence rule that generates occurrences. Studio local time ("Tuesday 18:00"), not UTC.';
COMMENT ON COLUMN recurrence_series.weekday IS 'ISO: 1 = Monday. Several weekdays = several series (simpler than RRULE; to be decided).';

CREATE TABLE schedule_event (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind                    VARCHAR(20) NOT NULL,
    service_type_id         UUID REFERENCES service_type(id),
    series_id               UUID REFERENCES recurrence_series(id),
    series_original_start   TIMESTAMPTZ,
    is_series_exception     BOOLEAN NOT NULL DEFAULT FALSE,
    training_group_id       UUID REFERENCES training_group(id),
    title                   VARCHAR(120),
    starts_at               TIMESTAMPTZ NOT NULL,
    ends_at                 TIMESTAMPTZ NOT NULL,
    capacity                SMALLINT,
    visibility              VARCHAR(10) NOT NULL DEFAULT 'PUBLIC',
    status                  VARCHAR(12) NOT NULL DEFAULT 'SCHEDULED',
    force_run               BOOLEAN NOT NULL DEFAULT FALSE,
    cancel_reason           VARCHAR(20),
    cancel_note             TEXT,
    cancelled_by            UUID REFERENCES account(id),
    cancelled_at            TIMESTAMPTZ,
    block_reason            VARCHAR(20),
    guest_name              VARCHAR(120),
    guest_phone             VARCHAR(20),
    staff_note              TEXT,
    created_by              UUID REFERENCES account(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_event_series_occurrence ON schedule_event (series_id, series_original_start) WHERE series_id IS NOT NULL;
CREATE INDEX ix_event_time ON schedule_event (starts_at, ends_at);
COMMENT ON TABLE schedule_event IS '[scheduling] One calendar event: class, personal training, workshop, consultation or a room/time block. One time model for everything.';
COMMENT ON COLUMN schedule_event.series_original_start IS 'Original start of the occurrence in the series; the occurrence key, even when the date was moved once.';
COMMENT ON COLUMN schedule_event.force_run IS 'Admin forces the event to run although the minimum was not reached.';
COMMENT ON COLUMN schedule_event.guest_name IS 'Consultation without a client account.';
COMMENT ON COLUMN schedule_event.staff_note IS 'Note from the person who ran the class, written afterwards.';
COMMENT ON COLUMN schedule_event.version IS 'Optimistic locking: concurrent event edit and client booking.';

ALTER TABLE booking_policy ADD CONSTRAINT fk_policy_group FOREIGN KEY (training_group_id) REFERENCES training_group(id);
ALTER TABLE booking_policy ADD CONSTRAINT fk_policy_event FOREIGN KEY (event_id) REFERENCES schedule_event(id);

CREATE TABLE event_staff (
    event_id            UUID NOT NULL REFERENCES schedule_event(id),
    staff_id            UUID NOT NULL REFERENCES staff_profile(id),
    role                VARCHAR(10) NOT NULL DEFAULT 'LEAD',
    assigned_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, staff_id)
);
COMMENT ON TABLE event_staff IS '[scheduling] People running the event. Changing who runs it = changing the row + an audit_log entry.';

CREATE TABLE event_resource (
    event_id            UUID NOT NULL REFERENCES schedule_event(id),
    resource_id         UUID NOT NULL REFERENCES resource(id),
    units_used          SMALLINT,
    PRIMARY KEY (event_id, resource_id)
);
COMMENT ON TABLE event_resource IS '[scheduling] Resource used by an event. units_used NULL = the whole room. The sum of units of overlapping events <= resource.capacity_units, enforced in a transaction with a lock.';

CREATE TABLE trainer_availability (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id            UUID NOT NULL REFERENCES staff_profile(id),
    weekday             SMALLINT NOT NULL,
    local_start_time    TIME NOT NULL,
    local_end_time      TIME NOT NULL,
    valid_from          DATE NOT NULL DEFAULT CURRENT_DATE,
    valid_to            DATE,
    created_by          UUID REFERENCES account(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE trainer_availability IS '[staff] Regular trainer availability; a one-off extra availability is a row with valid_from = valid_to.';

CREATE TABLE staff_time_off (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id            UUID NOT NULL REFERENCES staff_profile(id),
    kind                VARCHAR(15) NOT NULL,
    starts_at           TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ NOT NULL,
    status              VARCHAR(10) NOT NULL DEFAULT 'REQUESTED',
    reason              TEXT,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by          UUID REFERENCES account(id),
    decided_at          TIMESTAMPTZ,
    decision_note       TEXT
);
COMMENT ON TABLE staff_time_off IS '[staff] Leave or unavailability with an admin decision.';

CREATE TABLE substitution_request (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id            UUID NOT NULL REFERENCES schedule_event(id),
    original_staff_id   UUID NOT NULL REFERENCES staff_profile(id),
    requested_by        UUID NOT NULL REFERENCES account(id),
    reason              TEXT,
    status              VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    substitute_staff_id UUID REFERENCES staff_profile(id),
    claimed_at          TIMESTAMPTZ,
    decided_by          UUID REFERENCES account(id),
    decided_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_substitution_open ON substitution_request (event_id) WHERE status IN ('OPEN','CLAIMED');
COMMENT ON TABLE substitution_request IS '[staff] Request for a substitute, accepted by another trainer, with an admin decision (if studio.substitution_needs_approval).';

CREATE TABLE staff_rate (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id            UUID REFERENCES staff_profile(id),
    service_type_id     UUID REFERENCES service_type(id),
    rate_kind           VARCHAR(15) NOT NULL,
    amount              NUMERIC(10,2) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'PLN',
    valid_from          DATE NOT NULL,
    valid_to            DATE
);
COMMENT ON TABLE staff_rate IS '[staff] TO BE DECIDED. Draft of a rate depending on trainer, service and period; hours are computed from event_staff + finished events.';

-- ---------------------------------------------------------------------
-- PAYMENTS (module: payments)
-- ---------------------------------------------------------------------
CREATE TABLE customer_order (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    buyer_id            UUID NOT NULL REFERENCES customer_profile(id),
    status              VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    total_amount        NUMERIC(10,2) NOT NULL,
    currency            CHAR(3) NOT NULL DEFAULT 'PLN',
    created_by          UUID REFERENCES account(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at             TIMESTAMPTZ
);
COMMENT ON TABLE customer_order IS '[payments] Order: who buys and for how much.';

CREATE TABLE order_item (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES customer_order(id),
    variant_id          UUID NOT NULL REFERENCES pass_product_variant(id),
    beneficiary_id      UUID NOT NULL REFERENCES customer_profile(id),
    product_name        VARCHAR(120) NOT NULL,
    unit_price          NUMERIC(10,2) NOT NULL,
    quantity            SMALLINT NOT NULL DEFAULT 1
);
COMMENT ON TABLE order_item IS '[payments] Order line with a snapshot of name and price. beneficiary = participant (e.g. a child), may differ from the buyer.';

CREATE TABLE payment (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                UUID NOT NULL REFERENCES customer_order(id),
    provider                VARCHAR(15) NOT NULL,
    method                  VARCHAR(15) NOT NULL,
    status                  VARCHAR(12) NOT NULL DEFAULT 'INITIATED',
    amount                  NUMERIC(10,2) NOT NULL,
    currency                CHAR(3) NOT NULL DEFAULT 'PLN',
    provider_session_id     VARCHAR(100) UNIQUE,
    provider_transaction_id VARCHAR(100) UNIQUE,
    registered_by           UUID REFERENCES account(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at            TIMESTAMPTZ
);
COMMENT ON TABLE payment IS '[payments] Payment attempt: Przelewy24, or cash/bank transfer marked manually.';

CREATE TABLE payment_webhook_event (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider            VARCHAR(15) NOT NULL,
    provider_event_id   VARCHAR(150) NOT NULL,
    payload             JSONB NOT NULL,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at        TIMESTAMPTZ,
    result              VARCHAR(20),
    UNIQUE (provider, provider_event_id)
);
COMMENT ON TABLE payment_webhook_event IS '[payments] Callback idempotency: a repeated webhook does not activate a package twice.';

-- ---------------------------------------------------------------------
-- MEMBERSHIP / ENTITLEMENTS (module: membership)
-- ---------------------------------------------------------------------
CREATE TABLE customer_pass (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         UUID NOT NULL REFERENCES customer_profile(id),
    variant_id          UUID NOT NULL REFERENCES pass_product_variant(id),
    order_item_id       UUID UNIQUE REFERENCES order_item(id),
    product_name        VARCHAR(120) NOT NULL,
    entries_total       SMALLINT,
    entries_remaining   SMALLINT,
    price_paid          NUMERIC(10,2) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING_PAYMENT',
    valid_from          DATE,
    valid_until         DATE,
    created_by          UUID REFERENCES account(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_customer_pass_customer ON customer_pass (customer_id, status);
COMMENT ON TABLE customer_pass IS '[membership] Pass/package bought for a specific participant, with a snapshot of the terms at purchase time.';
COMMENT ON COLUMN customer_pass.entries_remaining IS 'Balance kept in the same transaction as the pass_ledger entry; the ledger is the source of truth.';
COMMENT ON COLUMN customer_pass.status IS 'SCHEDULED = bought before the previous one ends, starts later.';

CREATE TABLE pass_suspension (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pass_id             UUID NOT NULL REFERENCES customer_pass(id),
    starts_on           DATE NOT NULL,
    ends_on             DATE NOT NULL,
    reason              TEXT,
    created_by          UUID NOT NULL REFERENCES account(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at        TIMESTAMPTZ
);
COMMENT ON TABLE pass_suspension IS '[membership] Pass suspension, if the product allows it; moves valid_until.';

-- ---------------------------------------------------------------------
-- BOOKING (module: booking)
-- ---------------------------------------------------------------------
CREATE TABLE reservation (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id                UUID NOT NULL REFERENCES schedule_event(id),
    customer_id             UUID NOT NULL REFERENCES customer_profile(id),
    booked_by               UUID NOT NULL REFERENCES account(id),
    source                  VARCHAR(20) NOT NULL DEFAULT 'SELF',
    status                  VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    customer_pass_id        UUID REFERENCES customer_pass(id),
    makeup_credit_id        UUID,   -- FK -> makeup_credit (added below)
    booking_policy_id       UUID REFERENCES booking_policy(id),
    free_cancel_until       TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    cancelled_by            UUID REFERENCES account(id),
    cancel_kind             VARCHAR(16),
    attendance              VARCHAR(10) NOT NULL DEFAULT 'UNMARKED',
    attendance_marked_by    UUID REFERENCES account(id),
    attendance_marked_at    TIMESTAMPTZ,
    trainer_confirmed_at    TIMESTAMPTZ,
    client_confirmed_at     TIMESTAMPTZ,
    settlement_status       VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_reservation_active ON reservation (event_id, customer_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_reservation_customer ON reservation (customer_id, created_at);
COMMENT ON TABLE reservation IS '[booking] Place of a participant in an event, with cancellation, attendance and personal training confirmation.';
COMMENT ON COLUMN reservation.free_cancel_until IS 'Snapshot of the free cancellation deadline at booking time; a later policy change does not alter it.';
COMMENT ON COLUMN reservation.attendance IS 'Separate from status: a no-show (ABSENT) is not the same as a cancellation.';
COMMENT ON COLUMN reservation.settlement_status IS 'Personal training: OPEN until the dispute window ends, DISPUTED when the client reported a discrepancy, FINAL once settled.';

CREATE TABLE waitlist_entry (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id            UUID NOT NULL REFERENCES schedule_event(id),
    customer_id         UUID NOT NULL REFERENCES customer_profile(id),
    added_by            UUID NOT NULL REFERENCES account(id),
    status              VARCHAR(10) NOT NULL DEFAULT 'WAITING',
    offer_expires_at    TIMESTAMPTZ,
    reservation_id      UUID REFERENCES reservation(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_waitlist_waiting ON waitlist_entry (event_id, customer_id) WHERE status IN ('WAITING','OFFERED');
CREATE INDEX ix_waitlist_order ON waitlist_entry (event_id, created_at, id) WHERE status = 'WAITING';
COMMENT ON TABLE waitlist_entry IS '[booking] Waitlist queue; ordered by created_at, id (deterministic promotion). OFFERED/offer_expires_at for future spot confirmation.';

CREATE TABLE training_dispute (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id      UUID NOT NULL REFERENCES reservation(id),
    raised_by           UUID NOT NULL REFERENCES account(id),
    reason              TEXT NOT NULL,
    status              VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    resolution          VARCHAR(15),
    resolution_note     TEXT,
    resolved_by         UUID REFERENCES account(id),
    raised_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_dispute_open ON training_dispute (reservation_id) WHERE status = 'OPEN';
COMMENT ON TABLE training_dispute IS '[booking] Discrepancy reported by a client about whether a personal training session took place, and its resolution by an admin.';

-- Make-up credits and pass ledger (membership), placed after reservation because of the FKs
CREATE TABLE makeup_credit (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id             UUID NOT NULL REFERENCES customer_profile(id),
    source_pass_id          UUID NOT NULL REFERENCES customer_pass(id),
    source_reservation_id   UUID UNIQUE REFERENCES reservation(id),
    granted_by              UUID REFERENCES account(id),
    granted_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at              TIMESTAMPTZ NOT NULL,
    status                  VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE',
    used_reservation_id     UUID UNIQUE REFERENCES reservation(id),
    used_at                 TIMESTAMPTZ
);
COMMENT ON TABLE makeup_credit IS '[membership] A single make-up credit: where it came from, until when it is valid, when it was used.';

ALTER TABLE reservation ADD CONSTRAINT fk_reservation_makeup FOREIGN KEY (makeup_credit_id) REFERENCES makeup_credit(id);

CREATE TABLE pass_ledger (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pass_id             UUID NOT NULL REFERENCES customer_pass(id),
    kind                VARCHAR(20) NOT NULL,
    entries_delta       SMALLINT NOT NULL DEFAULT 0,
    valid_until_before  DATE,
    valid_until_after   DATE,
    reservation_id      UUID REFERENCES reservation(id),
    reason              TEXT,
    actor_account_id    UUID REFERENCES account(id),
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_ledger_idempotent ON pass_ledger (pass_id, reservation_id, kind) WHERE reservation_id IS NOT NULL;
CREATE INDEX ix_ledger_pass ON pass_ledger (pass_id, occurred_at);
COMMENT ON TABLE pass_ledger IS '[membership] Pass event ledger: activation, entry reserved/consumed/returned, corrections, extensions. INSERT only. The same reservation cannot consume an entry twice (unique index).';

-- ---------------------------------------------------------------------
-- PERSONAL TRAINING (module: personal_training) - trainee card
-- ---------------------------------------------------------------------
CREATE TABLE trainee_card (
    customer_id         UUID PRIMARY KEY REFERENCES customer_profile(id),
    goals               TEXT,
    sport_experience    TEXT,
    updated_by          UUID REFERENCES account(id),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE trainee_card IS '[personal_training] Trainee card: goals and experience.';

CREATE TABLE health_note (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         UUID NOT NULL REFERENCES customer_profile(id),
    kind                VARCHAR(20) NOT NULL,
    description         TEXT NOT NULL,
    reported_by         UUID NOT NULL REFERENCES account(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at         TIMESTAMPTZ
);
COMMENT ON TABLE health_note IS '[personal_training] Health restrictions and injuries. Sensitive data: visible only to a trainer with staff_assignment.can_view_health.';

CREATE TABLE trainer_note (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id         UUID NOT NULL REFERENCES customer_profile(id),
    author_staff_id     UUID NOT NULL REFERENCES staff_profile(id),
    body                TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE trainer_note IS '[personal_training] Trainer notes about a trainee.';

-- ---------------------------------------------------------------------
-- DOCUMENTS & CONSENTS (module: documents)
-- ---------------------------------------------------------------------
CREATE TABLE document (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(40)  NOT NULL UNIQUE,
    title               VARCHAR(160) NOT NULL,
    kind                VARCHAR(25)  NOT NULL,
    audience            VARCHAR(10)  NOT NULL DEFAULT 'ALL',
    is_required         BOOLEAN NOT NULL DEFAULT FALSE,
    blocks_booking      BOOLEAN NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE document IS '[documents] Definition of a document or consent: terms, GDPR, participation consent, parental consent, health questionnaire, separate e-mail/SMS marketing consents.';

CREATE TABLE document_version (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id             UUID NOT NULL REFERENCES document(id),
    version_no              SMALLINT NOT NULL,
    content                 TEXT,
    file_id                 UUID REFERENCES stored_file(id),
    requires_reacceptance   BOOLEAN NOT NULL DEFAULT TRUE,
    published_at            TIMESTAMPTZ,
    published_by            UUID REFERENCES account(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, version_no)
);
COMMENT ON TABLE document_version IS '[documents] Content version; an accepted version must not be deleted.';

CREATE TABLE document_service_requirement (
    document_id         UUID NOT NULL REFERENCES document(id),
    service_type_id     UUID NOT NULL REFERENCES service_type(id),
    PRIMARY KEY (document_id, service_type_id)
);
COMMENT ON TABLE document_service_requirement IS '[documents] Document required only for selected services (e.g. pole dance consent). An is_required document with no rows here = required globally.';

CREATE TABLE document_acceptance (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_version_id     UUID NOT NULL REFERENCES document_version(id),
    customer_id             UUID NOT NULL REFERENCES customer_profile(id),
    accepted_by             UUID NOT NULL REFERENCES account(id),
    accepted_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at              TIMESTAMPTZ,
    source                  VARCHAR(10) NOT NULL DEFAULT 'APP'
);
CREATE UNIQUE INDEX ux_acceptance_active ON document_acceptance (document_version_id, customer_id) WHERE revoked_at IS NULL;
COMMENT ON TABLE document_acceptance IS '[documents] Acceptance of a specific version by a specific participant; accepted_by = parent for a child. Withdrawing consent = revoked_at.';

-- ---------------------------------------------------------------------
-- COMMUNICATION (module: communication)
-- ---------------------------------------------------------------------
CREATE TABLE conversation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind                VARCHAR(10) NOT NULL,
    training_group_id   UUID REFERENCES training_group(id),
    event_id            UUID REFERENCES schedule_event(id),
    title               VARCHAR(120),
    created_by          UUID NOT NULL REFERENCES account(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE conversation IS '[communication] 1:1 conversation (client-trainer) or a group/event channel.';

CREATE TABLE conversation_participant (
    conversation_id     UUID NOT NULL REFERENCES conversation(id),
    account_id          UUID NOT NULL REFERENCES account(id),
    joined_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at             TIMESTAMPTZ,
    last_read_at        TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, account_id)
);
COMMENT ON TABLE conversation_participant IS '[communication] Conversation participants and read marker.';

CREATE TABLE message (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id     UUID NOT NULL REFERENCES conversation(id),
    sender_id           UUID NOT NULL REFERENCES account(id),
    body                TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at           TIMESTAMPTZ,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX ix_message_conversation ON message (conversation_id, created_at);
COMMENT ON TABLE message IS '[communication] Message in a conversation.';

CREATE TABLE message_attachment (
    message_id          UUID NOT NULL REFERENCES message(id),
    file_id             UUID NOT NULL REFERENCES stored_file(id),
    PRIMARY KEY (message_id, file_id)
);
COMMENT ON TABLE message_attachment IS '[communication] Attachments (photos, documents, trainer materials).';

CREATE TABLE announcement (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id           UUID NOT NULL REFERENCES account(id),
    audience            VARCHAR(15) NOT NULL,
    target_account_id   UUID REFERENCES account(id),
    training_group_id   UUID REFERENCES training_group(id),
    event_id            UUID REFERENCES schedule_event(id),
    is_marketing        BOOLEAN NOT NULL DEFAULT FALSE,
    title               VARCHAR(160) NOT NULL,
    body                TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at             TIMESTAMPTZ
);
COMMENT ON TABLE announcement IS '[communication] Announcement to a person, a group, event participants or everyone; a marketing one only reaches people who consented. Recipients are materialised as notification rows.';

CREATE TABLE notification_type (
    id                  SMALLINT PRIMARY KEY,
    code                VARCHAR(40)  NOT NULL UNIQUE,
    description         VARCHAR(255) NOT NULL,
    is_enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    lead_time_minutes   INTEGER,
    default_channels    VARCHAR(40) NOT NULL DEFAULT 'PUSH',
    updated_by          UUID REFERENCES account(id),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE notification_type IS '[communication] Dictionary of notification types and at the same time their configuration in the studio: whether to send and how far in advance, e.g. CLASS_REMINDER 120 min, PASS_EXPIRING 4320 min. Other tables point to the type with a foreign key.';
COMMENT ON COLUMN notification_type.code IS 'Readable type code used in the application (Java enum), e.g. CLASS_REMINDER; rows loaded by a migration.';

CREATE TABLE notification_template (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_type_id SMALLINT NOT NULL REFERENCES notification_type(id),
    channel             VARCHAR(10) NOT NULL,
    locale              VARCHAR(5)  NOT NULL DEFAULT 'pl',
    subject             VARCHAR(200),
    body_template       TEXT NOT NULL,
    updated_by          UUID REFERENCES account(id),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (notification_type_id, channel, locale)
);
COMMENT ON TABLE notification_template IS '[communication] Editable notification content templates.';
COMMENT ON COLUMN notification_template.locale IS 'Template language (pl, en). To be decided: with a single Polish studio it can be removed.';

CREATE TABLE notification_preference (
    account_id          UUID NOT NULL REFERENCES account(id),
    notification_type_id SMALLINT NOT NULL REFERENCES notification_type(id),
    channel             VARCHAR(10) NOT NULL,
    enabled             BOOLEAN NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, notification_type_id, channel)
);
COMMENT ON TABLE notification_preference IS '[communication] User notification preferences (transactional). Marketing consents are in documents.';
COMMENT ON COLUMN notification_preference.enabled IS 'Deviation from the default channels of the type: FALSE = the user switched a channel off, TRUE = switched on a channel outside the defaults. No row = default setting from notification_type.';

CREATE TABLE notification (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id        UUID NOT NULL REFERENCES account(id),
    notification_type_id SMALLINT NOT NULL REFERENCES notification_type(id),
    category            VARCHAR(15) NOT NULL DEFAULT 'TRANSACTIONAL',
    title               VARCHAR(200) NOT NULL,
    body                TEXT,
    payload             JSONB,
    announcement_id     UUID REFERENCES announcement(id),
    dedup_key           VARCHAR(200) UNIQUE,
    scheduled_for       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at             TIMESTAMPTZ
);
CREATE INDEX ix_notification_recipient ON notification (recipient_id, created_at);
COMMENT ON TABLE notification IS '[communication] Notification for a recipient (in-app inbox + delivery history). dedup_key protects scheduled jobs against duplicates, e.g. "CLASS_REMINDER:<reservation_id>".';

CREATE TABLE notification_delivery (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id     UUID NOT NULL REFERENCES notification(id),
    channel             VARCHAR(10) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    provider_message_id VARCHAR(150),
    error               TEXT,
    attempted_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE notification_delivery IS '[communication] Delivery attempt over a channel; a push failure does not undo a reservation and does not block the e-mail.';

CREATE TABLE device_token (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID NOT NULL REFERENCES account(id),
    token               VARCHAR(512) NOT NULL UNIQUE,
    platform            VARCHAR(10) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ
);
COMMENT ON TABLE device_token IS '[communication] FCM device tokens; one account can have several devices. Registration is an upsert by token: the same phone logged into another account moves the row. Logout or an FCM "UNREGISTERED" response sets revoked_at.';

-- ---------------------------------------------------------------------
-- INTEGRATIONS (module: integrations)
-- ---------------------------------------------------------------------
CREATE TABLE calendar_feed (
    account_id          UUID PRIMARY KEY REFERENCES account(id),
    token_hash          CHAR(64) NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at          TIMESTAMPTZ
);
COMMENT ON TABLE calendar_feed IS '[integrations] Private iCal link for a Google Calendar subscription (one-way). Simplest option; full two-way sync would need mapping tables.';

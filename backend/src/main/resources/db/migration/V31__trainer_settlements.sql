CREATE TABLE trainer_settlement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id UUID NOT NULL REFERENCES staff_profile(id),
    month DATE NOT NULL CHECK (EXTRACT(DAY FROM month) = 1),
    currency CHAR(3) NOT NULL DEFAULT 'PLN' CHECK (currency ~ '^[A-Z]{3}$'),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (staff_id, month)
);
CREATE INDEX ix_trainer_settlement_month ON trainer_settlement (month, staff_id);

CREATE TABLE trainer_settlement_revision (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_id UUID NOT NULL REFERENCES trainer_settlement(id),
    revision INTEGER NOT NULL CHECK (revision > 0),
    amount NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
    include_unconfirmed BOOLEAN NOT NULL,
    snapshot JSONB NOT NULL,
    source_fingerprint VARCHAR(64),
    reason VARCHAR(1000) NOT NULL,
    actor_id UUID NOT NULL REFERENCES account(id),
    actor_label VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (settlement_id, revision)
);

CREATE TABLE trainer_payout (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_id UUID NOT NULL REFERENCES trainer_settlement(id),
    amount NUMERIC(10,2) NOT NULL CHECK (amount > 0),
    paid_on DATE NOT NULL,
    method VARCHAR(16) NOT NULL CHECK (method IN ('TRANSFER','CASH','OTHER')),
    note VARCHAR(1000) NOT NULL,
    reverses_id UUID UNIQUE,
    actor_id UUID NOT NULL REFERENCES account(id),
    actor_label VARCHAR(320) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, settlement_id),
    FOREIGN KEY (reverses_id, settlement_id) REFERENCES trainer_payout(id, settlement_id),
    CHECK (reverses_id IS NULL OR reverses_id <> id)
);
CREATE INDEX ix_trainer_payout_settlement ON trainer_payout (settlement_id, created_at);

COMMENT ON TABLE trainer_settlement IS '[reporting] One monthly settlement per staff member; version serializes revisions and payout entries.';
COMMENT ON TABLE trainer_settlement_revision IS '[reporting] Immutable approvals and corrections: agreed amount, decision about unconfirmed hours, full schedule snapshot and author.';
COMMENT ON TABLE trainer_payout IS '[reporting] Manual payout ledger. A correction appends a reversal; original entries are never changed or deleted. No bank transfer is initiated.';
COMMENT ON COLUMN trainer_settlement_revision.id IS 'The application supplies the client operation UUID to safely retry an approval or correction.';
COMMENT ON COLUMN trainer_payout.id IS 'The application supplies the client operation UUID to safely retry recording a payout or reversal.';

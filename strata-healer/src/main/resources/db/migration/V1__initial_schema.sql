-- STRATA healer schema, version 1 (issue #12).
-- Written for PostgreSQL; the unit tests run it on H2 in PostgreSQL mode, so keep to
-- SQL both understand (no JSONB, no PL/pgSQL). Never edit a migration that has been
-- merged: add V2__..., V3__... instead.

-- One row per incident the healer opens from an alert (FR2).
CREATE TABLE incidents (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_name  VARCHAR(128) NOT NULL,
    service     VARCHAR(128) NOT NULL,
    container   VARCHAR(128),
    severity    VARCHAR(32),
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    opened_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT incidents_status_check CHECK (status IN ('OPEN', 'RESOLVED', 'ESCALATED'))
);

CREATE INDEX incidents_opened_at_idx ON incidents (opened_at);

-- One row per proposed action: what was proposed, whether the allow-list let it through,
-- its risk score and level, and how it ended (FR4-FR6, FR9).
CREATE TABLE decisions (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id       BIGINT       NOT NULL REFERENCES incidents (id),
    rule_id           VARCHAR(128) NOT NULL,
    action            VARCHAR(32)  NOT NULL,
    target            VARCHAR(128) NOT NULL,
    confidence        DOUBLE PRECISION NOT NULL,
    allowed           BOOLEAN      NOT NULL,
    rejection_reason  VARCHAR(512),
    risk_score        INTEGER,
    risk_factors      TEXT,
    autonomy_level    VARCHAR(16),
    outcome           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    decided_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT decisions_confidence_check CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT decisions_risk_score_check CHECK (risk_score IS NULL OR (risk_score >= 0 AND risk_score <= 100)),
    CONSTRAINT decisions_level_check CHECK (autonomy_level IS NULL
        OR autonomy_level IN ('L0_AUTO', 'L1_AUTO_UNDO', 'L2_APPROVAL', 'L3_HUMAN_ONLY')),
    CONSTRAINT decisions_outcome_check CHECK (outcome IN
        ('PENDING', 'REJECTED', 'AWAITING_APPROVAL', 'HANDED_TO_HUMAN', 'EXECUTED', 'RECOVERED', 'FAILED'))
);

CREATE INDEX decisions_incident_idx ON decisions (incident_id);

-- Each action type's success record, the input to earned autonomy (FR10).
CREATE TABLE action_trust (
    action      VARCHAR(32) PRIMARY KEY,
    successes   INTEGER     NOT NULL DEFAULT 0,
    failures    INTEGER     NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT action_trust_counts_check CHECK (successes >= 0 AND failures >= 0)
);

-- The tamper-evident audit log (FR11). Append-only: each entry stores the hash of the one
-- before it, and its own hash covers every other column, so changing, reordering or removing
-- an entry breaks the chain (see HashChain). seq is assigned by AuditLogService (1, 2, 3...),
-- not by the database, because it is part of the hashed content.
CREATE TABLE audit_log (
    seq          BIGINT PRIMARY KEY,
    recorded_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    incident_id  BIGINT,
    payload      TEXT         NOT NULL,
    prev_hash    VARCHAR(64)  NOT NULL,
    hash         VARCHAR(64)  NOT NULL,
    CONSTRAINT audit_log_seq_check CHECK (seq >= 1),
    CONSTRAINT audit_log_hash_unique UNIQUE (hash),
    CONSTRAINT audit_log_prev_hash_unique UNIQUE (prev_hash)
);

CREATE INDEX audit_log_incident_idx ON audit_log (incident_id);

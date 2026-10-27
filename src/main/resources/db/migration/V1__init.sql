CREATE TABLE accounts (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    reference     VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(200) NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uq_accounts_reference UNIQUE (reference),
    CONSTRAINT ck_accounts_status CHECK (status IN ('OPEN', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_accounts_currency CHECK (CHAR_LENGTH(currency) = 3)
);

CREATE TABLE positions (
    account_id     VARCHAR(36) NOT NULL PRIMARY KEY,
    currency       VARCHAR(3)  NOT NULL,
    balance_minor  BIGINT      NOT NULL,
    updated_at     TIMESTAMP   NOT NULL,
    CONSTRAINT fk_positions_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_positions_balance CHECK (balance_minor >= 0),
    CONSTRAINT ck_positions_currency CHECK (CHAR_LENGTH(currency) = 3)
);

CREATE TABLE settlement_instructions (
    id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
    idempotency_key    VARCHAR(200) NOT NULL,
    request_hash       VARCHAR(64)  NOT NULL,
    type               VARCHAR(16)  NOT NULL,
    debit_account_id   VARCHAR(36),
    credit_account_id  VARCHAR(36),
    currency           VARCHAR(3)   NOT NULL,
    amount_minor       BIGINT       NOT NULL,
    external_reference VARCHAR(128),
    status             VARCHAR(16)  NOT NULL,
    rejection_reason   VARCHAR(32),
    created_at         TIMESTAMP    NOT NULL,
    decided_at         TIMESTAMP    NOT NULL,
    CONSTRAINT uq_settlement_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_settlement_debit FOREIGN KEY (debit_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_settlement_credit FOREIGN KEY (credit_account_id) REFERENCES accounts (id),
    CONSTRAINT ck_settlement_amount CHECK (amount_minor > 0 AND amount_minor <= 1000000000000),
    CONSTRAINT ck_settlement_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT ck_settlement_status CHECK (status IN ('SETTLED', 'REJECTED')),
    CONSTRAINT ck_settlement_currency CHECK (CHAR_LENGTH(currency) = 3),
    CONSTRAINT ck_settlement_hash CHECK (CHAR_LENGTH(request_hash) = 64),
    CONSTRAINT ck_settlement_key CHECK (CHAR_LENGTH(idempotency_key) BETWEEN 1 AND 200),
    CONSTRAINT ck_settlement_reason CHECK (
        rejection_reason IS NULL
        OR rejection_reason IN ('INSUFFICIENT_FUNDS', 'ACCOUNT_NOT_OPEN', 'CURRENCY_MISMATCH', 'BALANCE_LIMIT')
    ),
    CONSTRAINT ck_settlement_outcome CHECK (
        (status = 'SETTLED' AND rejection_reason IS NULL)
        OR (status = 'REJECTED' AND rejection_reason IS NOT NULL)
    ),
    CONSTRAINT ck_settlement_shape CHECK (
        (type = 'DEPOSIT' AND debit_account_id IS NULL AND credit_account_id IS NOT NULL)
        OR (type = 'WITHDRAWAL' AND debit_account_id IS NOT NULL AND credit_account_id IS NULL)
        OR (
            type = 'TRANSFER'
            AND debit_account_id IS NOT NULL
            AND credit_account_id IS NOT NULL
            AND debit_account_id <> credit_account_id
        )
    )
);

CREATE TABLE settlement_status_events (
    instruction_id VARCHAR(36) NOT NULL,
    sequence_no    SMALLINT    NOT NULL,
    status         VARCHAR(16) NOT NULL,
    reason         VARCHAR(32),
    occurred_at    TIMESTAMP   NOT NULL,
    PRIMARY KEY (instruction_id, sequence_no),
    CONSTRAINT fk_status_event_instruction FOREIGN KEY (instruction_id) REFERENCES settlement_instructions (id),
    CONSTRAINT ck_status_event_seq CHECK (sequence_no IN (1, 2)),
    CONSTRAINT ck_status_event_status CHECK (status IN ('RECEIVED', 'SETTLED', 'REJECTED')),
    CONSTRAINT ck_status_event_reason CHECK (
        reason IS NULL
        OR reason IN ('INSUFFICIENT_FUNDS', 'ACCOUNT_NOT_OPEN', 'CURRENCY_MISMATCH', 'BALANCE_LIMIT')
    )
);

CREATE TABLE ledger_entries (
    id                         VARCHAR(36) NOT NULL PRIMARY KEY,
    settlement_instruction_id  VARCHAR(36) NOT NULL,
    account_id                 VARCHAR(36) NOT NULL,
    currency                   VARCHAR(3)  NOT NULL,
    direction                  VARCHAR(8)  NOT NULL,
    amount_minor               BIGINT      NOT NULL,
    created_at                 TIMESTAMP   NOT NULL,
    CONSTRAINT fk_ledger_settlement FOREIGN KEY (settlement_instruction_id) REFERENCES settlement_instructions (id),
    CONSTRAINT fk_ledger_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT ck_ledger_amount CHECK (amount_minor > 0 AND amount_minor <= 1000000000000),
    CONSTRAINT ck_ledger_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_ledger_currency CHECK (CHAR_LENGTH(currency) = 3),
    CONSTRAINT uq_ledger_leg UNIQUE (settlement_instruction_id, account_id, direction)
);

CREATE INDEX idx_ledger_account_created ON ledger_entries (account_id, created_at);

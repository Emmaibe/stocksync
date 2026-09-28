-- =====================================================================
-- Transactional email outbox, and password reset tokens.
--
-- Mail is not sent inline. The row that causes an email and the email itself
-- commit in the same transaction, and a separate dispatcher delivers it: an
-- invitation is never created without its mail, a mail is never sent for an
-- invitation that rolled back, and a flaky SMTP server cannot fail a request or
-- lose a message. The cost is that delivery is eventual, which is the right
-- trade for something a human reads minutes later.
-- =====================================================================

CREATE TABLE email_message (
    id              uuid         PRIMARY KEY,
    -- Nullable: password-reset mail is sent before any tenant is in context, and
    -- platform mail belongs to no tenant. Kept for traceability, not scoping.
    tenant_id       uuid         REFERENCES tenant (id),
    to_address      varchar(255) NOT NULL,
    subject         varchar(255) NOT NULL,
    body_text       text         NOT NULL,
    body_html       text,
    -- Which template produced this, for debugging and for metrics by kind.
    template        varchar(60)  NOT NULL,
    status          varchar(20)  NOT NULL DEFAULT 'PENDING',
    attempts        integer      NOT NULL DEFAULT 0,
    last_error      text,
    -- When the dispatcher may next try. Moves out on each failure (backoff).
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_email_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- The dispatcher's query: what is due, oldest first.
CREATE INDEX ix_email_due ON email_message (next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX ix_email_tenant ON email_message (tenant_id) WHERE tenant_id IS NOT NULL;

COMMENT ON TABLE email_message IS
    'Outbox. Rows are written in the transaction that caused them and delivered later by the dispatcher.';

-- ------------------------------------------------------- password reset
-- Same pattern as invitations: only the digest is stored, the raw token exists
-- once in an email, and redemption happens with no tenant bound — so the hash
-- must identify the user on its own and is unique platform-wide.
CREATE TABLE password_reset_token (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    user_id    uuid        NOT NULL,
    token_hash varchar(64) NOT NULL,
    used_at    timestamptz,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uk_password_reset_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES app_user (tenant_id, id)
);

CREATE INDEX ix_password_reset_user ON password_reset_token (tenant_id, user_id);
-- Housekeeping sweeps by expiry.
CREATE INDEX ix_password_reset_expiry ON password_reset_token (expires_at)
    WHERE used_at IS NULL;

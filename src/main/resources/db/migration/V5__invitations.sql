-- =====================================================================
-- Invitations.
--
-- A user is created by accepting an invitation, never by an admin typing a
-- password on someone else's behalf: the invitee sets their own credential and
-- nobody else ever knows it.
--
-- Only the SHA-256 of the token is stored. The raw token exists once, in the
-- response to the invite call (and later in an email); a leaked database gives
-- an attacker nothing they can redeem.
-- =====================================================================

CREATE TABLE invitation (
    id               uuid         PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    email            varchar(255) NOT NULL,
    role             varchar(20)  NOT NULL,
    -- SHA-256 hex of the single-use token. Unique platform-wide: a token is
    -- redeemed without naming a tenant, so it must identify one on its own.
    token_hash       varchar(64)  NOT NULL,
    status           varchar(20)  NOT NULL DEFAULT 'PENDING',
    invited_by       uuid,
    accepted_user_id uuid,
    accepted_at      timestamptz,
    expires_at       timestamptz  NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uk_invitation_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_invitation_inviter FOREIGN KEY (tenant_id, invited_by)
        REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_invitation_accepted_user FOREIGN KEY (tenant_id, accepted_user_id)
        REFERENCES app_user (tenant_id, id),
    CONSTRAINT ck_invitation_role   CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER', 'STAFF')),
    CONSTRAINT ck_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    -- Accepted invitations, and only those, record who accepted and when.
    CONSTRAINT ck_invitation_accepted CHECK (
        (status = 'ACCEPTED') = (accepted_user_id IS NOT NULL AND accepted_at IS NOT NULL)
    )
);

-- One live invitation per email per tenant; re-inviting means revoking first.
-- Accepted and revoked rows are kept as history and excluded here.
CREATE UNIQUE INDEX uk_invitation_pending
    ON invitation (tenant_id, lower(email)) WHERE status = 'PENDING';
CREATE INDEX ix_invitation_tenant ON invitation (tenant_id, status);
CREATE INDEX ix_invitation_expiry ON invitation (expires_at) WHERE status = 'PENDING';
CREATE INDEX ix_invitation_invited_by ON invitation (invited_by) WHERE invited_by IS NOT NULL;
CREATE INDEX ix_invitation_accepted_user
    ON invitation (accepted_user_id) WHERE accepted_user_id IS NOT NULL;

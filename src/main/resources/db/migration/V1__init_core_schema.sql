-- =====================================================================
-- StockSync V1 - core multi-tenant inventory schema
--
-- Conventions
--   * Every tenant-owned table carries tenant_id; isolation is enforced
--     in the application by a Hibernate filter, and structurally here by
--     composite foreign keys that include tenant_id (so a row can never
--     reference another tenant's row).
--   * stock_movement is an append-only ledger. stock_level is a derived
--     projection of it and is written ONLY by the movement module.
--   * Money and quantities are numeric(19,4). Timestamps are timestamptz.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------- tenant
CREATE TABLE tenant (
    id          uuid         PRIMARY KEY,
    slug        varchar(80)  NOT NULL,
    name        varchar(150) NOT NULL,
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    plan        varchar(30)  NOT NULL DEFAULT 'FREE',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uk_tenant_slug   UNIQUE (slug),
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_tenant_plan   CHECK (plan   IN ('FREE', 'STARTER', 'GROWTH', 'ENTERPRISE')),
    CONSTRAINT ck_tenant_slug   CHECK (slug ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$')
);

COMMENT ON TABLE tenant IS 'One row per business using the platform. Root of every tenant_id chain.';

-- -------------------------------------------------------------- app_user
CREATE TABLE app_user (
    id             uuid         PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    email          varchar(255) NOT NULL,
    password_hash  varchar(100) NOT NULL,
    full_name      varchar(150) NOT NULL,
    role           varchar(20)  NOT NULL,
    status         varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at  timestamptz,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    version        bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_app_user_role   CHECK (role IN ('OWNER', 'ADMIN', 'MANAGER', 'STAFF')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'INVITED', 'DISABLED'))
);

-- Email is unique per tenant, case-insensitively. The same person may hold
-- accounts in several tenants.
CREATE UNIQUE INDEX uk_app_user_tenant_email ON app_user (tenant_id, lower(email));
CREATE INDEX ix_app_user_tenant ON app_user (tenant_id);

-- A composite unique key so child tables can point at (tenant_id, id) pairs.
ALTER TABLE app_user ADD CONSTRAINT uk_app_user_tenant_id UNIQUE (tenant_id, id);

-- -------------------------------------------------------------- location
CREATE TABLE location (
    id          uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    code        varchar(40)  NOT NULL,
    name        varchar(150) NOT NULL,
    type        varchar(20)  NOT NULL DEFAULT 'WAREHOUSE',
    address     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    active      boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uk_location_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uk_location_tenant_id   UNIQUE (tenant_id, id),
    CONSTRAINT ck_location_type        CHECK (type IN ('WAREHOUSE', 'STORE', 'VEHICLE', 'VIRTUAL'))
);

CREATE INDEX ix_location_tenant ON location (tenant_id);

-- ------------------------------------------------------------------ item
CREATE TABLE item (
    id             uuid          PRIMARY KEY,
    tenant_id      uuid          NOT NULL REFERENCES tenant (id),
    sku            varchar(64)   NOT NULL,
    barcode        varchar(64),
    name           varchar(200)  NOT NULL,
    description    text,
    category       varchar(100),
    unit           varchar(20)   NOT NULL DEFAULT 'EACH',
    cost_price     numeric(19,4),
    sale_price     numeric(19,4),
    reorder_point  numeric(19,4),
    tracked        boolean       NOT NULL DEFAULT true,
    active         boolean       NOT NULL DEFAULT true,
    -- Tenant-defined custom fields. Kept in JSONB so tenants can extend the
    -- item record without a migration.
    attributes     jsonb         NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    version        bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uk_item_tenant_sku  UNIQUE (tenant_id, sku),
    CONSTRAINT uk_item_tenant_id   UNIQUE (tenant_id, id),
    CONSTRAINT ck_item_unit        CHECK (unit IN ('EACH', 'BOX', 'PACK', 'KG', 'GRAM', 'LITRE', 'MILLILITRE', 'METRE')),
    CONSTRAINT ck_item_cost_price  CHECK (cost_price    IS NULL OR cost_price    >= 0),
    CONSTRAINT ck_item_sale_price  CHECK (sale_price    IS NULL OR sale_price    >= 0),
    CONSTRAINT ck_item_reorder     CHECK (reorder_point IS NULL OR reorder_point >= 0)
);

CREATE INDEX ix_item_tenant ON item (tenant_id);
-- Barcodes are optional but unique per tenant when present.
CREATE UNIQUE INDEX uk_item_tenant_barcode ON item (tenant_id, barcode) WHERE barcode IS NOT NULL;
CREATE INDEX ix_item_tenant_category ON item (tenant_id, category) WHERE category IS NOT NULL;
CREATE INDEX ix_item_attributes ON item USING gin (attributes jsonb_path_ops);

-- -------------------------------------------------------- stock_movement
-- Append-only ledger. The single source of truth for stock. Rows are never
-- updated or deleted; a mistake is corrected with a compensating movement.
CREATE TABLE stock_movement (
    id                uuid          PRIMARY KEY,
    tenant_id         uuid          NOT NULL REFERENCES tenant (id),
    item_id           uuid          NOT NULL,
    location_id       uuid          NOT NULL,
    movement_type     varchar(20)   NOT NULL,
    -- Signed delta applied to the location's on-hand quantity. Sign is
    -- constrained per movement type below, so on-hand is simply the sum.
    quantity          numeric(19,4) NOT NULL,
    unit_cost         numeric(19,4),
    reason            varchar(255),
    -- External document this movement came from (invoice, PO, receipt no.).
    reference         varchar(120),
    -- Pairs the TRANSFER_OUT and TRANSFER_IN legs of one transfer.
    transfer_group_id uuid,
    -- Per-tenant client-supplied key that makes a retried write a no-op.
    idempotency_key   varchar(120),
    occurred_at       timestamptz   NOT NULL,
    recorded_at       timestamptz   NOT NULL DEFAULT now(),
    created_by        uuid,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_movement_item     FOREIGN KEY (tenant_id, item_id)     REFERENCES item     (tenant_id, id),
    CONSTRAINT fk_movement_location FOREIGN KEY (tenant_id, location_id) REFERENCES location (tenant_id, id),
    CONSTRAINT fk_movement_user     FOREIGN KEY (tenant_id, created_by)  REFERENCES app_user (tenant_id, id),
    CONSTRAINT ck_movement_type CHECK (
        movement_type IN ('RECEIPT', 'SALE', 'ADJUSTMENT', 'TRANSFER_IN', 'TRANSFER_OUT')
    ),
    CONSTRAINT ck_movement_quantity_sign CHECK (
        quantity <> 0
        AND (movement_type NOT IN ('RECEIPT', 'TRANSFER_IN')  OR quantity > 0)
        AND (movement_type NOT IN ('SALE', 'TRANSFER_OUT')    OR quantity < 0)
    ),
    CONSTRAINT ck_movement_unit_cost CHECK (unit_cost IS NULL OR unit_cost >= 0),
    -- A transfer leg is meaningless without its pair.
    CONSTRAINT ck_movement_transfer_group CHECK (
        (movement_type IN ('TRANSFER_IN', 'TRANSFER_OUT')) = (transfer_group_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_movement_tenant_idempotency
    ON stock_movement (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
-- Drives per-item/location history and stock_level rebuilds.
CREATE INDEX ix_movement_item_location ON stock_movement (tenant_id, item_id, location_id, occurred_at DESC);
CREATE INDEX ix_movement_tenant_time   ON stock_movement (tenant_id, occurred_at DESC);
CREATE INDEX ix_movement_transfer      ON stock_movement (transfer_group_id) WHERE transfer_group_id IS NOT NULL;
CREATE INDEX ix_movement_reference     ON stock_movement (tenant_id, reference) WHERE reference IS NOT NULL;

-- ----------------------------------------------------------- stock_level
-- Derived projection of stock_movement, kept for fast reads. Written only by
-- the movement module, and always rebuildable from the ledger.
CREATE TABLE stock_level (
    id                 uuid          PRIMARY KEY,
    tenant_id          uuid          NOT NULL REFERENCES tenant (id),
    item_id            uuid          NOT NULL,
    location_id        uuid          NOT NULL,
    quantity_on_hand   numeric(19,4) NOT NULL DEFAULT 0,
    quantity_reserved  numeric(19,4) NOT NULL DEFAULT 0,
    last_movement_id   uuid,
    last_movement_at   timestamptz,
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    version            bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uk_stock_level_slot   UNIQUE (tenant_id, item_id, location_id),
    CONSTRAINT fk_stock_level_item     FOREIGN KEY (tenant_id, item_id)     REFERENCES item     (tenant_id, id),
    CONSTRAINT fk_stock_level_location FOREIGN KEY (tenant_id, location_id) REFERENCES location (tenant_id, id),
    CONSTRAINT fk_stock_level_movement FOREIGN KEY (last_movement_id)       REFERENCES stock_movement (id),
    CONSTRAINT ck_stock_level_reserved CHECK (quantity_reserved >= 0)
);

CREATE INDEX ix_stock_level_tenant_item ON stock_level (tenant_id, item_id);
CREATE INDEX ix_stock_level_location    ON stock_level (tenant_id, location_id);

-- ------------------------------------------------- idempotency_record
-- HTTP-layer replay cache for mobile clients on flaky connections: a repeated
-- request with the same key returns the stored response instead of re-running.
CREATE TABLE idempotency_record (
    id               uuid         PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    scope            varchar(60)  NOT NULL,
    idempotency_key  varchar(120) NOT NULL,
    request_hash     varchar(64)  NOT NULL,
    status           varchar(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    response_status  integer,
    response_body    jsonb,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    expires_at       timestamptz  NOT NULL,
    version          bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uk_idempotency_key    UNIQUE (tenant_id, scope, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_idempotency_expiry ON idempotency_record (expires_at);

-- =====================================================================
-- Stocktake: counting what is actually on the shelf, and correcting the
-- ledger to match.
--
-- Two tables, and one rule that shapes both: a count NEVER writes stock.
-- Posting a count appends ordinary ADJUSTMENT movements through the same
-- service as everything else, so the ledger stays the single account of how
-- stock reached its current figure. A stocktake that quietly overwrote
-- stock_level would leave an unexplained jump no report could account for.
--
-- Separation of duties is deliberate: counting needs stock.count, which STAFF
-- holds, and posting needs stock.count.post, which starts at MANAGER. The
-- person who counted does not approve their own correction.
-- =====================================================================

-- The ledger never needed a composite key until now: nothing referenced a
-- movement. A count line does, so that a correction can be traced back to the
-- count that caused it, and the reference must be tenant-scoped like every
-- other one here.
ALTER TABLE stock_movement
    ADD CONSTRAINT uk_stock_movement_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE stock_count (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    location_id   uuid        NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'OPEN',
    reference     varchar(120),
    note          text,
    opened_by     uuid,
    -- Set together when the count is posted, and never afterwards.
    posted_by     uuid,
    posted_at     timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0,

    CONSTRAINT fk_stock_count_location FOREIGN KEY (tenant_id, location_id)
        REFERENCES location (tenant_id, id),
    CONSTRAINT fk_stock_count_opened_by FOREIGN KEY (tenant_id, opened_by)
        REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_stock_count_posted_by FOREIGN KEY (tenant_id, posted_by)
        REFERENCES app_user (tenant_id, id),
    CONSTRAINT ck_stock_count_status CHECK (status IN ('OPEN', 'POSTED', 'CANCELLED')),
    -- A posted count has both, an unposted count has neither. Without this a
    -- half-written posting could leave a count that claims to be posted with
    -- nobody accountable for it.
    CONSTRAINT ck_stock_count_posted CHECK (
        (status = 'POSTED') = (posted_by IS NOT NULL AND posted_at IS NOT NULL)
    ),
    -- Composite key so the lines can carry a tenant-scoped foreign key.
    CONSTRAINT uk_stock_count_tenant_id UNIQUE (tenant_id, id)
);

-- Only one count may be open per location at a time. Two open counts would
-- each measure a shelf the other is also changing, and whichever posted second
-- would overwrite the first with figures gathered before it.
CREATE UNIQUE INDEX uk_stock_count_one_open_per_location
    ON stock_count (tenant_id, location_id)
    WHERE status = 'OPEN';

CREATE INDEX ix_stock_count_tenant_status ON stock_count (tenant_id, status);

CREATE TABLE stock_count_line (
    id              uuid           PRIMARY KEY,
    tenant_id       uuid           NOT NULL REFERENCES tenant (id),
    stock_count_id  uuid           NOT NULL,
    item_id         uuid           NOT NULL,

    -- What the system believed when the line was created. Kept for the report,
    -- never used to compute the correction: see system_quantity.
    expected_quantity numeric(19, 4) NOT NULL DEFAULT 0,

    -- What a person actually counted. NULL means not counted yet, which is not
    -- the same as counted zero — an uncounted line is skipped when posting,
    -- while a zero genuinely empties the shelf.
    counted_quantity  numeric(19, 4),

    -- What the system believed at the moment of posting, written then. If it
    -- differs from expected_quantity, stock moved during the count and the
    -- difference is visible rather than silently absorbed.
    system_quantity   numeric(19, 4),

    -- The movement this line produced, so a correction can be traced back to
    -- the count that caused it.
    movement_id       uuid,
    note              varchar(255),
    created_at        timestamptz    NOT NULL DEFAULT now(),
    updated_at        timestamptz    NOT NULL DEFAULT now(),
    version           bigint         NOT NULL DEFAULT 0,

    CONSTRAINT fk_stock_count_line_count FOREIGN KEY (tenant_id, stock_count_id)
        REFERENCES stock_count (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_stock_count_line_item FOREIGN KEY (tenant_id, item_id)
        REFERENCES item (tenant_id, id),
    CONSTRAINT fk_stock_count_line_movement FOREIGN KEY (tenant_id, movement_id)
        REFERENCES stock_movement (tenant_id, id),
    -- One line per item per count: a second line for the same item would be two
    -- answers to the same question.
    CONSTRAINT uk_stock_count_line_item UNIQUE (stock_count_id, item_id),
    CONSTRAINT ck_stock_count_line_counted CHECK (counted_quantity IS NULL OR counted_quantity >= 0)
);

CREATE INDEX ix_stock_count_line_count ON stock_count_line (stock_count_id);
CREATE INDEX ix_stock_count_line_item ON stock_count_line (tenant_id, item_id);

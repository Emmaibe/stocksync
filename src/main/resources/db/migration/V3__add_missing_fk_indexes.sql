-- =====================================================================
-- Indexes for foreign keys that had none.
--
-- PostgreSQL indexes the referencing side of a foreign key only if asked.
-- Without these, checking whether a parent row is still referenced means a
-- sequential scan of the child table: deleting or deactivating a user would
-- scan the whole ledger, and joining a stock level to its last movement would
-- do the same.
-- =====================================================================

CREATE INDEX ix_movement_created_by ON stock_movement (created_by)
    WHERE created_by IS NOT NULL;

CREATE INDEX ix_stock_level_last_movement ON stock_level (last_movement_id)
    WHERE last_movement_id IS NOT NULL;

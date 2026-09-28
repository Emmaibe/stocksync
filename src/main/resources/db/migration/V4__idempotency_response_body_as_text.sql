-- =====================================================================
-- Store the cached response as text, not jsonb.
--
-- jsonb parses and re-renders what it stores: key order and whitespace are not
-- preserved. That is right for data you query and wrong for a replay cache,
-- whose whole promise is that a retry gets back exactly what the first call
-- returned — byte for byte, so a client that compares, hashes or signs the
-- response is not surprised by a body that differs from the one it missed.
--
-- Nothing queries inside this column; it is opaque payload.
-- =====================================================================

ALTER TABLE idempotency_record
    ALTER COLUMN response_body TYPE text USING response_body::text;

COMMENT ON COLUMN idempotency_record.response_body IS
    'Verbatim response body of the original call, replayed unchanged. Opaque text so bytes are preserved exactly.';

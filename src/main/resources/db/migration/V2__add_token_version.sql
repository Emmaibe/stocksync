-- =====================================================================
-- Token revocation support.
--
-- Access tokens are self-contained and cannot be withdrawn once issued, so
-- revocation happens at refresh time instead: every token carries the user's
-- token_version, and a refresh whose version is behind the stored one is
-- refused. Bumping this column therefore ends every session for that user at
-- the next refresh, without a revocation table to grow and prune.
--
-- The window this leaves open is the access-token lifetime (15 minutes).
-- Closing it entirely would mean a database read on every request.
-- =====================================================================

ALTER TABLE app_user
    ADD COLUMN token_version bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN app_user.token_version IS
    'Incremented on logout, password change or forced sign-out; tokens carrying an older value are refused at refresh.';

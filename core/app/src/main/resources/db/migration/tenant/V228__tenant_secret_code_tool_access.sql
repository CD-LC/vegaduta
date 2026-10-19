-- Opt-in flag: may this secret be handed to tenant-authored sandbox code?
--
-- Closes a real hole found in security review of the vault (2026-09-12).
--
-- The vault's central promise is that it is WRITE-ONLY: no endpoint returns a
-- stored value, so a stolen admin session cannot recover credentials. Custom
-- code tools broke that promise, because a workspace admin could:
--
--   1. grant a secret to a code tool  (PUT /api/tools/code/{id}/secrets)
--   2. write a program that returns it transformed - codepoints, reversed,
--      hex, chunked into a list
--   3. call POST /api/tools/code/{id}/test and read the value out of the 200
--
-- Redaction does not stop step 3 and never could: it matches known values
-- literally, so any transformation the program applies walks straight through.
-- Once tenant code holds plaintext, tenant code can return it. That is inherent
-- to letting custom code use credentials at all, so the fix is not to try to
-- contain the value afterwards - it is to make reachability an explicit,
-- per-secret decision instead of a side effect of holding the admin role.
--
-- DEFAULT FALSE is the whole point. Every secret that exists today, and every
-- one stored without thinking about this, is unreachable from sandboxed code.
-- A Stripe key used only by an MCP server never becomes readable, even by an
-- admin. Opting one in is a deliberate act that says "I accept that anyone who
-- can author a code tool here can read this value".
--
-- Version V228: flyway.locations merges controlplane/ and tenant/ into ONE
-- shared sequence, so this must be free across BOTH folders. V225, V226 and
-- V227 are taken (two by other sessions, one by this work), so V228 is next.
-- Renumber on collision - scripts/ci/flyway-lint.sh is the gate.

ALTER TABLE tenant_secret
    ADD COLUMN code_tool_access BOOLEAN NOT NULL DEFAULT FALSE;

-- The resolver asks "is this name granted AND code-tool-readable" on every
-- sandboxed execution that declares secrets. Narrow partial index: the TRUE
-- rows are expected to be a small minority of the table, which is exactly the
-- shape a partial index is for.
CREATE INDEX idx_tenant_secret_code_tool_access
    ON tenant_secret (name) WHERE code_tool_access;

COMMENT ON COLUMN tenant_secret.code_tool_access IS
    'Opt-in: may tenant-authored sandbox code receive this value. Anyone who can author a code '
    'tool in this workspace can read any secret with this set, because a program holding a '
    'plaintext value can always return it. Default false.';

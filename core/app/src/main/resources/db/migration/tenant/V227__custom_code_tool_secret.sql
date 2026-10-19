-- Which vault secrets a custom code tool is allowed to receive.
--
-- Declarative rather than inferred. A tool does NOT get to ask for a secret by
-- name at runtime: it declares up front which ones it needs, the names are
-- resolved before the sandbox process starts, and only those values are handed
-- to it. The alternative - letting tenant code call something like
-- vault.get(name) during execution - would mean arbitrary tenant-authored code
-- choosing what to read from the vault, which is the whole thing this design
-- avoids. Declaring also makes "what can this tool reach" answerable by reading
-- the row instead of reading the program.
--
-- Deliberately NOT storing the secret itself, or a copy, or an encrypted
-- duplicate. This table holds names only; the values live in tenant_secret
-- (V222) and are resolved per execution so a rotation takes effect on the very
-- next run with no change here.
--
-- Only TENANT-scoped secrets ever resolve through this path - see
-- CustomCodeToolService#resolveSecretsFor for why a tenant-authored program
-- must not be able to act with another member's personal credential.
--
-- Version V227: flyway.locations merges controlplane/ and tenant/ into ONE
-- shared sequence, so a V-number must be free across BOTH folders.
--
-- This file was originally authored as V224 and renumbered: scripts/ci/flyway-lint.sh
-- caught a collision with tenant/V224__tool_classification.sql, which another
-- session committed while this work was in progress. V225 (controlplane,
-- free_plan_whatsapp) and V226 (controlplane, industry_template_expansion)
-- were likewise taken in the meantime, so V227 is the next actually-free
-- number. A duplicate here is not two independent migrations - it is a hard
-- core boot failure, which is why that lint exists.
--
-- Renumbering THIS file rather than the other was the correct direction: the
-- other V224 is already committed (i.e. has shipped), and renumbering an
-- applied migration makes Flyway treat it as unapplied and run it twice.

CREATE TABLE custom_code_tool_secret (
    tool_id     UUID         NOT NULL REFERENCES custom_code_tool (id) ON DELETE CASCADE,
    secret_name VARCHAR(64)  NOT NULL,
    PRIMARY KEY (tool_id, secret_name)
);

-- The execution path reads "every secret name for this tool" on every run, so
-- the PK's leading column already serves it. This index exists for the other
-- direction: "which tools would break if I revoke STRIPE_KEY", which is the
-- question a person actually asks before rotating something.
CREATE INDEX idx_custom_code_tool_secret_name ON custom_code_tool_secret (secret_name);

-- Per-secret destination allow-list (security review SEC-006).
--
-- http_request and the workflow HTTP node expand {{secret.NAME}} /
-- {{secret:NAME}} into outbound headers, query values and bodies. The URL is
-- chosen by an LLM (or templated from untrusted workflow input), so without a
-- destination check a prompt injection could send any vault value to any host.
--
-- allowed_hosts holds comma-separated host patterns ("api.example.com" or
-- "*.example.com"; validated and normalised by SecretHostPolicy). A secret is
-- substituted into a URL-bound request ONLY when the destination host matches.
-- NULL / empty = never resolved for a URL-bound carrier (fail closed). Carriers
-- that are not URL-bound (MCP server env, integrations) are unaffected.
--
-- VERSION NUMBER: V365.4 - OSS-local dotted minor version, one shared
-- namespace across controlplane/ and tenant/. Additive only.
ALTER TABLE tenant_secret ADD COLUMN IF NOT EXISTS allowed_hosts TEXT;

COMMENT ON COLUMN tenant_secret.allowed_hosts IS
    'Comma-separated destination host patterns (exact host or *.suffix) this secret may be sent to by '
    'http_request / workflow HTTP nodes. Empty = never sent to any URL-bound destination.';

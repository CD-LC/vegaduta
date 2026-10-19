-- Master Roadmap 2026 item #14 (E2B code execution completion, §B).
-- Tenant-supplied E2B template ID for the "code-runner" template they build
-- and push themselves (see core/sdlc/e2b-template/README.md) - E2B's own
-- envd Process/Filesystem services are undocumented Connect-RPC (no public
-- JSON contract, no Java/Kotlin SDK - see E2bSandboxClient's javadoc), so
-- rather than guess at that wire format, code execution is done through a
-- tenant-owned template exposing a small, fully-documented HTTP contract we
-- control end to end. Nullable: a tenant can have an active E2B credential
-- (manual sandbox lifecycle control) without ever configuring this.
ALTER TABLE e2b_credential ADD COLUMN code_execution_template_id VARCHAR(64);

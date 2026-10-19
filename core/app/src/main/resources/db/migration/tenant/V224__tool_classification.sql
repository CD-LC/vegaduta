-- First-class tool classification registry: trust x sensitivity x determinism,
-- non-nullable, failing closed.
--
-- WHY THIS TABLE EXISTS
-- Every enforcement mechanism this platform is about to build needs to know what
-- a tool *is*: an irreversible-action confirmation gate, taint propagation in
-- GuardedToolCallback, per-tool approval policy, progressive tool disclosure, and
-- durable-run replay. Today that knowledge is implicit and scattered -
-- ApprovalPolicyService holds a handful of action-name globs ("channel.*.send",
-- "*.delete", "codeintel.workspace.run"), AgentToolProvider knows which
-- credential each tool needs, and NOTHING anywhere records whether repeating a
-- call is safe. Five features would each re-derive the same facts, differently,
-- and disagree under pressure. This table plus
-- core/agents/.../tools/classification/BuiltInToolClassifications.java is the
-- single declared answer.
--
-- FAILING CLOSED IS THE ENTIRE DESIGN
-- There is no "unclassified" row and no default classification. A tool with no
-- usable declaration resolves to BLOCKED (see ToolClassificationDecision), not to
-- a permissive default. That is why every classification column is NOT NULL: a
-- half-filled row must be impossible, because the fail-open direction of a
-- missing trust tier is "this untrusted content is trusted".
--
-- SCHEMA-PER-TENANT: no tenant_id column - this table only ever exists inside a
-- tenant's own schema, same as every other tenant-scoped table here (see
-- V52__llm_call_log.sql). The build agenda's sketch wrote (tool_id, tenant_id,
-- ...) generically; in this codebase the tenant dimension is the schema.
--
-- Version V224: flyway.locations merges controlplane/ and tenant/ into ONE shared
-- version sequence (application.yml). V222 (tenant, tenant_secret) and V223
-- (controlplane, real_world_action_agent_templates) were both taken by other
-- sessions on 2026-09-11, so V224 is the next actually-free number across BOTH
-- folders - checked both, not just tenant/.

CREATE TABLE tool_classification (
    id                 UUID PRIMARY KEY,

    -- Either an exact tool name as ToolDefinition.name() reports it, or a
    -- pattern containing a single '*' wildcard.
    --
    -- MCP-sourced tools MUST be classified under their namespaced name
    -- (mcp__<server>__<tool>, produced by NamespacedMcpToolCallback), never the
    -- bare upstream name - the namespaced form is what the platform dispatches
    -- and gates on, so a row keyed to the bare name would classify nothing and
    -- the tool would stay blocked for a reason nobody could find.
    --
    -- Patterns are recognised by simply containing '*', with no separate
    -- is_pattern flag: '*' cannot occur in a real tool name (first-party names
    -- are [a-z0-9_]+, MCP names are namespaced and control-char-stripped), and a
    -- redundant flag could contradict the string, leaving "is this a pattern"
    -- with two answers. Examples: 'mcp__github__*' classifies one MCP server's
    -- whole surface; 'ask_agent_*' covers the agent-delegation tools, whose
    -- concrete names are derived from tenant-chosen agent names.
    tool_name          VARCHAR(255) NOT NULL,

    -- Where the tool's code and returned bytes come from. Determines whether its
    -- OUTPUT taints the conversation.
    --   TRUSTED_INTERNAL    first-party code over first-party state; no attacker
    --                       can influence a byte of the result
    --   TENANT_SCOPED       first-party code over the tenant's own state, authored
    --                       by authenticated members of the workspace
    --   UNTRUSTED_EXTERNAL  code or content from outside the boundary - web fetch,
    --                       third-party MCP server, tenant custom code, an inbound
    --                       mailbox, OR retrieval over a corpus containing any of
    --                       those (taint must survive storage, which is the case
    --                       every implementation of this pattern misses first)
    trust              VARCHAR(32)  NOT NULL,

    -- What the tool can reach or reveal. Determines whether it is a taint SINK.
    -- Classify at the most sensitive REACHABLE action, always: this table is keyed
    -- by tool name and the resolver never sees the arguments, so google_gmail
    -- (action = list|read|send on one name) is EGRESS, not READ_TENANT.
    --   READ_PUBLIC   reveals only already-public information
    --   READ_TENANT   reads tenant-private data into the model's context
    --   WRITE_TENANT  mutates tenant state (a sink: the persistence channel - an
    --                 injected instruction written to memory outlives the turn)
    --   CREDENTIALED  acts with a stored credential, i.e. with the tenant's own
    --                 authority; nothing has to be broken for this to do damage
    --   EGRESS        moves bytes to a destination the CALLER names (a sent
    --                 message, an upload, a webhook) - the terminal step of every
    --                 realistic exfiltration chain
    sensitivity        VARCHAR(32)  NOT NULL,

    -- Whether repeating the call is safe - what makes retry, resume and replay
    -- decidable instead of guessed.
    --   PURE         deterministic AND effect-free. A very high bar: a clock read,
    --                a model call and a read of mutable state all FAIL it, which is
    --                why no first-party tool is declared PURE today.
    --   SIDE_EFFECT  repeating is recoverable - a non-deterministic read, or an
    --                effect this platform can absorb. Needs an idempotency key.
    --   DURABLE      the effect is externally visible and cannot be absorbed (a
    --                message sent, money moved, a shell command run). A replay MUST
    --                return the journaled response and must not reach the provider.
    determinism        VARCHAR(32)  NOT NULL,

    -- ACTIVE or QUARANTINED. A QUARANTINED row blocks the tool regardless of every
    -- other column - the state an MCP-discovered tool lands in when its server's
    -- tool manifest changes after approval (a renamed tool, or a rewritten
    -- DESCRIPTION, which is prompt content and therefore a live injection vector),
    -- and the state a platform admin uses after an incident. Deliberately not a
    -- boolean 'enabled': quarantined and unclassified are both blocked but are not
    -- the same problem, and the console has to say which.
    status             VARCHAR(32)  NOT NULL,

    -- Narrowest scope/permission string the caller must hold, or NULL. Free-form
    -- on purpose: the honest value is whatever the upstream system calls it (an
    -- OAuth scope URL, a platform role, an entitlement key), and forcing those
    -- into one enum would mean inventing a mapping that then has to be kept true.
    required_scope     VARCHAR(255),

    -- TRUE when a successful call cannot be undone BY THIS PLATFORM. Separate from
    -- determinism because "creates a workspace" and "deletes a workspace" are both
    -- DURABLE and are nothing alike. This flag, combined with a consequential
    -- sensitivity, is what drives ToolClassification.requiresHumanConfirmation().
    irreversible       BOOLEAN      NOT NULL DEFAULT FALSE,

    -- TRUE when the tool cannot execute correctly without Spring AI's
    -- model-invisible ToolContext carrying tenant/user identity. Declared so a
    -- wiring check can REFUSE to bind an identity-dependent tool into a path with
    -- no context (a background sweeper, an inbound A2A call, an edge execution)
    -- instead of discovering it later as a null-tenant failure. Identity is never
    -- a tool argument in this codebase; ToolContext is the channel.
    needs_tool_context BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Provenance of the ROW (not of the tool's data - that is `trust`). Lets the
    -- console distinguish "the platform shipped this opinion" from "your admin
    -- overrode it", and lets a future re-seed replace BUILT_IN_SEED rows without
    -- touching anything a human deliberately set.
    source             VARCHAR(32)  NOT NULL,

    -- Why this classification. "Why is this EGRESS" is the question an auditor
    -- asks, and a row with no answer is a row nobody can defend.
    notes              VARCHAR(1024),

    created_by         VARCHAR(255) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,

    -- Named so the atomic INSERT ... ON CONFLICT (tool_name) upsert in
    -- ToolClassificationRepository has a constraint to target. A read-then-write
    -- upsert would race: two agents in one tenant connecting to the same MCP
    -- server in the same second both declare the same brand-new tool name, and
    -- the loser dies on this constraint inside an already-aborted transaction.
    CONSTRAINT uq_tool_classification_tool_name UNIQUE (tool_name),

    -- The enum values are enforced at the DATABASE, not only in Java. A row is a
    -- security decision, and hand-written SQL, a data fix or a bad import must not
    -- be able to introduce a value the application will then have to interpret.
    -- (The application still fails closed on an unrecognised value - see
    -- ToolClassificationEntity's javadoc on why those columns are read as raw
    -- strings rather than @Enumerated - but that is the second line of defence,
    -- not the first.)
    CONSTRAINT chk_tool_classification_trust
        CHECK (trust IN ('TRUSTED_INTERNAL', 'TENANT_SCOPED', 'UNTRUSTED_EXTERNAL')),
    CONSTRAINT chk_tool_classification_sensitivity
        CHECK (sensitivity IN ('READ_PUBLIC', 'READ_TENANT', 'WRITE_TENANT', 'CREDENTIALED', 'EGRESS')),
    CONSTRAINT chk_tool_classification_determinism
        CHECK (determinism IN ('PURE', 'SIDE_EFFECT', 'DURABLE')),
    CONSTRAINT chk_tool_classification_status
        CHECK (status IN ('ACTIVE', 'QUARANTINED')),
    CONSTRAINT chk_tool_classification_source
        CHECK (source IN ('BUILT_IN_SEED', 'PLATFORM_ADMIN', 'TENANT_DECLARED', 'MCP_IMPORT'))
);

-- The unique constraint above already indexes exact-name lookup, which is the hot
-- path (one resolution per tool per turn). This partial index serves the second
-- read shape: "all the wildcard rows", scanned once per resolution batch. Partial
-- so it stays tiny - a tenant has a handful of patterns and potentially hundreds
-- of exact rows.
CREATE INDEX idx_tool_classification_patterns
    ON tool_classification (tool_name) WHERE tool_name LIKE '%*%';

-- Answers the post-incident question this registry was built for - "which
-- CREDENTIALED or EGRESS tools does this tenant have classified" - and backs the
-- console's risk-badge view without a full scan.
CREATE INDEX idx_tool_classification_sensitivity
    ON tool_classification (sensitivity, status);

-- ---------------------------------------------------------------------------
-- Seed: the obviously-dangerous first-party tools.
--
-- This is deliberately a PARTIAL seed, not a mirror of
-- BuiltInToolClassifications. That Java class is the authoritative declaration
-- for first-party tools (a tool's classification is a property of its code and
-- belongs in the same commit), and resolution consults these rows FIRST, so every
-- row seeded here is a row that can drift away from the code. The rows below earn
-- that cost because they are the ones a security reviewer, an auditor or an
-- incident responder goes looking for, and "it is in a Java file somewhere" is not
-- an acceptable answer to "show me every irreversible tool in this tenant".
-- ToolClassificationRegistry.driftFromBuiltInDeclarations() reports any divergence.
--
-- Selection rule for this seed: every tool declared irreversible, plus the two
-- primary taint SOURCES (whose classification is the most surprising and the most
-- load-bearing). Everything else resolves from the Java declarations.
--
-- ids are derived with md5(...)::uuid rather than gen_random_uuid() because
-- pgcrypto/gen_random_uuid is not guaranteed available on every deployment (see
-- V145's migration comment), and deterministic ids make this seed idempotent to
-- re-derive if it ever has to be compared across tenants.
-- ---------------------------------------------------------------------------

INSERT INTO tool_classification (
    id, tool_name, trust, sensitivity, determinism, status,
    required_scope, irreversible, needs_tool_context, source, notes,
    created_by, created_at, updated_at)
SELECT
    md5('tool_classification:' || v.tool_name)::uuid,
    v.tool_name, v.trust, v.sensitivity, v.determinism, 'ACTIVE',
    v.required_scope, v.irreversible, TRUE, 'BUILT_IN_SEED', v.notes,
    'platform:built-in', now(), now()
FROM (VALUES
    -- ---- taint sources -----------------------------------------------------
    ('fetch_web_page', 'UNTRUSTED_EXTERNAL', 'READ_PUBLIC', 'SIDE_EFFECT', NULL, FALSE,
     'The canonical taint source. READ_PUBLIC and UNTRUSTED_EXTERNAL at once is not a contradiction: '
     || 'fetching a public page reveals nothing and is the lowest-stakes call here, while believing what '
     || 'it returns is the highest-stakes thing the platform does.'),
    ('search_knowledge_base', 'UNTRUSTED_EXTERNAL', 'READ_TENANT', 'SIDE_EFFECT', NULL, FALSE,
     'Untrusted even though the corpus is the tenant''s own: ingestion includes fetched web pages and '
     || 'uploaded documents, so a retrieved chunk can be attacker-authored text written to storage days '
     || 'earlier. Taint that does not survive storage is not taint tracking.'),

    -- ---- irreversible: outbound messaging / publishing ---------------------
    ('google_gmail', 'UNTRUSTED_EXTERNAL', 'EGRESS', 'DURABLE',
     'https://www.googleapis.com/auth/gmail.send', TRUE,
     'Both halves of the worst case in one tool name: UNTRUSTED_EXTERNAL because an inbound mailbox is a '
     || 'channel any stranger can write to (that is what a support inbox IS), and EGRESS + irreversible '
     || 'because the same tool can mail data to an address chosen at call time.'),
    ('google_calendar', 'TENANT_SCOPED', 'EGRESS', 'DURABLE',
     'https://www.googleapis.com/auth/calendar.events', TRUE,
     'EGRESS rather than merely CREDENTIALED because create_event takes an attendees list of arbitrary '
     || 'email addresses and Google mails an invitation to each - an outbound message with a caller-chosen '
     || 'recipient that cannot be unsent.'),
    ('upload_video_to_youtube', 'TENANT_SCOPED', 'EGRESS', 'DURABLE',
     'https://www.googleapis.com/auth/youtube.upload', TRUE,
     'The most irreversible action in the platform: the bytes become public under the tenant''s channel '
     || 'identity, and a takedown is not an undo.'),
    ('forward_attachment', 'TENANT_SCOPED', 'EGRESS', 'DURABLE', NULL, TRUE,
     'Sends a stored artifact to a channel and recipient named in the call. The tool already refuses '
     || 'fabricated URLs server-side, which closes a hallucination hole - not this one: a REAL generated '
     || 'artifact forwarded to an attacker-chosen address is a complete exfiltration path.'),

    -- ---- irreversible: arbitrary code execution ----------------------------
    ('run_code', 'UNTRUSTED_EXTERNAL', 'EGRESS', 'DURABLE', NULL, TRUE,
     'Executes model-authored source in an E2B sandbox. EGRESS because nothing in the repository '
     || 'establishes that the sandbox has no outbound network, and the fail-closed reading of unknown '
     || 'egress is egress. Narrow this only with a link to the evidence - a sandbox does not imply no '
     || 'network, and assuming it does is how container isolation ends up answering the wrong question.'),
    ('run_in_code_workspace', 'UNTRUSTED_EXTERNAL', 'EGRESS', 'DURABLE', NULL, TRUE,
     'Arbitrary shell in a code workspace; same unknown-egress reasoning as run_code. Already gated by '
     || 'default through ApprovalPolicyService''s "codeintel.workspace.run" pattern - this row says the '
     || 'same thing structurally, and survives a rename.'),
    ('apply_code_changes', 'TENANT_SCOPED', 'WRITE_TENANT', 'DURABLE', NULL, TRUE,
     'Writes arbitrary file content into a workspace. Already gated by default through '
     || 'ApprovalPolicyService''s "codeintel.workspace.apply" pattern.'),
    ('delete_code_workspace', 'TENANT_SCOPED', 'WRITE_TENANT', 'DURABLE', NULL, TRUE,
     'Destroys a workspace and its contents. Matched today only by the broad "*.delete" default pattern, '
     || 'which would stop matching the moment the tool were renamed.'),

    -- ---- irreversible: money ----------------------------------------------
    ('create_payment_link', 'TENANT_SCOPED', 'CREDENTIALED', 'DURABLE', NULL, TRUE,
     'Moves money. Its own tool description already tells the model this normally requires human '
     || 'approval; irreversible = TRUE is that sentence expressed as DATA, so the gate does not depend on '
     || 'the model having read the description. CREDENTIALED rather than EGRESS because it returns the '
     || 'payment link instead of delivering it.')
) AS v(tool_name, trust, sensitivity, determinism, required_scope, irreversible, notes);

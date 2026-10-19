# Consumer secrets vault — design and state, 2026-09-11

How a consumer gives an agent a password, an API key or a token without that
secret ever reaching a model provider, a trace, or a log line.

---

## 1. The problem, stated precisely

An agent that books a slot on a supplier portal, files an expense, or calls a
paid API needs a credential. The obvious places to put one are all wrong, and
they are wrong for the same reason:

| Tempting place to put the secret | Why it leaks |
|---|---|
| The agent's system prompt | Sent to the model provider on **every turn**. Lands in Langfuse traces and in session memory (Redis, 24h). |
| A tool's argument | The model *generates* tool arguments. To put a secret there, the model must first know it. |
| The chat message itself | Same as above, plus it is now in the transcript forever. |
| Tool config JSON | Read back into context by any tool that describes its own configuration. |

The common failure in all four: **the secret passes through the model.** Once it
does, it has left the building — to a third-party API, to a trace store, and
into a transcript that may be replayed.

So the design rule is a single sentence:

> **The model must never see the secret. It sees only a name.**

## 2. The mechanism

Three pieces, all in the new `core/secrets` module.

### The vault — `tenant_secret` (V222)

A named, AES-GCM-encrypted secret, scoped either to the workspace or to one end
user. It reuses `EncryptionService`, the same chokepoint every other stored
secret in this platform already goes through, so `APP_ENCRYPTION_KEY` rotation
covers it for free.

Two scopes, and the second is the reason this is not just another
`*_credential` table:

- `TENANT` — shared. The business's own Stripe key. Any run may resolve it.
- `USER` — private to one person. *Their* password for a portal. Resolvable
  only on a run that person initiated; a workspace admin listing the vault sees
  that it exists, never its value.

Every existing credential table in the platform is a tenant singleton
(`unique(provider_name)`), which cannot express the second case at all.

### The reference — `{{secret.NAME}}`

What actually goes into the agent, the tool config, the MCP server env. The
model reads, reasons about, and may happily repeat `{{secret.STRIPE_KEY}}` —
and that string is worth nothing to anyone.

### The resolution point — inside the tool boundary

`SecretReferenceResolver` runs **after** the model has emitted a tool call and
**inside** the tool-execution boundary. The value goes straight into the
carrier — an env var for an MCP stdio process, an HTTP header for a remote one
— and nowhere else.

```
model  ──emits──▶  tool call carrying "{{secret.STRIPE_KEY}}"
                          │
                   ┌──────▼───────────────── tool-execution boundary ─────┐
                   │  SecretReferenceResolver → vault → audit row         │
                   │  value injected into env / header                    │
                   │  tool runs                                           │
                   │  SecretRedactor scrubs the value from the result     │
                   └──────┬──────────────────────────────────────────────┘
                          │
model  ◀──receives──  result, with the value masked
```

### Why redaction is not optional

Resolution keeps the secret out of the model's context on the way *in*.
Redaction keeps it out on the way *out*. Both are needed, because the common
leak is not the config — it is a tool that helpfully echoes the request it just
made, or a connection error that quotes the `Authorization` header it failed
with. This platform already learned that once:
`DynamicMcpConnectionManager#redactSecrets` exists because MCP connection
errors were persisting env values into a database column. `SecretRedactor`
generalises that fix, and matches on the **exact values just substituted**
rather than on a "looks like an API key" regex — a regex both misses real
secrets (a password is just a word) and mangles innocent text.

## 3. What is auditable now that was not before

The platform has ~21 bespoke credential entities across 27 encrypted columns,
and **not one of them records who read a secret**. `EncryptionService.decrypt`
is called freely on request threads with no instrumentation. There is no record
of who added, rotated, or deleted any credential either.

A vault holding end users' actual passwords cannot inherit that. Every vault
operation writes to the existing `AuditLogService`:

`SECRET_CREATED` · `SECRET_ROTATED` · `SECRET_UPDATED` · `SECRET_REVOKED` ·
`SECRET_PURGED` · `SECRET_ACCESSED` · `SECRET_ACCESS_DENIED`

`SECRET_ACCESSED` is written on **every** resolution including failures, so
"which agent used this key, when, and when did it stop working" is answerable.
Revoke is the default delete, precisely so that history survives.

## 4. Decisions worth knowing about

**The API is write-only.** No endpoint returns a stored value. The UI needs to
know a secret *exists* (it gets the last 4 characters); a tool needs the value
(it gets it in-process, never over HTTP). A "reveal" button would be the one
endpoint an attacker with a stolen session most wants, in exchange for a
convenience that rotation already covers.

**An unresolvable reference stays literal, it is not blanked.** Blanking would
hand the upstream an empty credential, and several services treat an empty
credential as *anonymous access* rather than as an error — turning a missing
secret into a confusing authorization result instead of an obvious failure.

**A personal secret never falls back to the shared one.** If a user's own
USER-scoped secret is expired, resolution fails rather than quietly using the
workspace account. That fallback would be privilege escalation wearing a
convenience costume.

**MCP connections resolve TENANT secrets only.** An MCP connection is
established per tenant and pooled — it is not per-run and has no initiating end
user, so `null` is passed as the user id and only TENANT rows match. Correct by
construction, and documented at the call site.

**Rotation is an update, not delete-and-recreate.** The row keeps its id and
name, so every agent, tool and MCP server referencing it keeps working across a
rotation with no edits and no re-encryption of `encrypted_env`.

**Names are `UPPER_SNAKE_CASE`, enforced.** That is exactly the character set
that is safe to interpolate both inside the reference syntax and verbatim as a
POSIX environment-variable name, with no escaping anywhere.

## 5. State

**Built, compiled, unit-tested, not integration-tested, not deployed.** Phase 2
adds the web UI, the sandbox path and the admin gate; see §7 and the
Phase 2 list below.

| Piece | Where |
|---|---|
| Table | `core/app/.../db/migration/tenant/V222__tenant_secret.sql` |
| Entity / repo | `core/secrets/.../TenantSecret.java`, `TenantSecretRepository.java` |
| Service (the only door) | `core/secrets/.../SecretVaultService.java` |
| Reference expansion | `core/secrets/.../SecretReferenceResolver.java` |
| Output scrubbing | `core/secrets/.../SecretRedactor.java` |
| REST | `core/secrets/.../SecretVaultController.java` → `/api/secrets` |
| First injection point | `core/mcp/.../McpRegistryService#dynamicConfigFor` |

### Phase 2 (2026-09-12) — what closed

1. **Web UI — built.** `/secrets` (`web/app/secrets/page.tsx` +
   `components/SecretsPanel.tsx`), a rail entry under Connect, and
   `components/ToolSecretGrants.tsx` for granting a secret to one code tool.
   No reveal affordance anywhere, and the one-click action on each row copies
   the **reference**, never a value. 7 component tests pin exactly that.
2. **Custom code tools now receive secrets.** See §7 below — this was the
   largest gap and it needed a different mechanism than expected.
3. **Workspace-wide writes are now admin-only.** Previously any tenant member
   could create, rotate or revoke a `TENANT`-scoped secret, i.e. a credential
   every agent in the workspace uses. `SecretVaultController` now gates those on
   `ROLE_tenant-admin`/`ROLE_platform-admin` and `SecretVaultService` re-checks
   on the delete path, where the scope is only known after the row is loaded.
   Personal (`USER`) secrets are unaffected — they are the caller's own.
   403, not 404: every member can already *see* a shared secret's name, so
   there is no existence secret left to protect (`SecretWriteForbiddenException`).

## 8. Security review, 2026-09-12 — what it found and what changed

A focused review of the whole vault surface. Six findings; all fixed. The
review also confirmed the load-bearing defences hold — USER-scope isolation
(both production resolvers pass a null user id, and `owner_user_id = ?` with a
null bind is never true in SQL), no plaintext in any HTTP response, `javac`
never receiving a secret, and `vega.internal.Harness` being unreachable from
tenant code because `harness.jar` is kept off the tenant compile classpath.

### The one that mattered most: redaction was never a containment boundary

A workspace admin could grant a secret to a code tool, write
`return [ord(c) for c in secrets["STRIPE_KEY"]]`, call the tool's test
endpoint, and read the value out of the 200. Redaction matches known values
literally, so **any** transformation the program applies — codepoints, hex,
reversal, chunking — walks straight through it. That defeated the vault's
central promise of being write-only.

This cannot be fixed by better scrubbing: once tenant code holds plaintext,
tenant code can return it. So the fix is to stop the value getting there by
default. `tenant_secret.code_tool_access` (V228) is **false for every secret
unless somebody explicitly opts it in**, and `resolveForCodeTool` is a separate
resolver that only sees opted-in TENANT rows. A Stripe key used by an MCP
server is now unreachable from sandboxed code even by an admin, and a stolen
admin session recovers only what was deliberately marked. Reads on that path
audit as `SECRET_ACCESSED_BY_CODE` with the tool name, so "a program read this
credential" is greppable rather than blended into ordinary traffic.

### Redaction was also bypassed by ordinary JSON escaping

`redact(result.toString(), secrets)` scrubbed *already-serialized* JSON while
searching for the **raw** value. Serialization rewrites `"` as `\"`, `\` as
`\\` and a newline as `\n`, so a password like `pa$s"word12` was simply not a
substring of the text being scrubbed — and PASSWORD / BASIC_AUTH / CUSTOM are
exactly the kinds that contain those characters. `SecretRedactor` now matches
the JSON-escaped form alongside the URL-encoded and base64 ones, and
`containsAny` checks the same variant set so a leak test cannot pass while an
escaped copy sits in the string it is asserting on.

### The other four

- **Secrets shorter than 6 characters were never redacted** — the redactor
  skips them to avoid mangling ordinary text, and its comment asserted such a
  value "should not have been stored", but nothing enforced that. A 4-digit
  PIN is mainstream for a consumer vault. Now refused at the door, in both the
  request DTO and the service, with a message saying why.
- **`updateMetadata` skipped the write gate** its siblings have. Not reachable
  today (no PUT/PATCH route), but `expiresAt` is load-bearing: setting it to
  the past is a revoke by another name, and `requireVisible` alone passes for
  every member on a shared row. Gated to match `revoke`/`purge`.
- **MCP stderr reached a model provider unredacted.** `McpStdioSelfHealer`
  ships the stderr tail to an LLM as prompt text, and that call sat *before*
  the `redactSecrets` the persisted diagnosis already used. Vault expansion is
  what made this bite: the child's env now holds real plaintext rather than a
  `{{secret.X}}` literal, and a server rejecting a bad credential routinely
  prints it. Both call sites now redact first.
- **`vega.Secrets.populate` is public and callable by tool code**, which its
  own javadoc denied. Not exploitable — it is idempotent and the harness calls
  it before any tool bytecode loads — but the comment was what the next person
  would reason from. Corrected to state the real argument, and the seal is now
  an explicit flag rather than "is the map empty", so the guarantee no longer
  depends on whether the tool had any grants.

### Known and accepted

- A secret opted in to code-tool use **is** readable by anyone who can author a
  code tool in that workspace. That is inherent, and the UI says so in those
  words rather than implying the platform can contain it.
- Under the `insecure` Spring profile there is no authentication, so no caller
  is a workspace admin and TENANT-scoped secrets cannot be written locally.
  Fails closed, consistent with the 2026-07-29 inversion of that profile's
  default; it does mean local dev cannot exercise that path without Keycloak.

---

### Still not built

1. **No just-in-time secret request.** The flow where an agent pauses mid-run
   and asks the user to type a password into a secure field — never stored — is
   not built. `core/approval`'s `HumanApprovalGateService` is the natural seam.
2. **Envelope encryption is still unused.** `EnvelopeEncryptionService` exists,
   is tested, and has zero production call sites; its only KMS provider reads
   the same static key, so it adds shape without isolation. Wiring it is
   blocked on a real cloud KMS, not on this module.
3. ~~No bulk re-encryption job.~~ **Closed** — `SecretRotationService` drives
   `reencrypt()` across the vault, per tenant schema, leaving any row it cannot
   decrypt untouched rather than overwriting it, and auditing an incomplete run
   as a FAILURE so it cannot look finished. Without it, swapping
   `APP_ENCRYPTION_KEY` appears to work (the dual-key fallback hides it) right
   up until someone removes `APP_ENCRYPTION_KEY_PREVIOUS` and every credential
   becomes undecryptable at once.
4. **Never run against a real Postgres.** V222/V223/V227 have not been applied
   anywhere — no Docker on the development host, and the repo has no
   testcontainers or integration-test harness to borrow. The partial unique
   indexes, the CHECK constraint and the `$$`-quoted template JSON are all
   still unexercised.

---

## 7. Secrets in the code sandbox (2026-09-12)

A tenant writes a Java or Python program; it runs in `tool-sandbox` as an
isolated process. Getting a credential into it turned out **not** to be a
matter of setting an environment variable, because the sandbox is built to stop
exactly that:

- Python tool code cannot `import os` (`policy.PYTHON_ALLOWED_MODULES`) and has
  four builtins — no `open`, `eval`, `exec`, `getattr`, `globals`.
- Java tool code cannot reference `java.lang.System`; the policy comment calls
  it "the door to getenv". `Runtime`, `ProcessBuilder`, `Class` and
  `ClassLoader` are denied too.

Those denials are load-bearing and were not relaxed. Instead the secret goes to
the **trusted harness**, which is not subject to them, and the harness hands it
to tool code through one narrow API:

```
core resolves the declared names ──env──▶ harness (trusted, reads env)
                                            │
                      Python: tool_globals["secrets"]   Java: vega.Secrets
                                            │
                                       tool code
```

- **Declarative, not on demand.** A tool declares which secrets it may receive
  (`custom_code_tool_secret`, V227); core resolves exactly those before the
  process starts. Tenant code never names a secret at runtime, so it cannot go
  fishing. "What can this program reach" is answerable by reading the row
  instead of reading the program.
- **`TENANT` scope only.** A code tool is authored by one person and run by
  many. If personal secrets resolved here, the author could write a tool that
  quietly returns whoever-ran-it's password. `resolveSecretsFor` passes a null
  user id, so only workspace secrets match.
- **The Python harness deletes the variables from `os.environ`** once it has
  read them — belt and braces behind an import guard that already makes them
  unreachable. Java has no equivalent (`System.getenv` is an immutable
  start-up snapshot), and needs none: the tool cannot reference `System`.
- **`javac` never sees a secret.** Only the two harness invocations get them;
  the compile step is deliberately left alone, since its `.class` output is
  cached and reused across runs.
- **Redaction on the way back.** `CustomCodeToolService` scrubs the resolved
  values out of `result`, `stdout` and `stderr` before any of it reaches the
  model — a failing program is exactly the one that prints its own config.
- **Budgets.** `TOOL_SANDBOX_MAX_SECRETS` (32) and
  `TOOL_SANDBOX_MAX_SECRET_BYTES` (8192); an over-budget entry is dropped
  rather than failing the run.

Proven by test rather than asserted: a tool **can** read a granted secret, a
tool **cannot** reach `os.environ` to find one it was not granted, and a tool
with no grant sees an empty mapping — in both languages
(`tool-sandbox/tests/test_secretenv.py`, `tests/test_harness_e2e.py`).

## 6. Related change landed alongside

`CommunityTemplateService#applyPostCreateDesign` — template import used to drop
`allowedToolNames` and `enabledGuardrails`. Because an **empty allow-list means
"no restriction"**, a template declaring three tools imported as an agent with
access to *all* of them. Failing open like that is why the safe convention
became "declare `search_knowledge_base` and nothing else", which is why all 22
pre-existing templates were tool-less chatbots. Import now applies both through
the same public services the UI calls, best-effort so a plan-gated guardrail
cannot orphan an already-created agent.

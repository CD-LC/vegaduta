# Guardrails, Knowledge Bridge & Web Integration

*Low-risk, fully-controlled code intelligence — grounded in 2026 industry
practice. Additive; nothing in `core/` changed.*

---

## 1. What was added

| Area | Files (all new/additive) | Purpose |
|---|---|---|
| Guardrail policy engine | `services/code-intel/app/policy.py` | deny/allow/ask gates, caps, protected paths, injection canary |
| Per-tenant policy store | `services/code-intel/app/policy_store.py` | conservative defaults; service kill-switch overrides tenant |
| Audit + approvals | `services/code-intel/app/audit.py` | append-only JSONL trail; pending-approval store |
| Service wiring | `services/code-intel/app/service.py` (edited) | every run/apply is gated → audited; knowledge bridge |
| API | `services/code-intel/app/main.py` (edited) | `/policy`, `/approvals`, `/audit`, `/knowledge/query`, `?approval_id=` |
| Tests | `services/code-intel/tests/test_guardrails.py` | 35 tests, all passing |
| Web page | `web/app/code-intel/page.tsx`, `lib.ts`, `GovernanceTab.tsx` (originally `page.js`, `lib.js`) | the page now has Repos, Knowledge, Search, Graph, Exec and Governance tabs; the guardrail controls are on Governance |

Test totals when this was added: **73 passing** (17 service + 35 guardrails + 21 exec/MCP). The suite has grown since; run it with `python -m tests.<module>` (not pytest).

---

## 2. The guardrail model (why it's low-risk)

Built to the converged 2026 standard for autonomous code agents — VS Code
agent mode, Claude Code's gate precedence, OpenAI Agents SDK `require_approval`,
Codex's managed policy layer, and OWASP's least-privilege/least-agency split.
The rule everyone agrees on: **permissions are configuration you can test and
diff, never a system prompt.**

### Six-gate precedence (a DENY can never be overridden)

```
exec kill-switch → deny-list → injection canary → allow-list → autonomy mode
```

1. **Service kill-switch** — `CODE_INTEL_ALLOW_EXEC=false` disables all
   execution service-wide; no tenant policy can re-enable it (Codex-style
   managed layer beating project config).
2. **Deny-list** (hard, highest precedence) — `rm -rf`, `sudo`, `git push`,
   `curl … | sh`, cloud CLIs, `kubectl`, `terraform apply`, ssh/scp, secret
   paths (`~/.ssh`, `.aws/credentials`), `--latest`/version-floating
   (supply-chain). Matches → **403**, always.
3. **Injection canary** — logs instruction-override / exfiltration phrases in
   commands and file contents; flags in the audit trail (log, don't
   auto-block — false positives hurt usability).
4. **Allow-list** — only non-destructive read/test commands auto-run
   (`pytest`, `mvn test`, `git status`, `grep`, `npm test`, `go test`, …).
5. **Autonomy mode** decides everything else:
   - `read_only` — only allow-listed runs; any write → **approval**.
   - `test_gated` *(default)* — allow-listed runs auto; every write →
     **approval**.
   - `autopilot` — non-listed commands and writes proceed, **still**
     sandboxed, capped, and audited; a canary hit still forces approval.

### Blast-radius limits (reversibility)

Writes are confined to the isolated workspace (never the real checkout),
capped at N files / KB-per-file, commands clamped to a max timeout, and
**protected paths** (`.git/`, `deploy/`, `.github/`, `**/*.pem`, `.env*`,
secrets) are hard-denied even under autopilot.

### Human-in-the-loop that isn't click-through

An ASK action returns **HTTP 428** with an `approval_id` instead of doing
anything. A reviewer approves via `POST /approvals/{id}/resolve`, and only
then does retrying with `?approval_id=…` execute. Low-risk actions auto-allow
so humans are asked only for consequential ones (Anthropic's finding: blanket
gates get click-through-approved 93% of the time — so gate selectively).

### Audit everything

Every decision — `run_executed`, `apply_denied`, `run_escalated`,
`approval_resolved`, `policy_update`, with actor, reason, rule, and canary
flags — is appended to a per-tenant `audit.jsonl`, ready to tail or ship to a
SIEM. "What happened, who approved it, why."

---

## 3. Knowledge-base bridge (zero-risk by construction)

`POST /knowledge/query` is **retrieval-only** — it searches indexed code
(optionally across all the tenant's repos) and returns passages, each with a
**citation** (`repo/path:start-end`). The response includes
`grounded: true|false`; when nothing matches, `grounded:false` and no answer
is fabricated — the RAG grounding guardrail (answers are evidence, not
generation). Because it never executes or writes, it's safe to expose broadly
in the knowledge experience and to agents.

In the platform this maps cleanly onto the existing `knowledge` module and
the `KnowledgeSearchToolCallback` pattern: a future `code_knowledge_search`
tool callback would call this endpoint and hand the agent cited passages,
governed by the same per-agent knowledge flags already in core.

---

## 4. Web integration (the /code-intel preview page)

Two new tabs, added when the page was a standalone link (it is now in the nav rail as "Code Intel", `/code-intel`; the tabs are now Knowledge and Governance):

- **Ask the Code** — natural-language, grounded, cited code Q&A across repos.
- **Guardrails** — set the autonomy mode and toggles, see **pending
  approvals** with Approve/Deny, and a live **audit trail** (color-coded:
  green executed, red denied, amber escalated, ⚠ canary). This is the human
  control surface the whole model depends on.

The Execute & Rework tab now shows each action's policy decision; an ASK
action surfaces as an approval in the Governance tab rather than running.

### How this maps onto core's existing guardrails (when you promote it)

The platform already has `core/guardrails` (`GuardedToolCallback`,
`ToolCallVerifierService`, `ApprovalGateMode`) and `core/approval`
(`HumanApprovalGateService`). The code-intel policy layer is deliberately the
**same shape**, so promotion is a mapping, not a rebuild: code-intel `ASK` →
`HumanApprovalGateService`; deny/allow lists → `GuardrailService`; the audit
trail → `core/audit`. Until then it's fully self-contained and enforced
inside the service.

---

## 5. Try it

```bash
# service (guarded), from code-intel/
CODE_INTEL_AUTH_MODE=keycloak CODE_INTEL_CORS_ORIGINS=http://localhost:3000 \
CODE_INTEL_ALLOW_EXEC=true python -m uvicorn app.main:app --port 8090

# web, from web/
npm run dev      # open http://localhost:3000/code-intel → Governance tab
```

Default posture is **test_gated with exec off** until you opt in — safe by
default, autonomy raised deliberately, per tenant.

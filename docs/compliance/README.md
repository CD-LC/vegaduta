# Standards alignment pack

An evidence map from the controls in this repository to the standards a buyer, auditor or
risk committee is likely to ask about. Written to be checked, in the style of the
[OWASP mapping](../security/owasp-mapping.md): every **Covered** or **Partly** row names a
file, a configuration key and a test, and anything that could not be pointed to in code is
marked **Not covered**.

> **This is an evidence mapping, not a compliance claim.** The VegaDūta software carries no
> certification: it is not ISO/IEC 42001, ISO/IEC 27001 or SOC 2 certified or attested, it has
> had no third-party conformity assessment, and nothing in this pack says that it, a deployment
> or a customer "complies with" the EU AI Act, the NIST AI RMF or any other framework. The
> statement on the
> [compliance posture page](../platform-documentation/security/compliance-posture.html) stands.
> Compliance is a property of an organisation and a deployment: a management system, people,
> decisions and records that a code base cannot contain. Where a framework asks for one, this
> pack says **organisational: the software cannot provide it** and stops there. It is
> engineering notes, not legal advice.

As of **2026-10-28**, from the working tree at that date. Nothing here was run on a stack.

## The pages

| Page | Framework | What it maps |
|---|---|---|
| [ISO/IEC 42001](iso-iec-42001.md) | ISO/IEC 42001:2023 AI management system | Clauses 4-10 and Annex A (A.2-A.10) |
| [NIST AI RMF](nist-ai-rmf.md) | NIST AI RMF 1.0 and the Generative AI Profile (NIST AI 600-1) | Govern, Map, Measure, Manage; the twelve GenAI risks |
| [EU AI Act](eu-ai-act.md) | Regulation (EU) 2024/1689 | Arts. 9, 10, 12, 13, 14, 15, 26, 50 and the general-purpose model rules; operator and deployer split |
| [SOC 2 and ISO/IEC 27001 evidence map](soc2-iso27001-evidence-map.md) | SOC 2 Trust Services Criteria, ISO/IEC 27001:2022 Annex A | Control families to CI gates, release signing, access control, audit seals, tenancy, backup |
| [MCP and A2A conformance](mcp-a2a-conformance.md) | MCP 2026-07-28 revision, A2A 1.0.0 | Actual protocol conformance status and the tests behind it |
| [DPDP obligations](dpdp-obligations.md) | India DPDP Act 2023 and Rules 2025 | Existing page, kept as is |
| [ADR-0027 Agent mandates](../architecture/ADR-0027-agent-mandates-and-delegated-authority.md) | none (a design proposal) | Closes the largest control gap these pages share: delegated authority |

Related existing material: the [OWASP mapping](../security/owasp-mapping.md) (Agentic, LLM and MCP
Top 10), [authorization model](../security/authorization.md), [security model](../security-model.md),
and, for the embeddable SDK, the [SDK compliance mapping](../sdk/compliance-mapping.md).

## How to read the status

Same legend as the OWASP mapping.

| Status | Meaning |
|---|---|
| **Covered** | a control exists in code, is on by default (or the row says which profile turns it on), has a test, and no bypass was found in the code that was read |
| **Partly** | a control exists but at least one of: no test, off by default, fails open, regex-only, covers part of the requirement, or a known bypass. A control without a test is always Partly |
| **Not covered** | no control was found in code. A neighbouring control is named in the note |
| **Organisational** | the framework asks for something only an organisation can supply (a policy, a role, a review, a contract, an audit). The software can at most leave evidence behind. It is never scored Covered |

"Covered" is a statement about a control and a test. It is not a statement that an auditor
accepted it, that a deployment runs it, or that it meets the standard.

## Method and limits

- Evidence comes from reading this working tree on 2026-10-28. Every file path, test class and
  configuration key named in a Covered or Partly row was looked up in the tree (see
  [What was verified](#what-was-verified)). Paths are written `core/<module>/.../Class.java`:
  the package directories are elided, the module and the class name are exact. The ADR-0023
  split moved many classes out of `core/agents` into `agents-turn`, `agents-tools`,
  `agents-interop` and `agents-budget`; older documents still name the old location, and this
  pack does not rely on them.
- Test classes are listed because they exist and exercise the control. Assertion depth was not
  audited for each, and the tests were not re-run for this pack.
- Defaults are read from `core/app-config/src/main/resources/application*.yml`. A deployment can
  override all of them; the *personal* profile (`application-personal.yml`) tightens some.
- The standards' own texts were not reproduced. Clause and article numbers are cited from the
  published structure of each standard; ISO/IEC 42001 and 27001 are paid standards and their
  full text was not consulted for this pack. Where a clause title is given from memory of the
  published table of contents, treat it as an index and check it against your licensed copy.
- Pages for the SDKs (`sdk/java`, `sdk/python-agent`) are mapped separately; a control that
  exists only in an SDK is marked as such and is not counted for the platform.

## Who is responsible for what

Three parties appear in these pages. The split matters most for the EU AI Act, and is repeated
on that page.

| Party | In this pack | Typical obligations |
|---|---|---|
| **Software publisher** | the VegaDūta project | ships code, releases, signed artifacts, documentation, a vulnerability policy ([SECURITY.md](../../SECURITY.md)) |
| **Platform operator** | whoever runs a VegaDūta deployment for others (or for themselves) | configuration, key custody, backups, access reviews, monitoring, incident response, vendor contracts |
| **Deployer / tenant** | whoever configures an agent for a purpose and puts it in front of people | the intended purpose, risk classification, impact assessment, oversight staffing, notices to affected people |

One organisation can be all three. The pages say which party a gap belongs to.

## Known gaps

Real gaps found while writing the pack. They are repeated, with their framework rows, on the
individual pages. "In progress on a branch" means work exists on a branch of this repository and
is **not yet released**; nothing in this pack counts it as done.

| # | Gap | State at 2026-10-28 | Where it shows up |
|---|---|---|---|
| 1 | No distinct-approver option for tenant admins. A tenant admin can approve a request they raised; the self-approval check exempts admins on purpose (single-admin tenants) | gap, no option exists | `core/approval/.../ApprovalController.java` (`rejectSelfApproval`); EU AI Act Art. 14, ISO 42001 A.9, SOC 2 CC6 |
| 2 | Untrusted-content taint is advisory in the base profile: `app.agents.taint.mode` defaults to `observe`; only the `personal` profile enforces | gap by default | OWASP ASI01; NIST Manage; EU AI Act Art. 15 |
| 3 | Knowledge collections have no per-collection access control list: `KnowledgeCollection` carries no owner, role or visibility field. Access is by tenant, plus which collections an agent is bound to | gap; per-collection ACL is in progress on a branch, not yet released | ISO 42001 A.7, EU AI Act Art. 10, SOC 2 C1 |
| 4 | A2A has no message signing. Agent Cards can be signed and verified; individual messages cannot | gap; message signing is in progress on a branch, not yet released | [MCP and A2A conformance](mcp-a2a-conformance.md), OWASP ASI07 |
| 5 | Workflow execution is not durable by default: `app.workflow.execution-engine` defaults to `legacy` (in-process, a crashed run is reaped FAILED); `temporal` is opt-in and needs the `temporal` profile | gap by default | SOC 2 A1/PI1, EU AI Act Art. 15 |
| 6 | Coverage is report-only in CI. JaCoCo runs under `-Pcoverage` and uploads the `core-coverage` artifact; no threshold is enforced | gap | SOC 2 CC8, ISO 27001 A.8.29 |
| 7 | The tamper-evident audit seal is off by default (`app.audit.seal.enabled=false`) and needs a pinned signing key | by default | EU AI Act Art. 12, SOC 2 CC7 |
| 8 | The per-turn journal (replayable record of a turn) is off by default (`app.turn-journal.enabled=false`) | by default | EU AI Act Art. 12, NIST Measure |
| 9 | No output-side disclosure that a person is talking to an AI on the web console or embeddable widget; the one-time disclosure exists on inbound messaging channels only | gap | EU AI Act Art. 50 |
| 10 | No model cards, subprocessor list or DPAs ship with the project | gap, organisational | ISO 42001 A.10, NIST Map |
| 11 | No behavioural anomaly detection on agents; no output-side system-prompt leakage filter | gap | NIST Measure, OWASP LLM07, ASI10 |
| 12 | The platform-level delegated-authority model (scope, spend ceiling, expiry, revocation, workload identity) does not exist for agents in general; payment mandates exist for money only | proposal in [ADR-0027](../architecture/ADR-0027-agent-mandates-and-delegated-authority.md) | ISO 42001 A.9, NIST Govern/Manage, EU AI Act Arts. 14, 26 |
| 13 | MCP tool pinning defaults to `warn`, not `enforce` (`app.mcp.tool-pinning.mode`) | by default | OWASP MCP03, [MCP conformance](mcp-a2a-conformance.md) |
| 14 | The MCP server and client speak the handshake-based (2025-11-25) protocol; the stateless 2026-07-28 revision is blocked on the MCP Java SDK | gap, SDK-blocked | [MCP and A2A conformance](mcp-a2a-conformance.md) |
| 15 | A2A `ListTasks`, `SubscribeToTask`, push notifications and gRPC are not implemented; no external A2A client or TCK has been run | gap | [MCP and A2A conformance](mcp-a2a-conformance.md) |

## What was verified

For each page, the list of file paths, test classes and configuration keys cited in a Covered or
Partly row was extracted and looked up in the working tree: paths and class names against the
file list (a class must exist under the named module), configuration keys against
`application*.yml` and the Java sources. On 2026-10-28 the extraction found 302 path and file
references, 273 class and test-class names and 66 `app.*` configuration keys across the seven
pages and the ADR. Every one resolved after fixes, apart from names that are deliberately absent
(a path or class the text says does not exist, such as the A2A gRPC service) and names the ADR
proposes. Key checks are on the key's last segment in `application*.yml` or the Java sources,
which is looser than checking the full dotted path; defaults quoted in rows were read by eye from
the YAML. `mkdocs build --strict`, `scripts/ci/docs-link-check.sh` and
`scripts/ci/internal-reference-lint.sh` pass. A name existing is not proof it asserts the
property in the "Controls" cell.

## Keeping the pack honest

- Change the status when the code changes, not when a plan is written. A row moves to Covered
  only with a path, a key and a test.
- Rows about in-flight work move when the work is released, and the release is named in the row.
- Do not add a row that cites a document as its only evidence. Cite code, config and a test, or
  mark the row Not covered.
- The pack has no generator. Run `bash scripts/ci/docs-link-check.sh` and `make docs-build` after
  editing it.

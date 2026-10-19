# Adoption playbook: get your agent through security review in a week

Audience: the Java team whose agent is "done" but blocked in security, risk or compliance
review; the architect who has to sign it off; the person who will run the design-partner
programme. This is the operational companion to the [Java Agent SDK](java-agent-sdk.md) and
[positioning](positioning.md) pages.

The plan below is five working days of calendar time, not five days of effort. Most of the
week is waiting for real traffic to flow through a shadow policy. The team's hands-on work is
roughly one to two days.

> **Status of the pieces this playbook names.** The 0.1 SDK ships `Governance`, `@Governed`,
> `GovernanceMode` (`WARN` / `STRICT` / `OFF`), `ToolDispatchGate`, `ApprovalGate`, budgets,
> `EffectJournal`, guardrails, hooks, `AuditSink` and the testkit (see the
> [README](java-agent-sdk.md)). The components marked *in flight* in the table at the end of
> this page (agent-scanner, `ShadowGate`, `PolicyReplay` / `ShadowReport`, `ShowbackReport`,
> `UnderwritingReport`, `HaltDrill`, `ToolManifest`, `AutonomyLevel`, `ApprovalNotifier`, the
> compliance and security-review packs) are being built alongside this document and are not
> yet in a tagged release. Where a step depends on one of them, the fallback using 0.1 types is
> given. Do not promise a reviewer a report that is not on the classpath yet.

## Why a week, and why this order

Security reviews of agents stall for one reason: the reviewer cannot answer, from evidence,
which tools the agent can reach, what each can do to the world, who approves the dangerous
ones, what a run can cost, and what stops it. Showing them a demo does not answer that;
showing them an argument does not either. **Evidence from the agent's real traffic does.**

So the week is ordered to produce evidence without changing behaviour until the last step:

| Day | Outcome | Behaviour change |
|---|---|---|
| 1 | A risk map of every tool the agent can call | none (static scan) |
| 2 | Governance in the process, in `WARN`, with the target `STRICT` policy evaluated in shadow | none (observe only) |
| 3-4 | Shadow and showback reports from real traffic | none |
| 5 | Evidence bundle handed to the reviewer | none |
| after sign-off | Flip to `STRICT`, autonomy per agent, halt drill scheduled, manifest pinned, approvals in chat | **yes**, deliberately and with the evidence of what will change |

## Day 1: a risk map, no code changes

Goal: a one-page table of every tool, its risk, side effects, data access and OWASP ASI
exposure, produced from the code as it is.

1. Run the scanner over the agent's build output (*in flight*; see the status table):

   ```bash
   mvn -f pom.xml io.vegaduta:vegaduta-agent-maven-plugin:scan
   # or, standalone:
   java -jar vegaduta-agent-scanner.jar --classpath target/classes --format markdown > risk-map.md
   ```

   It lists every `@Tool` / `ToolCallback` / `ToolCallbackProvider` it can see, whether it
   carries `@Governed`, the inferred classification where it does not (HTTP client in the
   method body implies `exfiltrationSink`; repository access implies `readsPrivateData`;
   anything that parses a URL, a file or a web page implies `consumesUntrustedInput`), and
   flags the **lethal trifecta** (private read + untrusted input + sink) across the set.

   **Fallback with 0.1:** build with the starter on the classpath in `WARN` mode (Day 2 step 1)
   and read the `agent.toolset` audit events and the `WARNING` events for unclassified tools.
   `Governance.governAll(tools)` performs the trifecta check at runtime; in `WARN` it audits.

2. Sit with the team for 30 minutes and add `@Governed` to each tool. Be honest in the
   annotation: `sideEffecting`, `readsPrivateData`, `consumesUntrustedInput`,
   `exfiltrationSink`, `idempotent`, `requiresApproval`, and the ASI items. A tool that posts
   to Slack is an exfiltration sink even if "it only posts to our channel".

3. Output: `risk-map.md`, committed next to the agent. This is page 1 of the evidence bundle
   and the reviewer's first question answered.

Do not argue about thresholds today. A wrong classification is fixed by a one-line change; a
missing one is a finding.

## Day 2: starter in WARN plus the target STRICT policy in shadow

Goal: governance is in the process and the reviewer's intended policy is being evaluated
against every real call, but **nothing is blocked and nobody is asked for approval yet**.

1. Add the starter:

   ```xml
   <dependency>
     <groupId>io.vegaduta</groupId>
     <artifactId>vegaduta-agent-spring-boot-starter</artifactId>
     <version>${vegaduta-agent.version}</version>
   </dependency>
   ```

   ```properties
   vegaduta.agent.mode=warn
   vegaduta.agent.store.type=jdbc            # durable audit from day one; memory is fine for a spike
   vegaduta.agent.store.initialize-schema=false
   ```

   Apply the DDL from `io/vegaduta/agent/jdbc/schema-postgresql.sql` (or H2) through your own
   Flyway or Liquibase. `WARN` means: unclassified tools run with the default classification
   and a `WARNING` audit event; `requiresApproval` tools still run but are audited. Adding the
   dependency does not break the application; that is the design contract.

   Caveat: in 0.1, a tool annotated `requiresApproval = true` is *gated* in `WARN` mode too
   (the gate is mode-independent; only the unclassified and trifecta rules change with mode).
   If you want a pure observe-only week, leave `requiresApproval` off until Day 5 and let the
   shadow policy carry the risk threshold instead.

2. Register the **target** policy as a shadow (*in flight*):

   ```java
   @Bean
   ToolDispatchGate gate(PolicyToolDispatchGate live) {
     GovernancePolicy target = GovernancePolicy.builder()
         .mode(GovernanceMode.STRICT)
         .approvalRequiredFrom(RiskLabel.HIGH)
         .denyUnclassified(true)
         .build();
     return ShadowGate.of(live, new PolicyToolDispatchGate(target));
   }
   ```

   `ShadowGate` returns the live verdict, evaluates the shadow one, and records both on the
   `tool.call` audit event (`shadow.verdict`, `shadow.reason`). Every divergence is a call that
   would have been denied or held for approval under the target policy.

   **Fallback with 0.1:** implement `ToolDispatchGate` yourself in 20 lines: delegate to the
   live `PolicyToolDispatchGate`, call a second `PolicyToolDispatchGate` built from the strict
   policy, and write the second verdict through a `BEFORE_TOOL_CALL` `Hook` or straight to an
   `AuditSink`. The point is the paired record, not the class name.

3. Wire the telemetry you already have. `MicrometerToolCallObserver` and
   `OpenTelemetryToolCallObserver` register automatically when a `MeterRegistry` or
   `OpenTelemetry` bean exists. Reviewers like a dashboard; `vegaduta.agent.tool.calls` by
   `tool` and `outcome` is enough.

4. Deploy to the environment that carries real traffic (production if the organisation allows
   observe-only changes there, otherwise the staging environment with mirrored traffic). Nothing
   has changed for users.

## Days 3-4: collect the evidence from real traffic

Goal: two reports from at least 48 hours of traffic, or from enough runs that every tool has
been called at least once. Weekends and release freezes count against the 48 hours; start on a
Tuesday.

1. **`PolicyReplay` and `ShadowReport`** (*in flight*). `PolicyReplay` re-evaluates the audit
   log against a policy offline; `ShadowReport` summarises the live-versus-shadow pairs
   recorded on Day 2:

   ```
   ShadowReport  2026-10-07T00:00Z .. 2026-10-09T00:00Z   agent=refund-assistant   runs=1,284
   tool             calls   live        shadow (STRICT)      divergence
   order_status     3,912   ALLOW       ALLOW                0
   refund           211     ALLOW       REQUIRE_APPROVAL     211   (HIGH, side-effecting)
   send_email       190     ALLOW       REQUIRE_APPROVAL     190   (trifecta sink)
   web_fetch        1,044   ALLOW       ALLOW                0
   legacy_lookup    17      ALLOW(WARN) DENY                 17    (unclassified)
   ```

   This table tells the reviewer exactly what `STRICT` will change and tells the team how
   many approvals per day a human will have to decide. If `refund` shows 211 approvals in two
   days, the approval path needs a queue and a rota before the flip, not after.

   **Fallback with 0.1:** `SELECT tool, outcome, attributes->>'shadow.verdict', count(*) FROM
   vegaduta_audit WHERE event_type='tool.call' GROUP BY 1,2,3` against the JDBC audit table,
   with `shadow.verdict` written by your Day-2 gate. Same table, no class.

2. **`ShowbackReport`** (*in flight*): tool calls, model calls, tokens and estimated dollars per
   run, per agent, per tenant, from the `model.call` and `tool.call` audit events and the
   budget commits. Set `vegaduta.agent.budget.estimated-usd-per-tool-call` and the model
   pricing table so the estimate is not zero. Two days of showback gives you the number the
   finance reviewer asks for and the basis for a `max-usd-per-run` that will not trip on
   normal traffic. Analyst context for why they ask: see the FinOps figures in
   [positioning](positioning.md#finops).

   **Fallback with 0.1:** token counts are recorded on `model.call` audit events; sum them. Dollar
   caps are enforced from `max-usd-per-run`; token caps are recorded but not yet enforced
   across model calls (README, known limitations).

3. Fix what the reports surface while traffic keeps flowing: classify the unclassified tool,
   mark the idempotent ones (so the effect journal can serve replays), split a tool that is
   both a read and a write. Each fix is a one-line annotation and a redeploy that still changes
   no behaviour.

## Day 5: hand over the evidence bundle

Goal: the reviewer gets a folder they can read in an hour, each document answering one of
their standard questions, with the data behind it.

| Document | Answers | Source |
|---|---|---|
| `risk-map.md` (Day 1) | Which tools, what can each do, where is the trifecta | scanner or `agent.toolset` events |
| `shadow-report.md` (Days 3-4) | What will change under the target policy, how many approvals per day | `ShadowReport` or the SQL fallback |
| `showback-report.md` (Days 3-4) | What a run costs, what the caps will be | `ShowbackReport` or summed `model.call` events |
| **EU AI Act Art. 12 / 14 pack** (*in flight*, `vegaduta-agent-compliance`) | Art. 12 record-keeping: the append-only `vegaduta_audit` table, its retention policy (six months minimum for high-risk deployers), the hash chain or DB-level immutability you configured. Art. 14 human oversight: the approval trail (`approval.requested` / `approval.decided` with requester, decider, four-eyes), the stop path (`GovernanceMode.OFF` is *not* a stop; the halt drill is, see below) | audit table, approval store |
| **OWASP ASI coverage** | For each of ASI01-ASI10, which SDK control is active for this agent and which is not. The README's standards table is the template; filter it to the controls this agent actually enables. Say plainly which items are *not* covered (for example ASI04 supply chain when no MCP or skills scanning is in place) | README mapping, `coversRisks(...)` testkit assertions |
| **`UnderwritingReport`** (*in flight*) | The subset an insurer or an internal risk committee asks for: autonomy level per agent, approval coverage of side-effecting tools, budget caps, halt-drill result and date, audit retention, third-party audit status (**none** today, say so) | the above, condensed |
| `compliance-mapping.md` (*in flight*) | Control-by-control mapping to EU AI Act, ISO/IEC 42001, NIST AI RMF, OWASP ASI | static document shipped with the compliance module |
| `security-review-pack.md` (*in flight*) | The reviewer-facing walkthrough: threat model, what the SDK enforces, what the host must do (secure the approvals endpoint, run the DDL, set retention, protect `APP_ENCRYPTION_KEY`-style secrets), known limitations verbatim from the README | static document |

Hand it over with a 30-minute walkthrough. Lead with the shadow report: "here is what will
change, here is how many times a day a human will be asked". Reviewers sign off on changes they
can see.

## After sign-off: flip to STRICT, deliberately

Each step below changes behaviour. Do them in order, one deploy each, and watch the shadow
divergence column drop to zero as live catches up with the target.

1. **`vegaduta.agent.mode=strict`.** Unclassified tools are denied; the lethal trifecta forces
   every sink in the set to require approval. Because Days 3-4 left no unclassified tools, this
   should deny nothing. If it does, the shadow report lied to you; stop and find out why before
   step 2.

2. **`AutonomyLevel` per agent** (*in flight*). Not every agent gets the same policy; Gartner's
   warning that uniform governance leads to agent failure (analyst, see positioning) matches
   what the shadow report showed you: a read-only reporting agent and a refunding agent are
   different animals. Set, per `RunContext.agentId`:

   | Level | Meaning | Policy |
   |---|---|---|
   | `OBSERVE` | may read, nothing side-effecting runs | deny list = every `sideEffecting` tool |
   | `ASSISTED` | side-effecting tools run only with approval | `approvalRequiredFrom = LOW` for side-effecting |
   | `SUPERVISED` | HIGH/CRITICAL need approval, the rest runs | `approvalRequiredFrom = HIGH` (the default) |
   | `AUTONOMOUS` | runs within budget; approvals only for `requiresApproval` tools | caps tight, four-eyes on |

   **Fallback with 0.1:** one `GovernancePolicy` per agent, selected in your own
   `ToolDispatchGate` bean by `RunContext.agentId()`.

3. **`HaltDrill` scheduled** (*in flight*). Art. 14 asks for a human who can stop the system;
   an untested stop is a promise. The drill flips the agent's gate to deny-all, asserts that
   the next tool call is denied and audited, verifies in-flight approvals are expired or
   rejected, and restores. Schedule it monthly and keep the report in the bundle.

   **Fallback with 0.1:** a `ToolDispatchGate` with an `AtomicBoolean halted` consulted first,
   an admin endpoint you secure yourself, and a scheduled test that exercises it against the
   staging stack. `GovernanceMode.OFF` is the opposite of a halt; never use it as one.

4. **`ToolManifest` pinned** (*in flight*). The set of tools and classifications the reviewer
   signed off is written to a manifest (the ACS "AgBOM" shape) and the build fails if the
   runtime toolset differs. A new tool is a new review, not a surprise.

   **Fallback with 0.1:** a testkit test that asserts the exact set of `ToolDescriptor` names
   and classifications returned by `Governance.governAll(...)`; it fails when someone adds a
   tool.

5. **Approvals in Slack or Teams via `ApprovalNotifier`** (*in flight*). An `ON_APPROVAL_REQUESTED`
   hook posts the request (tool, argument preview, requester, expiry) to a channel; the
   decision comes back through your own authenticated endpoint that calls
   `ApprovalGate.approve(id, decidedBy)` / `reject(...)`. Four-eyes stays on: the requester's
   principal cannot decide their own request.

   **Fallback with 0.1:** `WebhookHook` on `ON_APPROVAL_REQUESTED` pointing at a small relay
   that posts to the channel, plus the optional approvals endpoint
   (`vegaduta.agent.approval.endpoint.enabled=true`, **secure it yourself**; it has no
   authentication of its own).

Keep the shadow gate on after the flip, now with the *next* policy in shadow (for example
`approvalRequiredFrom = MEDIUM`). The week's method becomes the way policy changes are made.

## Design-partner programme

Three partners, one quarter, one named reference at the end. Fewer partners means one
company's quirks become the roadmap; more means nobody gets real help.

**Selection.** Each partner has a Java agent (Spring AI or any framework) that is **built but
stuck in security, risk or compliance review**, a named security reviewer willing to be in
the room, and a tool set with at least one side-effecting tool. A partner without a stuck agent
has no urgency; a partner without a reviewer cannot give the feedback that matters.

Ideal mix: one regulated financial-services team (the Art. 12/14, FINRA and insurance questions
come up unprompted), one platform team governing several internal agents (the tiered-autonomy
and showback questions), one team on a non-Spring stack (LangChain4j, Quarkus, plain Java) to
keep the `api` + `core` path honest.

**What a partner gets.** A named engineer for the week described above, run with them, not for
them. Weekly 45-minute calls for the quarter. Issues they file are triaged within two working
days. Their reviewer's questions that the evidence bundle cannot answer become documentation or
features, in that order. Hands-on help migrating when `@Experimental` APIs move.

**What a partner gives.** Permission to describe their deployment (anonymised unless they agree
to be named), the shadow report numbers at the start and after the flip, their reviewer's
sign-off checklist (which becomes the next edition of `security-review-pack.md`), and for
**one** of the three, agreement to be a named reference with a quote once their agent is in
production under `STRICT`.

**Cadence.**

| Week | Milestone |
|---|---|
| 1-2 | Partner onboarding; the five-day playbook run with each |
| 3-6 | Partners in shadow or `STRICT`; weekly calls; fixes shipped as `0.x` snapshots |
| 7-10 | Second agent at each partner on the SDK without hands-on help (the "does it stand alone" test) |
| 11-12 | Reference write-up; retrospective; roadmap adjusted from the three sign-off checklists |

## Success metrics

Measure these for each partner and for the programme. Targets are deliberately modest; the
first quarter is for learning what the real numbers are.

| Metric | How measured | Target |
|---|---|---|
| Review cycle time | Date the agent entered review to the date of sign-off, for agents using the bundle vs. the partner's previous agents | at least halved |
| Time to risk map | Day 1 start to committed `risk-map.md` | under 1 day |
| Shadow divergence at flip | Divergent calls in the 24 hours before `STRICT` | 0 unexpected |
| Incidents after flip | Denied or halted calls that were legitimate, first 30 days | 0 that reach a user |
| Approval latency | `approval.requested` to `approval.decided`, median | under 15 minutes in business hours |
| Approval expiry rate | Requests that hit `approval.ttl` undecided | under 5 percent |
| Cost predictability | Runs over `max-usd-per-run` after the showback-derived cap | under 1 percent |
| Halt drill | Monthly, passes, report in the bundle | 100 percent |
| Stand-alone adoption | Second agent per partner on the SDK with no hands-on help | 3 of 3 |
| Reference | A named, quotable production user | 1 |

Vanity metrics to avoid: GitHub stars, download counts, number of OWASP items "covered" in a
table. A control that is on the classpath and not enabled for the agent covers nothing.

## What NOT to promise

- **"Compliant with the EU AI Act."** The SDK produces evidence a deployer uses for Art. 12
  (records) and Art. 14 (oversight). Compliance is the deployer's, assessed against the whole
  system. The high-risk application date and the proposed Digital Omnibus delay are moving
  (see positioning); do not quote a date as settled.
- **"Covers all ten OWASP ASI risks."** It has a control *relevant to* each; coverage for a
  given agent depends on which controls are enabled, and ASI04 (supply chain) needs the MCP
  and skills modules that have not shipped.
- **"Stops prompt injection."** The guardrails redact known patterns in tool output and block
  secrets from leaving through arguments. They lower the odds; the trifecta rule and approvals
  are what limit the blast radius. Say that.
- **"Insurable" or "meets insurer requirements."** The `UnderwritingReport` presents facts an
  underwriter asks about; whether a policy is written, and on what terms, is between the
  customer and the insurer. No insurer has endorsed this SDK.
- **A third-party audit, a SOC 2 report, or a named production user.** None exist today. The
  platform dogfoods the artifacts; that is the only production exposure, and it is first-party.
- **Orchestration, model calls or a UI.** The SDK governs tools; Spring AI, LangChain4j,
  Embabel and the others run the agent. Partners who want a console get the VegaDūta platform,
  which is a different product with a different deployment footprint.
- **API stability before 1.0.** Anything `@Experimental` may move; japicmp enforces
  compatibility from 1.0.0 onward, not before.
- **Behaviour under streaming.** Streamed `ChatClient` replies are not inspected by output
  guardrails in 0.1 (README, known limitations).

## Component status

| Component | Where | Status (2026-10-15; "in code" means the class exists in `sdk/java`, tests not re-run for this page) |
|---|---|---|
| `Governance`, `@Governed`, `GovernanceMode`, `GovernancePolicy` | `agent-core` | shipped, 0.1 |
| `ToolDispatchGate`, `PolicyToolDispatchGate` | `agent-api`, `agent-core` | shipped |
| `ApprovalGate`, four-eyes, TTL, optional endpoint | `agent-core`, starter | shipped (endpoint unauthenticated) |
| Budgets, `EffectJournal`, guardrails, hooks, `WebhookHook` | `agent-core` | shipped |
| `AuditSink` (log, memory, JDBC), Micrometer, OTel observers | `agent-core`, `agent-jdbc` | shipped |
| `GovernanceTestKit`, AssertJ assertions | `agent-testkit` | shipped |
| `agent-scanner` module and CLI (`io.vegaduta.agent.scanner.Main`) | `sdk/java/agent-scanner` | in code (`@Experimental`); the Maven plugin `scan` goal does **not** exist, run the CLI |
| `ShadowGate` | `agent-core` (`core/replay`) | in code (`@Experimental`) |
| `PolicyReplay`, `ShadowReport` | `agent-core` (`core/replay`) | in code (`@Experimental`) |
| `ShowbackReport` | `agent-core` (`core/cost`) | in code (`@Experimental`) |
| `UnderwritingReport`, `HashChainedAuditSink`, EU AI Act evidence exporters | `agent-core` (`core/evidence`; there is no separate `agent-compliance` module) | in code (`@Experimental`) |
| `compliance-mapping.md`, `security-review-pack.md` | docs | shipped (this site) |
| `AutonomyLevel`, `AutonomyRegistry` | `agent-api`, `agent-core` | in code (`@Experimental`) |
| `HaltDrill` | `agent-core` (`core/halt`) | in code (`@Experimental`) |
| `ToolManifest` | `agent-core` (`core/provenance`) | in code (`@Experimental`); no Maven plugin |
| `ApprovalNotifier` | `agent-core` (`core/approval`); Slack/Teams adapters not checked | in code (`@Experimental`) |
| MCP hardening, skills scanner, LangChain4j adapter | `agent-mcp`, `agent-skills`, `agent-langchain4j` | not started (ADR-0013 phase 2) |

The *in flight* markers in the steps above pre-date these classes; where this table says "in code",
the class exists but is `@Experimental` and may change before 1.0, so keep the fallback as the
safe default until you have tried it on your own agents. The `agent-maven-plugin` command shown in
step 1 is not built; use the scanner CLI.

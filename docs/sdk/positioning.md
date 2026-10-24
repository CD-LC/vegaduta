# Positioning: where the VegaDūta Agent SDK sits, honestly

Audience: maintainers deciding what to build next, partners deciding whether to adopt, and
anyone writing about the SDK who should not overstate it. Companion to the
[adoption playbook](adoption-playbook.md) and [ADR-0013](../architecture/ADR-0013-embeddable-agent-sdk.md).

Snapshot date: **2026-10-14**. Every statistic below is tagged **vendor-reported** (the number
comes from the company selling something) or **analyst** (Gartner, OWASP, FinOps Foundation,
CSA, a law firm or a trade publication). None has been independently verified by this project.
Items marked **verify** rest on secondary coverage and should be checked against the primary
source before being quoted externally. Sources are listed at the end.

## One paragraph

The SDK is a **governance layer for tools in Java agents**: tool risk classification, a
dispatch gate, human approval with a four-eyes rule, atomic budgets, an idempotent effect
journal, pattern guardrails, hooks, and one audit record across tool and model calls, tagged
with OWASP agentic risk ids. It is a dependency, not a platform: no server, no UI, no model
calls, no orchestration. It sits on top of Spring AI today, with `api` and `core` usable from
any framework, and it exists because the Java frameworks ship the loop and leave the controls to
the application team, while the governance toolkits that do ship controls do not ship Java.

## What the SDK does NOT do

State these first, because every competitor comparison below is only fair against them.

- **No orchestration.** No planner, no multi-agent graph, no workflow engine. Spring AI,
  LangChain4j agentic, Embabel, Google ADK and Koog do that; the SDK wraps the tools they call.
- **No model calls.** It never talks to an LLM. `BEFORE_MODEL_CALL` / `AFTER_MODEL_CALL` hooks
  and `model.call` audit events observe the host's calls; they do not make them.
- **No UI.** Approvals are decided through `ApprovalGate` from the host's own UI, a chat
  command, a ticket, or an optional unauthenticated endpoint. The VegaDūta platform has a
  console; the SDK does not.
- **No identity.** It carries a `RunContext` (tenant, principal, agent, run) that the host
  supplies; it does not issue, verify or federate agent identities. Okta, Entra and the MCP
  authorization spec are where that lives.
- **No protocol implementation.** MCP, A2A and payments protocols are consumed through their
  own SDKs; this SDK hardens arguments and outputs and maps approvals onto MCP's
  `input_required` (the hardening module has not shipped yet).

## Where it is unproven

- **A first release, not a mature one.** `0.1.0` is a GitHub Release (`sdk-v0.1.0`), not yet on
  Maven Central; namespace verification and the Central upload are pending. japicmp compatibility enforcement starts at 1.0.
- **No third-party audit.** No penetration test, no SOC 2 report, no code audit by an outside
  firm. The test suite and CI gates are first-party.
- **No named production user.** The platform in this repository consumes the artifacts
  (dogfooding); no external organisation has reported running it in production.
- **Modules the comparisons assume are absent.** MCP hardening, skills supply-chain scanning,
  the LangChain4j adapter, the compliance pack and the Maven plugin are ADR-0013 phases 2 and
  3. The adoption playbook lists the exact status of each.
- **Known limitations** (README): streamed replies are not inspected by output guardrails;
  token budgets are recorded but not enforced across model calls; the approvals endpoint has
  no authentication.

## Competitive map

### Java agent frameworks (the SDK sits on these, it does not replace them)

| Project | State (Oct 2026) | Governance built in | Relationship |
|---|---|---|---|
| **Spring AI** 2.0.x | GA | The January 2026 Agent Skills post describes skill loading and tool use and states **no built-in approval** step; approval is left to the application. The team has said "agentic foundations" are targeted for **November 2026**; scope is not public. | Primary adapter target. Risk: November ships approvals or budgets. Mitigation in ADR-0013 section 9: adapt to their extension points, re-evaluate within two weeks of release. |
| **LangChain4j** `langchain4j-agentic` | core GA; agentic **beta** | Human-in-the-loop exists but is **per workflow** (suspend/resume of an agentic scope), not per tool; no tool risk model, budgets or audit schema. **verify** against the official tutorial. | Second adapter (phase 2). Their HITL and our approval gate are complementary: theirs pauses the graph, ours decides whether a specific side effect may happen and journals the result. |
| **Embabel** 1.0 | GA | GOAP planning over typed goals and actions; no governance layer. Built on Spring AI 1.1.x / Boot 3.5 (Spring AI 2 support **verify**). | Sits beside the SDK on Spring AI; an Embabel action's tools can be governed once Embabel moves to Spring AI 2. |
| Google ADK for Java, Koog, Jakarta Agentic AI (draft) | GA / GA / draft | Not a focus / none / explicitly out of 1.0 scope | Possible adapters after 1.0; not competitors. |

### Governance toolkits

| Project | Languages | Open source | Claim | Relationship |
|---|---|---|---|---|
| **Microsoft Agent Governance Toolkit** | Python, TypeScript, .NET, Rust, Go. **No Java.** | MIT | Coverage of **10/10 OWASP agentic risks** (vendor-reported), EU AI Act / HIPAA / SOC 2 evidence mapping. | The closest thing to this SDK in every language but ours. Its existence validates the category; its language list is the gap. Expect a Java port to be asked for; design `api` so that if one appears, a bridge is one adapter. |
| AgentAdmit SDK | Java (Spring Boot) | proprietary | Approval only | Single concern, closed. |
| AgentBudgetGuard, agents-kt | JVM | OSS / **verify** | Budget wrapper / permission manifests | Single concern, pre-1.0. |
| OWASP Agent Control Standard (ACS) v0.1 | spec | MIT | Hook points, declarative policy, AgBOM | Not a competitor; the hook names and manifest shape the SDK tracks. |

### Platform control planes (vendor-scoped governance)

These govern agents **inside one vendor's estate**. They are strong there and absent
elsewhere, which is the room an embeddable, vendor-neutral library lives in.

| Vendor | Offering | What it governs | Limits for a Java team (analyst / press, see sources) |
|---|---|---|---|
| ServiceNow | AI Control Tower, private MCP registry | Agents and MCP servers registered with ServiceNow | Agents outside the Now platform are visible only if registered; enforcement for third-party agents' tool calls is through the registry, not in the agent's process. |
| Salesforce | Agent Fabric, Trusted Enterprise AI Harness | Agentforce agents and registered external agents | **Enforcement parity between Salesforce-native and external agents is unproven** (press coverage; **verify**). |
| SAP | API Policy v4/2026 §2.2.2 | Access to SAP APIs | **Blocks external AI agents from calling its APIs** directly, pushing customers to SAP's own agents (press coverage; **verify** the policy text). A Java agent that needs SAP data is pushed to an SAP-mediated path, whatever governance it carries. |
| Workday | Agent System of Record | Agents registered with Workday | Same pattern: registry-centred, HR/finance-scoped. |

Position: the SDK is the governance that travels **with the agent** across these estates and
is the same when the agent calls a SAP-mediated API, a ServiceNow MCP server and an internal
database in one run. It does not replace any vendor's control tower; the audit events it emits
are what a control tower would ingest.

### Identity layers

| Offering | State | Relationship |
|---|---|---|
| Okta Agent SSO | GA August 2026 (vendor press release) | Issues and federates agent identities. The SDK consumes the principal and agent id in `RunContext`; it does not issue them. Complementary. |
| Microsoft Entra Agent ID | shipping | Same relationship. |
| MCP 2026-07-28 authorization | OAuth 2.1 resource-server model; **authorization remains optional** in the spec | Identity for MCP servers; the SDK's planned MCP module pins origins and hardens traffic but does not do OAuth. |

### The "guardian agent" category (analyst framing)

Gartner's Market Guide for guardian agents (February 2026; secondary coverage in the sources)
names the category this SDK belongs to in analyst language: software that observes, gates and
can stop other agents. Three Gartner claims matter for positioning, all **analyst**, none
verified by this project:

- Through 2028, **80 percent of unauthorised agent transactions** will stem from **internal
  policy violations, not external attacks**. If true, a dispatch gate, budgets and approvals
  (policy enforcement) matter more than attack detection for most of the exposure.
- By 2027, **40 percent of enterprises will demote or decommission agents** because of
  governance gaps **found after incidents**. The adoption playbook's shadow-then-flip sequence
  is built for finding the gaps before the incident.
- **Uniform governance across agents will lead to agent failure**; the recommendation is tiered
  autonomy. The planned `AutonomyLevel` per agent is the direct response; the 0.1 fallback is
  one `GovernancePolicy` per `agentId`.

## Drivers

### Regulatory

| Driver | Status (Oct 2026) | What the SDK contributes | What it does not |
|---|---|---|---|
| **EU AI Act, high-risk obligations** | The brief for this document cites **2 August 2026** as the high-risk application date with a **Digital Omnibus delay proposed and unconfirmed**; ADR-0013 (written 2026-10-12) records Regulation (EU) 2026/1744 as **adopted**, deferring stand-alone high-risk obligations to **2 December 2027**. These disagree. **verify** against the Official Journal before quoting either date; the CSA note in the sources is the starting point. | **Art. 12** record-keeping: append-only audit table with run context, tool, arguments preview, outcome; retention is the host's (six months minimum for high-risk deployers). **Art. 14** human oversight: approval trail with requester, decider, four-eyes; a stop path once `HaltDrill` ships (fallback in the playbook). | Does not make a system compliant; does not classify a system as high-risk; does not produce the technical documentation or conformity assessment. |
| **FINRA 2026 Regulatory Oversight Report** | Published; continued focus on generative AI and **emerging agent-based risks** (law-firm summary in sources) | Evidence of supervision over agent actions: who approved, what ran, what it cost. | Does not implement any FINRA rule; firms map the audit record to their supervisory procedures. |
| **Federal Reserve SR 26-2 (April 2026)** | Replaces SR 11-7 model risk guidance; **excludes agentic AI** from its scope (consultancy summary in sources; **verify**) | Nothing directly: the exclusion means banks have no supervisory template for agents, so the evidence bundle is filling a gap, not meeting a requirement. | Do not describe the SDK as "SR 26-2 compliant"; the guidance does not cover agents. |

### Insurance

Insurers have started carving AI out of general liability and selling it back as a named
product. All **vendor-reported** or **press**:

- ISO general-liability **AI exclusion endorsements** (January 2026) remove AI-caused loss from
  standard GL policies, creating the coverage gap the mediate.com article in the sources
  describes.
- **AIUC-1**-tied agent liability cover: coverage conditioned on an agent meeting the AIUC-1
  standard's controls. Munich Re's **aiSure** is the named carrier product.
- The planned `UnderwritingReport` is designed to answer the questions these forms ask
  (autonomy level, approval coverage, budget caps, stop capability, audit retention, third-party
  audit status). **No insurer has reviewed or endorsed the SDK or the report.** Do not imply
  insurability.

### FinOps

- **73 percent** of organisations exceeded their AI cost plans; agentic workloads overshoot by
  **about 2.4x** (State of FinOps 2026, as reported by beri.net; **analyst**, **verify against
  the FinOps Foundation primary report** before quoting; the 2.4x figure in particular is
  secondary).
- The SDK's answer is narrow and real: reserve-then-commit budgets that cannot be jointly
  overspent by parallel tool calls, dollar caps per run, and token counts on every `model.call`
  event for showback. Token caps across model calls are not yet enforced (README).

### Java in enterprise AI

- **62 percent of enterprises use Java for AI in production** (Azul State of Java 2026, as
  reported by The New Stack; **vendor-reported**, Azul sells a JDK). Whatever the exact number,
  the Microsoft toolkit shipping in five languages and not Java, while Java carries this much
  production AI, is the market gap in one sentence.

### Protocols

| Protocol | State | Governance in the protocol | SDK stance |
|---|---|---|---|
| **MCP 2026-07-28** | release candidate then release | OAuth 2.1 resource servers defined; **authorization still optional**. Press reports **1,800+ unauthenticated public MCP servers** (press, **verify** the scan methodology). Multi Round-Trip Requests (`input_required`) give approvals a protocol shape. | `PENDING_APPROVAL` maps onto `input_required` one to one; the hardening module (phase 2) pins origins, sanitises output, redacts secrets. Does not do OAuth. |
| **A2A v1.0** | released (Linux Foundation) | **Authorization left to implementers.** | Egress guard and card verification planned (phase 3) on top of the A2A Java SDK. |
| **AP2 / x402** agent payments | emerging | Payment authorization is the whole point; policy about *when* an agent may pay is out of scope for the protocols | A payment tool is `CRITICAL`, `sideEffecting`, `requiresApproval`, `idempotent` with an effect-journal key; the SDK does not implement either protocol. |
| arXiv **2606.31498** | preprint | Surveys MCP, A2A, ACP, ANP and ERC-8004 and finds **governance absent** from all of them (academic; **verify** the paper's exact claims). | This is the thesis of ADR-0013 stated by someone else: the protocols move messages; something in the process has to decide, record and stop. |

## Forecast (opinions, dated 2026-10-14)

These are the maintainers' expectations, not facts. Revisit quarterly.

1. **Spring AI's November 2026 agentic release will include some human-in-the-loop primitive.**
   Likelihood: high. Consequence: the SDK must adapt to it within two weeks (ADR-0013 section
   9) and position the approval gate as the thing that *decides and journals*, while theirs
   *pauses*. If they also ship a risk model and audit schema, the SDK's Spring-specific value
   drops and the framework-neutral `api` + `core` and the compliance evidence become the whole
   story.
2. **Microsoft will add Java to the Agent Governance Toolkit, or a community will.**
   Likelihood: medium within 12 months. Consequence: the SDK must be in Maven Central with a
   1.0 API and at least one named user before that lands, or it becomes the second choice.
3. **Vendor control planes will demand registration and will not accept in-process governance
   as a substitute.** Likelihood: high. Consequence: the SDK's audit events need an exporter
   per control tower (ServiceNow, Salesforce, Workday) so that a governed Java agent can be
   registered with its evidence, not instead of it.
4. **Insurers will ask for exactly the facts in the `UnderwritingReport` and nothing else.**
   Likelihood: medium. The AIUC-1 question list is the one to track; if coverage is written
   against it, the report becomes the SDK's most-requested output.
5. **The EU high-risk date will slip at least once more and be argued about until the day
   before.** Likelihood: high. Consequence: build the Art. 12 / 14 evidence now because
   reviewers ask for it regardless of the date; never put a date in a product claim.
6. **Agent incidents in 2027 will mostly be policy violations by authorised agents, as Gartner
   predicts.** Likelihood: medium-high. Consequence: the shadow-then-flip method in the
   playbook and tiered autonomy are the differentiators to invest in, ahead of more
   prompt-injection patterns.

## How to describe the SDK in one breath

"A Java dependency that gives an agent's tools a risk classification, an approval gate with a
four-eyes rule, budgets that cannot be overspent, an effect journal so retries do not pay twice,
guardrails, hooks and one audit record tagged with OWASP agentic risk ids. It runs on Spring AI
today and on any framework through its API. It does not orchestrate, call models or ship a UI.
It is a 0.1.0 release without a third-party audit or a named production user yet; the evidence it
produces is what a security reviewer asks for, and the adoption playbook shows how to produce
it in a week without changing behaviour."

## Sources

Read for this document on 2026-10-14. Claims that rest on these without a primary document are
marked **verify** above.

Frameworks
- Spring AI, "Generic Agent Skills" (January 2026; no built-in approval; agentic foundations roadmap): <https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills/>
- Java for AI in production (Azul survey as reported): <https://thenewstack.io/2026-java-ai-apps/>

Governance tooling and analyst framing
- Microsoft Agent Governance Toolkit (languages, OWASP coverage claim): <https://github.com/microsoft/agent-governance-toolkit>
- Gartner, uniform governance across AI agents leads to failure; tiered autonomy (press release, 2026-05-26): <https://www.gartner.com/en/newsroom/press-releases/2026-05-26-gartner-says-applying-uniform-governance-across-ai-agents-will-lead-to-enterprise-ai-agent-failure>
- Gartner Market Guide for guardian agents, five learnings (secondary): <https://thehackernews.com/2026/03/5-learnings-from-first-ever-gartner.html>
- OWASP GenAI, agentic exploit roundup Q3 2026: <https://genai.owasp.org/2026/10/08/genai-and-agentic-ai-exploit-roundup-q3-2026>
- GitGuardian, six controls for AI agent security: <https://blog.gitguardian.com/ai-agent-security-six-controls>

Regulation and insurance
- CSA research note, EU AI Act high-risk compliance deadline: <https://labs.cloudsecurityalliance.org/research/csa-research-note-eu-ai-act-high-risk-compliance-deadline-20/>
- Debevoise, FINRA 2026 Regulatory Oversight Report (generative AI, agent-based risks): <https://www.debevoisedatablog.com/2025/12/11/finras-2026-regulatory-oversight-report-continued-focus-on-generative-ai-and-emerging-agent-based-risks/>
- SR 11-7 rescinded, SR 26-2 and the agentic-AI gap (consultancy summary): <https://elevateconsult.com/insights/sr-11-7-rescinded-model-risk-guidance-ai-gap/>
- Agentic AI and the emerging insurance coverage gap (ISO exclusions, AIUC-1, aiSure): <https://mediate.com/agentic-ai-and-the-emerging-insurance-coverage-gap/>

Identity and platforms
- Okta Agent SSO press release: <https://www.okta.com/newsroom/press-releases/okta-brings-first-class-identity-to-ai-agents-with-agent-sso/>
- SAP blocks external AI agents; Salesforce and ServiceNow do not (press): <https://www.techzine.eu/blogs/applications/141323/sap-blocks-external-ai-agents-salesforce-and-servicenow-dont/>

Protocols
- MCP 2026-07-28 release candidate: <https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/>
- Governance absent across agent protocols (preprint): <https://arxiv.org/html/2606.31498>

FinOps
- 73 percent exceeded AI cost plans; agentic overshoot (State of FinOps 2026 as reported): <https://www.beri.net/article/ai-finops-2026-73-percent-blow-budget-cfo-fix>

Internal
- ADR-0013, embeddable agent SDK (research, plan, framework and standards tables, its own source list): [ADR-0013](../architecture/ADR-0013-embeddable-agent-sdk.md)
- SDK README (standards mapping, known limitations): [Java Agent SDK](java-agent-sdk.md)

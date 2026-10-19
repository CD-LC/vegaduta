import type { Industry } from "./recipes-types";
import { Network } from "lucide-react";

// Core pattern pack, part a: Multi-agent patterns. Content unchanged; split out of recipes-core-patterns.tsx.
export const INDUSTRIES_CORE_A: Industry[] = [
  // ============================================ ADVANCED MULTI-AGENT PATTERNS
  {
    id: "multi-agent-patterns",
    name: "Advanced multi-agent patterns",
    icon: Network,
    tagline:
      "Supervisor routing, parallel research and cost-tiered escalation — with the budgets and guardrails that keep genuinely multi-agent flows safe AND cheap.",
    featured: true,
    recipes: [
      {
        id: "supervisor-support-desk",
        title: "Supervisor → specialist support desk (4 agents)",
        scenario:
          "One support bot trying to be an expert at billing, tech and returns is either shallow or expensive — running a heavyweight model on every 'where's my order?'.",
        triggerLabel: "WhatsApp / inbound message",
        steps: [
          { kind: "trigger", title: "Customer messages", detail: "A WhatsApp trigger starts the conversation." },
          { kind: "supervisor", title: "Supervisor agent triages + routes", detail: "A Supervisor node classifies intent and routes to exactly one specialist. It never answers the customer itself — routing only." },
          { kind: "condition", title: "Pick the domain", detail: "A Condition node directs the run to the matched specialist (billing / technical / returns)." },
          { kind: "agent", title: "Matched specialist handles it", detail: "Only the relevant specialist agent runs — each has its own prompt, Knowledge scope and tools." },
          { kind: "approval", title: "Money actions pause for a human", detail: "Any refund/credit routes to an Approval node before it executes." },
          { kind: "output", title: "Reply on WhatsApp", detail: "An Output node sends the specialist's grounded answer." },
        ],
        connectors: ["WhatsApp", "Knowledge base", "Tool (MCP → OMS/CRM)", "Approval / HITL"],
        outcome: "Each conversation is handled by a focused expert, and you only pay for a heavyweight model on the message that actually needs one.",
        impact: "Depth without the blanket cost — a cheap router in front of specialists is both better (focused prompts/knowledge) and cheaper than one do-everything agent.",
        build: {
          agents: 4,
          agentsNote: "1 supervisor + 3 domain specialists. The Condition/Approval/Output are workflow nodes.",
          pattern: "Supervisor → 3 specialists (route-to-one)",
          prompt:
            "You are a support ROUTER. Read the customer message and classify it as exactly one of: BILLING, TECHNICAL, RETURNS, or HUMAN (anything sensitive/unclear). Output strict JSON {route, confidence 0-1, reason}. You do NOT answer the customer — you only route. If confidence < 0.6, route=HUMAN.",
          agentRoles: [
            { role: "Supervisor / router", model: "Economy tier — fast, low-cost (runs on every message)", prompt: "Classify intent → one of BILLING/TECHNICAL/RETURNS/HUMAN as strict JSON. Never answer; never invent a category; low confidence → HUMAN." },
            { role: "Billing specialist", model: "Standard tier", prompt: "You handle billing/orders/invoices for {{company}}. Look up the order via the tool, answer from it only, and NEVER issue a refund/credit yourself — propose it and let the Approval node decide." },
            { role: "Technical specialist", model: "Standard tier", prompt: "You troubleshoot {{product}} strictly from the Knowledge base. If a step isn't documented, say so and offer a human. Never guess config or make destructive suggestions." },
            { role: "Returns specialist", model: "Standard tier", prompt: "You handle returns/exchanges per the returns policy in Knowledge. Confirm eligibility from policy; create the RMA via the tool; refunds go through Approval." },
          ],
          mcp: ["OMS / CRM — via Tool (MCP) nodes (order lookup, RMA create)", "WhatsApp — send channel"],
          tools: ["knowledge_search (per-specialist scoped collections)", "http_request (order/RMA)"],
          guardrails: [
            "Separation of duties: the Supervisor can ONLY route — it has no customer-reply or tool permissions",
            "Each specialist is scope-locked to its own Knowledge collection + tools (a billing agent can't touch RMA endpoints)",
            "All money movement (refund/credit) is Approval-gated; specialists may propose, never execute",
            "Per-conversation tool-call cap so a loop can't rack up API cost",
          ],
          cost:
            "The only agent that runs on EVERY message is the economy-tier supervisor (cheap). A standard/premium specialist fires once, for the single matched domain — so you never pay a heavyweight model to answer 'where's my order'. Enable per-agent cost routing so each specialist still drops simple turns to its economy model.",
          output:
            "Supervisor emits {route, confidence} → Condition dispatches to one specialist → specialist answers from its scoped Knowledge (money actions via Approval → Tool) → Output sends the reply on WhatsApp; the router's decision + specialist + any approval are logged.",
        },
      },
      {
        id: "parallel-due-diligence",
        title: "Parallel due-diligence pack (5 agents, budget-capped)",
        scenario:
          "Vendor/partner onboarding needs a financial, compliance and reputation check. Done serially by one agent it's slow; done naively it's an open-ended (expensive) research loop.",
        triggerLabel: "Webhook (new vendor/partner to vet)",
        steps: [
          { kind: "trigger", title: "New entity to vet", detail: "A Webhook trigger fires with the company details." },
          { kind: "parallel", title: "Fan out three researchers at once", detail: "A Parallel node launches the financial, compliance and reputation research agents simultaneously — fast wall-clock, isolated scopes." },
          { kind: "agent", title: "Three read-only researchers", detail: "Each researcher gathers evidence in its lane, capped at a fixed tool/token budget so it can't run away." },
          { kind: "agent", title: "Aggregator writes the risk memo", detail: "An aggregator agent synthesizes the three findings into one structured risk memo with a rating." },
          { kind: "agent", title: "Guardrail agent verifies", detail: "A cheap guardrail agent rejects any claim without a source citation and redacts PII before a human ever sees it." },
          { kind: "approval", title: "Analyst signs off", detail: "An Approval node puts the accept/reject decision with a human — the pack only researches." },
          { kind: "output", title: "File the memo", detail: "An Output/Tool node stores the approved memo against the vendor record." },
        ],
        connectors: ["Webhook", "Knowledge base", "Tool (MCP → vendor system)", "Approval / HITL"],
        outcome: "A cited, structured risk memo in minutes instead of a day — with hard budgets so a research loop can never blow up the bill, and a human owning the decision.",
        impact: "Parallelism buys speed; budgets + a guardrail agent buy cost-safety; the Approval node keeps the judgement human. This is the pattern for anything 'gather from many sources, then decide'.",
        build: {
          agents: 5,
          agentsNote: "3 parallel researchers + 1 aggregator + 1 guardrail agent. Approval/Output are workflow nodes.",
          pattern: "Parallel fan-out → aggregator → guardrail agent → human",
          prompt:
            "(Aggregator) You are a risk analyst. Given three research briefs (financial, compliance, reputation), produce a structured memo: {summary, rating: LOW|MEDIUM|HIGH, findings[{area, claim, source}], open_questions[]}. Every claim MUST carry a source from the briefs — if there's no source, drop the claim. Do not make the accept/reject decision; that's the human's.",
          agentRoles: [
            { role: "Financial researcher", model: "Standard tier — read-only, budget-capped", prompt: "Research {{company}}'s financial standing from allowed sources only. Return dated, sourced facts. Never speculate; mark unknowns as unknown." },
            { role: "Compliance researcher", model: "Standard tier — read-only, budget-capped", prompt: "Check {{company}} against sanctions/registration/litigation signals from allowed sources. Sourced facts only; flag anything you cannot verify." },
            { role: "Reputation researcher", model: "Standard tier — read-only, budget-capped", prompt: "Gather reputation signals (news, reviews, incidents) for {{company}}. Sourced, dated items only; separate fact from rumor." },
            { role: "Aggregator", model: "Premium tier — reasoning (runs once)", prompt: "Synthesize the three briefs into the structured risk memo; every claim cited; no accept/reject decision." },
            { role: "Guardrail / verifier", model: "Economy tier — fast, cheap", prompt: "Reject any memo claim lacking a source; redact PII; confirm the rating is justified by cited findings before it reaches a human." },
          ],
          mcp: ["Vendor / procurement system — via Tool (MCP)", "allowed research sources — via Tool (MCP)/web where permitted"],
          tools: ["web_search (scoped, allow-listed)", "knowledge_search (internal policy)", "http_request"],
          guardrails: [
            "Read-only until approval: researchers/aggregator can gather but perform NO external writes",
            "Hard per-agent budget: max tool-calls + token cap per researcher, so a search loop can't escalate cost",
            "Citation-required: the guardrail agent drops any uncited claim — no ungrounded assertions reach the analyst",
            "Human owns the verdict: the accept/reject Approval is never automated",
          ],
          cost:
            "Researchers run in PARALLEL (wall-clock ≈ one research pass, not three) and each is capped at a fixed tool/token budget, so worst-case cost is bounded and predictable. Only the aggregator uses a premium reasoning model — once. The verifier is an economy-tier model. You pay premium exactly once per vetting, not per source.",
          output:
            "Parallel researchers → aggregator memo (cited) → guardrail agent verifies/redacts → analyst Approval → on accept, a Tool node files the memo to the vendor record; rejects loop back with the open questions.",
        },
      },
      {
        id: "cost-tiered-escalation",
        title: "Cost-tiered answer ladder (3 agents: cheap → premium → verify)",
        scenario:
          "You want premium-quality answers but can't afford to run a top-tier model on every routine question — and you never want a confident wrong answer.",
        triggerLabel: "WhatsApp / Webhook (question in)",
        steps: [
          { kind: "trigger", title: "Question arrives", detail: "An inbound trigger starts the ladder." },
          { kind: "agent", title: "Tier-1 answers cheaply", detail: "A fast, economy-tier agent attempts the answer from Knowledge and returns a confidence score." },
          { kind: "condition", title: "Confident enough?", detail: "A Condition node checks the confidence threshold; high-confidence answers skip straight to verify." },
          { kind: "agent", title: "Tier-2 escalation (only if needed)", detail: "Only low-confidence questions hit a premium reasoning agent — this is the one expensive call, and it's rare." },
          { kind: "agent", title: "Verifier grounds the answer", detail: "A cheap verifier agent confirms the answer is supported by Knowledge before anything is sent." },
          { kind: "approval", title: "Human on the residual", detail: "Still unsure? An Approval node routes the handful of genuinely hard ones to a person." },
          { kind: "output", title: "Send the verified answer", detail: "An Output node delivers it." },
        ],
        connectors: ["WhatsApp", "Knowledge base", "Approval / HITL"],
        outcome: "The vast majority of questions are answered on a cheap model; the premium model is spent only where it changes the outcome; nothing ungrounded goes out.",
        impact: "This is the cost/quality/safety triangle solved: economy-tier by default, premium on demand, and a verifier that turns 'confident wrong answers' into escalations.",
        build: {
          agents: 3,
          agentsNote: "Tier-1 (economy) + Tier-2 (premium, conditional) + verifier (economy). Condition/Approval/Output are workflow nodes.",
          pattern: "Tiered escalation: cheap → premium (conditional) → verify → human",
          prompt:
            "(Tier-1) Answer the user's question using ONLY the Knowledge base. Return strict JSON {answer, confidence 0-1, sources[]}. If you can't fully ground the answer, set confidence low and do NOT fabricate — a low score is correct and safe; a confident guess is a failure.",
          agentRoles: [
            { role: "Tier-1 responder", model: "Economy tier — fast, cheap (runs on every question)", prompt: "Answer from Knowledge only; emit {answer, confidence, sources}; never guess — low confidence is the right call when unsure." },
            { role: "Tier-2 specialist", model: "Premium tier — reasoning (only on low confidence)", prompt: "Handle the hard question Tier-1 couldn't. Reason carefully, still grounded in Knowledge, and cite sources. If it truly needs a human, say so." },
            { role: "Verifier", model: "Economy tier — fast, cheap", prompt: "Check that the proposed answer is supported by the cited sources. If any claim isn't grounded, block it and escalate to a human." },
          ],
          mcp: ["WhatsApp — channel", "internal systems — via Tool (MCP) if the answer needs live data"],
          tools: ["knowledge_search", "http_request (only if a live lookup is required)"],
          guardrails: [
            "No-fabrication: Tier-1 must return a confidence score and defer (escalate) instead of guessing",
            "Bounded escalation: at most ONE premium (Tier-2) call per question — cost per request is capped",
            "Grounding gate: the verifier blocks any answer not supported by cited Knowledge before send",
            "Human backstop: the residual unsure cases go to Approval, never out unverified",
          ],
          cost:
            "Set the confidence threshold so ~80–90% of questions resolve on the economy Tier-1 model; the premium Tier-2 model is invoked ONLY on the low-confidence remainder, capped at one call. This is the platform's cost-routing idea made explicit across agents — premium-tier answer quality at close to economy-tier average cost, with a hard per-request ceiling.",
          output:
            "Tier-1 {answer, confidence} → Condition escalates only low-confidence to Tier-2 → verifier grounds it → Output sends; unresolved ones go to a human via Approval. Every tier used is logged so you can tune the threshold against real cost.",
        },
      },
      {
        id: "map-reduce-batch",
        title: "Map-reduce over a batch (classify + summarize at scale)",
        scenario:
          "Every month there are thousands of tickets / reviews / contracts to classify, tag and summarize. Manual is impossible, and a single agent can't fit them all in one context window.",
        triggerLabel: "Webhook (batch ready — e.g. a nightly export)",
        steps: [
          { kind: "trigger", title: "Batch is ready", detail: "A scheduled Webhook trigger fires with the batch reference (or the workflow pulls the list)." },
          { kind: "loop", title: "Map: iterate every item", detail: "A Loop (or Parallel) node fans the mapper agent across each item — one focused pass per record, isolated context." },
          { kind: "agent", title: "Mapper classifies + extracts", detail: "A cheap mapper agent returns strict, schema-validated JSON per item (category, sentiment, key fields, one-line summary)." },
          { kind: "condition", title: "Validate each result", detail: "A Condition node rejects malformed output so one bad item never corrupts the aggregate." },
          { kind: "agent", title: "Reduce: aggregator rolls it up", detail: "After the map completes, a reducer agent aggregates the validated items into trends, top issues and an exec summary." },
          { kind: "approval", title: "Analyst reviews the rollup", detail: "An Approval node puts the summary in front of a human before it's published." },
          { kind: "output", title: "Publish the report + write tags back", detail: "An Output/Tool node writes per-item tags back to the source and posts the summary." },
        ],
        connectors: ["Webhook", "Tool (MCP → source system)", "Approval / HITL"],
        outcome: "Thousands of items classified and rolled into a decision-ready summary on a schedule, at a cost you can predict per item.",
        impact: "Map-reduce is THE pattern for 'too big for one context': cheap per-item passes plus one premium synthesis — linear, bounded cost that scales with volume instead of exploding.",
        build: {
          agents: 2,
          agentsNote: "1 mapper (runs once per item via the Loop/Parallel node) + 1 reducer (runs once). Loop/Condition/Approval are workflow nodes.",
          pattern: "Map-reduce: Loop/Parallel (map) → aggregator (reduce)",
          prompt:
            "(Mapper) Classify ONE item. Return strict JSON {category (from the allowed enum), sentiment, key_fields{}, summary (max 25 words)}. Use only the item's own text; if a field is absent use null — never invent. Your output MUST validate against the schema or it will be rejected.",
          agentRoles: [
            { role: "Mapper (per item)", model: "Economy tier — fast, cheap; runs N times so its model choice dominates total cost", prompt: "Classify/extract ONE item into strict schema-valid JSON; null for missing; zero invention." },
            { role: "Reducer (once)", model: "Premium tier — reasoning", prompt: "Aggregate the validated per-item results into trends, a top-5 issues list, and a 5-line exec summary with real counts. Use only the provided items; cite counts, never estimate." },
          ],
          mcp: ["Source system (tickets/reviews/DMS) — via Tool (MCP)"],
          tools: ["knowledge_search (taxonomy/rubric)", "http_request (fetch + tag items)"],
          guardrails: [
            "Schema validation on every mapper output — malformed items are dropped, never fed into the reduce (data integrity)",
            "Hard per-item token/tool budget + timeout — bounds total cost at N × a known ceiling",
            "Idempotent per item (keyed by item id) so a re-run never double-tags or double-counts",
            "Sample audit: a fixed % of items are human-spot-checked, and the Approval node gates the published rollup",
          ],
          cost:
            "Cost is linear and predictable: N × (cheap, hard-capped mapper) + ONE premium reduce. Because the mapper runs thousands of times, it MUST sit on the economy tier — that single choice is the dominant cost lever. Run items in Parallel batches for wall-clock speed without changing per-item cost.",
          output:
            "Loop maps the cheap agent over every item → Condition validates each → reducer synthesizes once → analyst Approval → Output writes per-item tags back and posts the summary; rejected items are queued for review, never silently lost.",
        },
      },
      {
        id: "generator-critic",
        title: "Generator ↔ critic reflection loop (quality gate)",
        scenario:
          "Draft outputs — proposals, customer replies, policy answers, code — need to clear a quality/accuracy bar before they ship. A single agent rarely catches its own mistakes.",
        triggerLabel: "Webhook / inbound (draft requested)",
        steps: [
          { kind: "trigger", title: "Draft requested", detail: "A trigger starts the request with the brief + any source material." },
          { kind: "agent", title: "Generator drafts", detail: "A generator agent produces the first draft, grounded in Knowledge." },
          { kind: "agent", title: "Critic reviews against a rubric", detail: "A separate critic agent scores the draft against an explicit rubric and returns PASS/FAIL + specific issues — it never rewrites, only critiques." },
          { kind: "condition", title: "Pass or revise?", detail: "A Condition node checks the critic's verdict." },
          { kind: "loop", title: "Revise (bounded rounds)", detail: "On FAIL, a Loop sends the issues back to the generator — capped at a max number of rounds so it can't loop forever." },
          { kind: "approval", title: "Human signs off high-stakes", detail: "An Approval node puts the final output in front of a person for anything customer-facing or binding." },
          { kind: "output", title: "Ship the vetted output", detail: "An Output node delivers the approved result." },
        ],
        connectors: ["Webhook", "Knowledge base", "Approval / HITL"],
        outcome: "Outputs clear an explicit quality bar before a human ever sees them — the reviewer gets an already-vetted draft, not a rough first pass.",
        impact: "The reflection pattern (generator + critic) catches errors a lone agent misses, at the cost of a bounded number of extra passes. A hard round cap keeps quality high without unbounded token spend.",
        build: {
          agents: 2,
          agentsNote: "1 generator + 1 critic, iterating via a round-capped Loop. Condition/Approval/Output are workflow nodes.",
          pattern: "Generator ↔ critic (reflection, round-capped)",
          prompt:
            "(Generator) Produce the requested output grounded ONLY in the provided brief + Knowledge. When you receive critic feedback, address each issue specifically and return the revised version. Never claim a fact you can't cite.",
          agentRoles: [
            { role: "Generator", model: "Standard/Premium tier — sized to the output's stakes", prompt: "Draft (and revise on feedback) grounded in Knowledge; cite; address each critic issue explicitly." },
            { role: "Critic", model: "Standard tier", prompt: "Score the draft against the rubric {accuracy, grounding/citations, completeness, tone, policy-compliance}. Return {verdict: PASS|FAIL, issues[]}. Do NOT rewrite — critique only. Fail anything ungrounded or non-compliant." },
          ],
          mcp: ["Delivery channel (Email/WhatsApp/ticket) — via the channel / a Tool node"],
          tools: ["knowledge_search (brief + policy + rubric)"],
          guardrails: [
            "Hard cap on revision rounds (e.g. max 2) — the loop cannot run forever, so token cost is bounded",
            "The critic uses an EXPLICIT rubric, not open-ended vibes — reviews are consistent and auditable",
            "Separation of duties: the critic can FAIL but cannot approve-to-send; only the human Approval ships high-stakes output",
            "Grounding required: the generator must cite; the critic fails ungrounded content, so hallucinations don't slip through",
          ],
          cost:
            "Quality costs a bounded number of extra passes: 1 draft + up to N critique/revision rounds, where N is a hard cap (typically 1–2). Put the critic on a cheaper tier than the generator where you can. The round cap is the cost ceiling — no reflection loop can run away.",
          output:
            "Generator drafts → critic scores vs the rubric → Condition loops (capped) on FAIL → on PASS, high-stakes goes to human Approval → Output ships; every round + the rubric scores are logged for QA.",
        },
      },
      {
        id: "subworkflow-library",
        title: "Reusable sub-workflow library (build once, govern centrally)",
        scenario:
          "The same step — KYC/document verification, PII redaction, payment verification — is needed by a dozen workflows. Copy-pasting it means a compliance fix in one place silently misses the other eleven.",
        triggerLabel: "Any parent workflow (this is a shared module, not a standalone trigger)",
        steps: [
          { kind: "trigger", title: "Parent workflow runs", detail: "Any workflow (onboarding, lending, claims…) reaches the point where it needs the shared step." },
          { kind: "subworkflow", title: "Call the shared module", detail: "A Sub-workflow node invokes the governed 'KYC / document-verification' workflow — the single source of truth for that logic." },
          { kind: "agent", title: "(Inside) the module does its job", detail: "Within the sub-workflow, an agent verifies documents/identity against the current policy." },
          { kind: "approval", title: "(Inside) its own approval gate", detail: "The module enforces its OWN Approval step, so no parent workflow can bypass the human check." },
          { kind: "condition", title: "Return a clean verdict", detail: "The sub-workflow returns a structured pass/fail + evidence to the parent over a stable contract." },
          { kind: "output", title: "Parent continues", detail: "The parent proceeds on the returned verdict — consistently, everywhere it's used." },
        ],
        connectors: ["Sub-workflow", "Knowledge base", "Approval / HITL", "Tool (MCP → system of record)"],
        outcome: "Sensitive, regulated logic lives in ONE governed, versioned module that every workflow reuses — fix or update it once and every consumer inherits it.",
        impact: "Composition is the enterprise-standard answer to duplication and drift: one reviewed, versioned building block instead of a dozen copies that fall out of sync. It's DRY applied to automation, with governance baked in.",
        build: {
          agents: 1,
          agentsNote: "1 agent inside the shared sub-workflow; any number of parent workflows reuse it via a Sub-workflow node — the pattern is composition, not agent count.",
          pattern: "Sub-workflow composition (reusable, versioned module)",
          prompt:
            "(Module agent) Verify the submitted documents/identity strictly against the CURRENT policy in Knowledge. Return {verdict: PASS|FAIL|NEEDS_HUMAN, checks[{name, result, evidence}]}. Never approve a borderline case yourself — return NEEDS_HUMAN so the module's own Approval step decides.",
          agentRoles: [
            { role: "Verification agent (inside the module)", model: "Standard tier", prompt: "Run the documented checks against current policy; structured verdict with evidence; borderline → NEEDS_HUMAN, never self-approve." },
          ],
          mcp: ["System of record (KYC / identity provider) — via Tool (MCP)"],
          tools: ["knowledge_search (current policy — the ONE place to update)", "document parsing"],
          guardrails: [
            "Single source of truth: the compliance logic exists once — change it in the module and every parent inherits the change",
            "Versioned + reviewed: a change to the shared module is reviewed/versioned before rollout, so consumers never get a silent, unaudited change",
            "The module enforces its OWN Approval gate — a parent workflow cannot skip the human check by calling it",
            "Stable contract: it returns a structured verdict, so parents can't misread or partially apply the result",
          ],
          cost:
            "Build-and-govern-once beats maintain-a-dozen-copies: one model/prompt/guardrail set to tune, one place to optimize cost, and zero duplicated maintenance. Centralizing also lets you right-size the model for that step in exactly one place.",
          output:
            "Parent hits the Sub-workflow node → the module verifies (agent + policy) → its own Approval gates borderline cases → it returns a structured PASS/FAIL/NEEDS_HUMAN verdict + evidence → the parent continues on that verdict; the module's version + result are logged centrally.",
        },
      },
    ],
  },

];

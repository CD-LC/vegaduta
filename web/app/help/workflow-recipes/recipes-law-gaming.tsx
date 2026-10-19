import type { Industry } from "./recipes-data";
import { Gavel, Gamepad2 } from "lucide-react";

// ---------------------------------------------------------------------------
// Law-firm practice operations & Gaming/esports workflow recipes — same
// "inline copy, no i18n keys" precedent and honesty rules as recipes-data.tsx.
// Every recipe is expressed ONLY in VegaDūta's real building blocks (Trigger /
// Agent / Knowledge / Condition / Approval / Output / Tool(MCP) / Voice /
// Device / Loop / Code / Parallel), so each maps 1:1 to something you can
// actually build in the Workflow designer.
//
// (The "legal" industry in recipes-data.tsx covers CONTRACT REVIEW; this
// "law-firm" industry is deliberately distinct — it is about PRACTICE
// MANAGEMENT / operations: intake, conflict checks, onboarding/KYC, deadline
// reminders and time capture — not contract triage.)
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — practice-management / case-management
// systems, document-management systems (DMS), time-and-billing, the game
// backend / player database, payment processors — are reached the real way,
// via an inbound Webhook trigger and/or a Tool (MCP) node, and are LABELLED as
// such. Every consequential decision (matter acceptance, anything that is legal
// advice, refunds/chargebacks, moderation bans) goes through an Approval
// (human-in-the-loop) step. Impact lines describe the mechanism (why it helps),
// never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_PROOPS: Industry[] = [
  // ============================================================= LAW FIRMS
  {
    id: "law-firm",
    name: "Law firms & legal operations",
    icon: Gavel,
    tagline: "Automate client intake, conflict checks, onboarding and deadline reminders — with an attorney gating anything that's advice or matter acceptance.",
    recipes: [
      {
        id: "law-intake-conflict",
        title: "New-client intake & conflict check, with an attorney accept gate",
        scenario:
          "A prospective client calls or messages; today a paralegal keys their details into a form, manually searches existing clients and adverse parties for conflicts, and the enquiry waits days before a lawyer decides whether to take the matter.",
        triggerLabel: "WhatsApp / Call / Webhook (new enquiry or intake form)",
        steps: [
          { kind: "trigger", title: "Prospective client enquires", detail: "A WhatsApp, Call or Webhook trigger (from your website intake form) starts the flow in the enquirer's own words/language." },
          { kind: "agent", title: "Capture a structured intake", detail: "An Agent node collects the parties, matter type, jurisdiction, opposing side and a plain description into a clean intake record — the same fields every time, no missed questions." },
          { kind: "tool", title: "Run the conflict check", detail: "A Tool (MCP) node searches your practice-management / case-management system for existing clients and adverse parties — no first-party connector for these systems, MCP calls yours." },
          { kind: "condition", title: "Branch on the conflict result", detail: "A Condition node routes a clean check toward acceptance and any hit toward a mandatory human review before anything proceeds." },
          { kind: "approval", title: "Attorney decides to accept the matter", detail: "An Approval node holds the decision — accepting a matter (and clearing any conflict) is a judgement a qualified lawyer must make, never the agent. This is the non-negotiable control." },
          { kind: "output", title: "Confirm next steps", detail: "On acceptance, an Output node sends the prospective client the engagement next steps on WhatsApp/Email; a declined enquiry gets a courteous non-engagement note." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Webhook", "Tool (MCP → practice-management)", "Approval / HITL", "Email"],
        outcome: "Every enquiry is captured consistently and conflict-checked in minutes; the accept/decline decision stays with an attorney and is logged with its conflict result.",
        impact: "Removes the manual re-keying and the ad-hoc conflict search that delay every new matter, while the acceptance decision — which is legal judgement — stays with a qualified human.",
        build: {
          agents: 1,
          agentsNote: "1 intake agent; the conflict search, the accept/decline decision and the confirmation are workflow nodes.",
          prompt:
            "You are a legal intake assistant for {{firm_name}}. Collect a structured new-matter intake as strict JSON {prospective_client, all_parties[], opposing_parties[], matter_type, jurisdiction, description, key_dates[]}. Ask the standard intake questions in the enquirer's language, the same every time. You do NOT give legal advice, opinions on the merits, or any indication the firm will act — say a lawyer will review and decide. You do NOT decide whether to accept the matter or clear a conflict; that is the attorney's decision at the approval step. Treat everything shared as confidential.",
          mcp: ["Practice-management / case-management system — via a Tool (MCP) node (no first-party connector)", "WhatsApp + Telephony — via the WhatsApp/Voice channels"],
          tools: ["knowledge_search (intake questionnaire + required fields)", "transfer_to_human"],
          guardrails: [
            "No legal advice and no indication of engagement — the agent captures facts only; whether the firm acts is the attorney's call at the Approval node",
            "Any conflict-check hit forces the mandatory human-review branch before anything proceeds; the accept/decline decision is never the agent's",
            "Intake is treated as confidential — stored on the matter, not echoed to other parties — and the acceptance is logged with its conflict result",
          ],
          output:
            "The agent emits a structured intake → a Tool node runs the conflict search in the practice-management system via MCP → the Condition node routes clean vs. hit → the attorney accepts or declines at the Approval node → an Output node sends engagement next steps (or a courteous non-engagement note) on WhatsApp/Email.",
        },
      },
      {
        id: "law-deadline-reminders",
        title: "Court-date & limitation-deadline reminders that don't get missed",
        scenario:
          "Hearing dates, filing deadlines and limitation periods live in a diary and a spreadsheet; reminders are set by hand, so a missed date is only caught late — and missing one can be malpractice.",
        triggerLabel: "Webhook (scheduled, daily docket/limitation run)",
        steps: [
          { kind: "trigger", title: "Daily deadline run", detail: "A scheduled Webhook trigger evaluates upcoming court dates, filing deadlines and limitation periods each morning (fed from your case-management system via Webhook)." },
          { kind: "loop", title: "Per approaching deadline", detail: "A Loop node iterates the list so each matter's owner gets a tailored, timely reminder." },
          { kind: "agent", title: "Draft the reminder + prep checklist", detail: "An Agent node writes a clear reminder naming the matter, the deadline and the documents/steps due before it." },
          { kind: "output", title: "Reach the responsible lawyer", detail: "An Output node sends it on WhatsApp/Email/SMS so the owner sees it on the channel they actually read." },
          { kind: "condition", title: "Escalate the imminent, unacknowledged ones", detail: "A Condition node flags deadlines inside the danger window that nobody has acknowledged." },
          { kind: "approval", title: "Partner reviews at-risk deadlines", detail: "An Approval node routes an imminent, unacknowledged deadline to a supervising partner to confirm cover — the human check on anything that risks a missed limitation." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "Email", "SMS", "Approval / HITL"],
        outcome: "Every court date and limitation period gets a timely, specific reminder, and anything at risk is escalated to a partner before it slips.",
        impact: "Consistent, per-matter reminders are what prevent missed deadlines — the manual diary skips exactly the dates a busy lawyer forgets, which is the dangerous case.",
        build: {
          agents: 1,
          agentsNote: "1 drafting agent per deadline; the per-matter Loop, the escalation Condition and the partner Approval are workflow nodes.",
          prompt:
            "You are a legal deadlines assistant. For each deadline record {matter, deadline_type, due_date, responsible_lawyer, required_steps[]}, write a clear, specific reminder naming the matter, the exact date, and the documents or steps due before it. Use ONLY the dates supplied by the docket run — never calculate, adjust, or infer a limitation or filing date yourself, and never state a deadline you weren't given. Do not give legal advice on the matter. Flag, don't reassure: if a record looks incomplete, say so.",
          mcp: ["Case-management / docketing system — deadlines fed inbound via Webhook (no first-party connector)", "WhatsApp + SMS + Email — via the messaging channels"],
          tools: ["knowledge_search (filing checklists by matter type)"],
          guardrails: [
            "Never computes or adjusts a limitation/filing date — it only relays the dates the docketing run supplies, so a reminder can't itself introduce a wrong date",
            "An imminent, unacknowledged deadline is escalated to the supervising partner at the Approval node to confirm cover — the human check against a missed limitation",
            "No legal advice on the matter; reminders name the responsible lawyer and are logged as sent/acknowledged for audit",
          ],
          output:
            "A Loop node runs the agent per approaching deadline → an Output node delivers the reminder + prep checklist on WhatsApp/Email/SMS → the Condition node flags imminent, unacknowledged ones → the partner confirms cover at the Approval node.",
        },
      },
    ],
  },

  // ============================================================== GAMING
  {
    id: "gaming",
    name: "Gaming & esports",
    icon: Gamepad2,
    tagline: "Deflect routine player tickets, triage community moderation and verify account recovery — with refunds and bans always human-approved.",
    recipes: [
      {
        id: "gaming-support-deflect",
        title: "Player support triage with self-serve deflection",
        scenario:
          "Login, purchase and bug tickets flood in across chat and email at all hours; support staff answer the same 'I can't log in' and 'my purchase didn't arrive' questions by hand, and real bugs get buried.",
        triggerLabel: "Discord / WhatsApp / Email / Webhook (in-game ticket)",
        steps: [
          { kind: "trigger", title: "Player reports an issue", detail: "A Discord, WhatsApp, Email or Webhook trigger (from your in-game support form) catches the ticket in the player's own words." },
          { kind: "agent", title: "Classify the issue", detail: "An Agent node sorts it into login / purchase / bug / other and pulls out the useful details (platform, order id, error)." },
          { kind: "knowledge", title: "Answer the common ones", detail: "A Knowledge node grounds the reply in your help articles and known-issue notes — login and how-to questions get an accurate, cited self-serve answer instead of a queue." },
          { kind: "condition", title: "Split solved from needs-a-human", detail: "A Condition node closes the deflected tickets and routes purchase failures and real bugs onward." },
          { kind: "tool", title: "Check the player's account", detail: "A Tool (MCP) node looks up the account/purchase state in the game backend — no first-party connector for the game backend, MCP calls yours." },
          { kind: "output", title: "Escalate with full context", detail: "An Output node opens a bug or purchase ticket for a human agent with the account state and repro already attached, so the player never repeats themselves." },
        ],
        connectors: ["Discord", "WhatsApp", "Email", "Webhook", "Knowledge base", "Tool (MCP → game backend)"],
        outcome: "The repetitive login and how-to tickets answer themselves accurately; purchase failures and real bugs reach a human already enriched with account context.",
        impact: "Removes the highest-volume, lowest-value ticket type from the human queue so staff spend their time on the genuine bugs, not password resets.",
        build: {
          agents: 1,
          agentsNote: "1 support agent; the deflect/escalate split, the account lookup and the enriched hand-off are workflow nodes.",
          prompt:
            "You are a player-support agent for {{game_name}}. Classify each ticket as {login | purchase | bug | other} and pull the useful details (platform, order id, error text). Answer login and how-to questions ONLY from the help articles in Knowledge, with the relevant steps — if it isn't covered, say so and escalate. You do not issue refunds, change entitlements, or alter accounts; a purchase failure or a real bug is opened for a human with the account state attached. Reply in the player's language, and never share another player's data.",
          mcp: ["Game backend / player database — via a Tool (MCP) node (no first-party connector)", "Discord + WhatsApp + Email — via the messaging channels"],
          tools: ["knowledge_search (help articles + known-issue notes)", "transfer_to_human"],
          guardrails: [
            "Grounded-only: self-serve answers must come from the help articles with the actual steps, or the ticket is escalated — no invented fixes",
            "No refunds, entitlement changes or account edits over chat — purchase disputes route to the refund flow, real bugs to a human queue",
            "Only ever exposes the requesting player's own account data; the hand-off carries account state + repro so the player never repeats themselves",
          ],
          output:
            "The agent classifies and, where covered, answers from Knowledge → the Condition node closes deflected tickets and routes the rest → a Tool node reads account/purchase state from the game backend via MCP → an Output node opens an enriched bug/purchase ticket for a human agent.",
        },
      },
      {
        id: "gaming-refund-approval",
        title: "Refund / chargeback intake with mandatory human approval",
        scenario:
          "Players request refunds for purchases and dispute charges across channels; refunds are fraud-prone and high-stakes, so they should be fast to assemble but never issued automatically.",
        triggerLabel: "Discord / WhatsApp / Email / Webhook (refund or chargeback)",
        steps: [
          { kind: "trigger", title: "Refund requested", detail: "A Discord, WhatsApp, Email or Webhook trigger (or a chargeback notice from your payment processor) starts the flow." },
          { kind: "agent", title: "Assemble the case", detail: "An Agent node pulls the purchase, the player's spend/entitlement history and the stated reason into a one-screen summary for a reviewer." },
          { kind: "tool", title: "Pull the transaction record", detail: "A Tool (MCP) node fetches the order and payment detail from the game backend / payment processor — reached via MCP, not a native connector." },
          { kind: "approval", title: "Finance approves the refund", detail: "An Approval node holds the refund until a human explicitly approves — money moving out is the non-negotiable control, never automatic." },
          { kind: "tool", title: "Issue the refund", detail: "On approval, a Tool (MCP) node calls the payment processor's refund API (Razorpay UPI where that's the rail) and adjusts the entitlement in the game backend." },
          { kind: "output", title: "Tell the player", detail: "An Output node confirms the outcome and timeline on the channel the request came in on." },
        ],
        connectors: ["Discord", "WhatsApp", "Email", "Webhook", "Tool (MCP → game backend/payment)", "Razorpay UPI", "Approval / HITL"],
        outcome: "Refund cases are assembled in seconds with the full purchase history attached; the money decision is made in minutes by a person and logged with a clear approver.",
        impact: "Combines speed with control: the mechanical evidence-gathering is instant, while every refund and chargeback stays a human decision with a full trail.",
        build: {
          agents: 1,
          agentsNote: "1 case-assembly agent; the transaction lookup, the finance Approval and the refund execution are workflow nodes.",
          prompt:
            "You are a refunds case assistant for {{game_name}}. Given a refund or chargeback request, assemble a one-screen case as JSON {player, order, amount, purchase_date, prior_refunds, spend_history_summary, stated_reason, policy_notes}. Present the facts only. You do NOT approve, deny, or promise a refund — tell the player their request is under review; the decision is a human finance approval. Never state a refund has been issued unless the workflow confirms it after approval. Don't speculate about fraud; surface the history and let the reviewer judge.",
          mcp: ["Game backend + payment processor — via a Tool (MCP) node (no first-party connector)", "Razorpay UPI — refund rail where applicable", "Discord/WhatsApp/Email — via the messaging channels"],
          tools: ["http_request (fetch order + payment record)"],
          guardrails: [
            "Money out is never automatic — every refund/chargeback waits for the finance Approval node; the agent only assembles evidence",
            "The agent never tells a player a refund is approved or issued until the workflow confirms it post-approval",
            "Refund execution is keyed to the payment id so an approved refund is issued once — no double refunds on a retried request",
          ],
          output:
            "The agent assembles the case → a Tool node pulls the order/payment record via MCP → finance approves or denies at the Approval node → on approval a Tool node calls the payment processor's refund API (Razorpay UPI where that's the rail) and adjusts the entitlement → an Output node tells the player the outcome and timeline.",
        },
      },
      {
        id: "gaming-mod-recovery",
        title: "Community moderation triage & account-recovery verification",
        scenario:
          "Discord reports of toxicity and cheating are read manually and inconsistently, and account-recovery requests are handled ad-hoc over DMs — both risk either a wrong ban or handing an account to an impersonator.",
        triggerLabel: "Discord / Webhook (report or recovery request)",
        steps: [
          { kind: "trigger", title: "Report or recovery request arrives", detail: "A Discord trigger (community report) or a Webhook (account-recovery form) starts the relevant flow." },
          { kind: "agent", title: "Triage + gather evidence", detail: "An Agent node classifies a moderation report by severity and pulls the message context, or — for recovery — collects the ownership-proof answers into a structured case." },
          { kind: "knowledge", title: "Apply the policy", detail: "A Knowledge node grounds the assessment in your community guidelines and recovery/verification policy — with citations, not a guess." },
          { kind: "condition", title: "Route by risk", detail: "A Condition node lets a low-risk warning proceed while sending bans and identity-sensitive recoveries to a human." },
          { kind: "approval", title: "Moderator confirms bans & account handovers", detail: "An Approval node holds any ban or account handover for a human — banning a player or handing over an account is a consequential decision a person must own." },
          { kind: "output", title: "Action + notify", detail: "On approval, an Output node applies the decision and notifies the player, with the policy basis and appeal path recorded." },
        ],
        connectors: ["Discord", "Webhook", "Knowledge base", "Approval / HITL"],
        outcome: "Reports and recovery requests are triaged consistently against policy in minutes; bans and account handovers are always confirmed by a moderator and logged with their basis.",
        impact: "Removes the inconsistent manual reading that lets toxicity slide or bans the wrong person, while keeping every irreversible action — a ban, an account handover — a human decision.",
        build: {
          agents: 1,
          agentsNote: "1 triage agent handles both report and recovery cases; the risk routing, the moderator Approval and the action are workflow nodes.",
          prompt:
            "You are a community-safety and account-recovery assistant for {{game_name}}. For a moderation report, classify severity and gather the message context into a case; for an account-recovery request, collect the ownership-proof answers into a structured case. Assess strictly against the community guidelines and recovery policy in Knowledge, citing the rule. You do NOT ban a player, apply a sanction, or hand over an account — you recommend and route; the moderator decides at the approval step. For recovery, never approve a handover on weak proof; if identity is unclear, flag it for human verification.",
          mcp: ["Discord — moderation actions via the Discord channel", "Player-account system — via a Tool (MCP) node for account handovers (no first-party connector)"],
          tools: ["knowledge_search (community guidelines + recovery/verification policy)"],
          guardrails: [
            "No ban, sanction, or account handover is ever the agent's — every one is confirmed by a moderator at the Approval node and logged with its policy basis",
            "Assessments are grounded in the guidelines/recovery policy with a citation, not a guess; a low-risk warning may proceed, bans and identity-sensitive recoveries may not",
            "Account handovers require sufficient ownership proof; anything ambiguous is routed to human identity verification, never auto-approved",
          ],
          output:
            "The agent triages the report or recovery case against policy → the Condition node routes by risk → the moderator confirms any ban or account handover at the Approval node → on approval an Output/Tool step applies the decision and notifies the player, with the policy basis and appeal path recorded.",
        },
      },
    ],
  },
];

import type { Industry } from "./recipes-data";
import { Ship, ShieldCheck, Package } from "lucide-react";

// ---------------------------------------------------------------------------
// Shipping / Freight, Insurance broking & D2C workflow recipes — same "inline
// copy, no i18n keys" precedent and honesty rules as recipes-data.tsx. Every
// recipe is expressed ONLY in VegaDūta's real building blocks (Trigger / Agent /
// Knowledge / Condition / Approval / Output / Tool(MCP) / Voice / Device / Loop /
// Code / Parallel), so each maps 1:1 to something you can actually build in the
// Workflow designer, and every flagship recipe carries a full `build` spec.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — port/carrier EDI, forwarding TMS,
// customs systems, insurer portals, broking/policy-admin systems, OMS/3PL — are
// reached the real way, via an inbound Webhook trigger and/or a Tool (MCP) node,
// and are LABELLED as such. Only real channels are used (Webhook, WhatsApp, SMS,
// Voice/Call, Email, ElevenLabs, Sarvam, Razorpay UPI). Every binding /
// financial / coverage decision (advice, a bound quote, a coverage position, a
// refund/exchange) passes an Approval (human-in-the-loop) step. Impact lines
// describe the mechanism (why it helps), never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_TRADE: Industry[] = [
  // ================================================= PORTS, SHIPPING & FREIGHT
  {
    id: "shipping",
    name: "Ports, shipping & freight forwarding",
    icon: Ship,
    tagline: "Proactive milestone updates, demurrage warnings and clean document files — without a coordinator refreshing a carrier portal all day.",
    recipes: [
      {
        id: "shipping-eta",
        title: "Milestone & ETA updates with demurrage / detention alerts",
        scenario:
          "A coordinator refreshes carrier and port portals all day, then emails consignees when a vessel slips or a box lands — and free time on containers quietly runs out before anyone flags the demurrage and detention risk, so charges land as a surprise.",
        triggerLabel: "Webhook (carrier / port EDI milestone event)",
        steps: [
          { kind: "trigger", title: "Milestone event arrives", detail: "A Webhook trigger receives each tracking/EDI event (gate-in, loaded, sailed, discharged, gated-out). Carrier and port systems have no first-party connector — the EDI/tracking feed pushes in via Webhook (or a Tool/MCP node that polls their API)." },
          { kind: "knowledge", title: "Load the shipment's free-time terms", detail: "A Knowledge node holds the booking's free-time / detention rules and the consignee's contacts, so the flow reasons against this shipment's actual terms — not a generic guess." },
          { kind: "condition", title: "Only act when it's material", detail: "A Condition node branches: a routine event is logged; an ETA slip, an arrival, or a container nearing the end of its free time continues to the outreach path — including a demurrage/detention warning window." },
          { kind: "agent", title: "Write the update + the warning", detail: "An Agent node turns the raw event into a plain-language update for the consignee, and — where free time is running short — a clear demurrage/detention heads-up with the last-free-day and the action needed." },
          { kind: "output", title: "Notify the consignee proactively", detail: "An Output node reaches the consignee on WhatsApp/Email/SMS before they chase you — and before the charge clock runs out." },
        ],
        connectors: ["Webhook (carrier/port EDI)", "Knowledge base", "WhatsApp", "Email", "SMS"],
        outcome: "Consignees hear about every milestone, ETA change and looming demurrage window from you first, in time to act — instead of chasing a coordinator or discovering a charge after the fact.",
        impact: "Replaces all-day manual portal-watching with event-driven, terms-aware alerts; the value is catching the free-time deadline before it becomes a charge, not a hands-off ops bot.",
        build: {
          agents: 1,
          agentsNote: "1 update-writer agent; the free-time reasoning and the materiality branch are Knowledge and Condition nodes, not extra agents.",
          prompt:
            "You are a freight status assistant. Given a shipment milestone event {shipment_ref, event_type, event_time, location, current_eta, last_free_day?} and the shipment's free-time terms from Knowledge, write a short, calm update for the consignee in their language. If the container is within the demurrage/detention warning window, state the last free day and the specific action needed, clearly. Never invent an ETA, a charge amount, or a last-free-day that isn't in the input or Knowledge — if a value is missing, say it's being confirmed. Do not promise a delivery date the carrier hasn't given.",
          mcp: ["Carrier / port EDI + tracking — inbound via Webhook trigger (no first-party connector)", "Forwarding TMS lookup — via a Tool (MCP) node"],
          tools: ["knowledge_search (free-time / detention terms + consignee contacts)", "http_request (pull shipment detail)"],
          guardrails: [
            "No invented ETAs, charges or last-free-days — the agent only relays values present in the event or Knowledge, else it defers ('being confirmed')",
            "Materiality gate at the Condition node — routine events are logged, not messaged, so consignees aren't spammed on every gate scan",
            "Idempotency on event id — a re-sent EDI event never double-notifies the consignee",
          ],
          output:
            "Milestone event → Condition filters for material changes + demurrage-window risk → the agent's update text is sent on WhatsApp/Email/SMS within seconds, keyed idempotently on the event id, with the raw event stored for audit.",
        },
      },
      {
        id: "shipping-docs",
        title: "Shipping-document collection & customs-status desk",
        scenario:
          "For every shipment someone emails back and forth for the bill of lading, commercial invoice and packing list, checks each one by eye, and separately fields 'has it cleared customs yet?' enquiries by hand — incomplete files stall the entry and the same status question gets asked five times.",
        triggerLabel: "Webhook (shipment created) or WhatsApp (shipper/consignee message)",
        steps: [
          { kind: "trigger", title: "Docs due, or a status question", detail: "A Webhook trigger (shipment created / docs-due in your forwarding TMS) or a WhatsApp message from the shipper/consignee starts the flow." },
          { kind: "knowledge", title: "Ground answers in this shipment's record", detail: "A Knowledge node holds the shipment's document checklist and its customs/clearance status, so status enquiries are answered from the real record — with citations — not a guess." },
          { kind: "agent", title: "Capture & validate each document", detail: "An Agent node requests, reads and validates the bill of lading, commercial invoice and packing list — checking they match the booking (consignee, HS/commodity, quantities) rather than just accepting a PDF." },
          { kind: "condition", title: "Completeness gate", detail: "A Condition node won't advance the file until every mandatory document is present, legible and consistent; anything missing or mismatched loops back with a specific ask." },
          { kind: "approval", title: "Customs broker signs off the set", detail: "An Approval node hands the complete, validated document set to a human customs broker/ops to confirm before anything is filed — the entry decision is never automated. This is the control." },
          { kind: "tool", title: "File to the forwarding / customs system", detail: "On approval, a Tool (MCP) node writes the document set and status into your forwarding TMS or customs system — reached via MCP, not a native connector." },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Knowledge base", "Approval / HITL", "Tool (MCP → TMS/customs)"],
        outcome: "Document files arrive complete, validated and consistent, and consignees get accurate customs-status answers on demand; ops touches only the approval, and nothing is filed without a human sign-off.",
        impact: "Removes the email ping-pong for documents and the repetitive 'has it cleared yet?' enquiries, while the customs filing stays a human decision with a full audit trail.",
        build: {
          agents: 1,
          agentsNote: "1 document-intake agent; the customs-status Q&A is grounded by the Knowledge node, and filing is gated by Approval + a Tool node.",
          prompt:
            "You are a freight-documentation assistant. For a shipment, request and validate the required documents (bill of lading, commercial invoice, packing list). Extract strict JSON {doc_type, present: boolean, legible: boolean, matches_booking: boolean, mismatches[]} for each. Copy values exactly — never 'fix' a quantity, HS code or consignee to make it match; flag the mismatch instead. When answering a customs-status question, answer ONLY from the shipment record in Knowledge, cite it, and if the status isn't in the record say it's being confirmed — never assert a clearance that isn't recorded.",
          mcp: ["Forwarding TMS / customs system — via a Tool (MCP) node (no first-party connector)", "Document uploads — accepted in the WhatsApp/Email channel"],
          tools: ["document parsing (PDF/scan → text)", "knowledge_search (doc checklist + customs status)"],
          guardrails: [
            "Completeness gate — the Condition node blocks the file from advancing until every mandatory document is present, legible and booking-consistent",
            "No filing without the Approval node — a human customs broker confirms the set before the Tool node writes to the customs/TMS system",
            "Grounded status only — customs-status answers come from the shipment record with a citation, or defer to 'being confirmed'; the agent never asserts a clearance",
          ],
          output:
            "Documents captured in-channel → the agent validates each against the booking → Condition enforces the completeness gate → the customs broker approves at the Approval node → a Tool node files the set into the TMS/customs system via MCP; customs-status questions are answered inline from the Knowledge-grounded record.",
        },
      },
    ],
  },

  // ================================================= INSURANCE BROKING
  {
    id: "insurance-broking",
    name: "Insurance broking",
    icon: ShieldCheck,
    tagline: "Chase renewals, collect policy docs, take first-notice claims and answer coverage questions — with every recommendation and coverage position gated to a licensed broker.",
    recipes: [
      {
        id: "broking-renewal",
        title: "Renewal reminders + quote-comparison intake",
        scenario:
          "Policies lapse because nobody had time to flag the renewal, then a broker manually emails the client for updated details, re-keys the requirement, and rebuilds a quote comparison by hand for the tenth time this month.",
        triggerLabel: "Webhook (scheduled, renewal run)",
        steps: [
          { kind: "trigger", title: "Renewal run", detail: "A scheduled Webhook trigger evaluates policies nearing expiry each morning (the book is fed from your broking / policy-admin system via Webhook — no first-party connector)." },
          { kind: "loop", title: "Per client due for renewal", detail: "A Loop node iterates the due list so each client gets a personal, timely reminder rather than a batch blast." },
          { kind: "output", title: "Remind + request updated details", detail: "An Output node reaches the client on WhatsApp/Email with the renewal date and asks for the updated details and policy documents needed to requote." },
          { kind: "agent", title: "Build the quote-comparison intake", detail: "An Agent node structures the client's updated requirement and the collected documents, and assembles the insurer quotes into a clean side-by-side comparison — as information, not a recommendation." },
          { kind: "condition", title: "Completeness gate before quoting", detail: "A Condition node won't proceed to a comparison until every mandatory detail and document is present, so quotes aren't built on gaps." },
          { kind: "approval", title: "Broker signs off before it reaches the client", detail: "An Approval node routes the comparison and any recommendation to a licensed broker — advice and any bound quote are never sent by the agent. This is the non-negotiable control." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "Email", "Knowledge base", "Approval / HITL"],
        outcome: "Every renewal is flagged and chased on time with the requote intake assembled automatically; brokers spend their time on the advice and the comparison, not on reminders and re-keying — and no recommendation goes out unreviewed.",
        impact: "Removes the manual renewal chase and the copy-paste rebuild of comparisons, while the advice and any bound quote stay a licensed-broker decision.",
        build: {
          agents: 1,
          agentsNote: "1 intake/comparison agent; the reminder loop is a workflow node and the advice decision is the broker's Approval step.",
          prompt:
            "You are an insurance-broking assistant. For a client due for renewal, collect the updated requirement and documents, and assemble the insurer quotes you are given into a neutral side-by-side comparison as strict JSON {client_ref, requirement, quotes[{insurer, premium, sum_insured, key_terms}], missing[]}. Present facts only — do NOT recommend an insurer, rank 'best value', or state anything is the right cover; that is the broker's decision. Copy premiums and terms exactly from the quotes provided; never estimate a premium. If a mandatory detail is missing, list it in missing[] and do not proceed.",
          mcp: ["Broking / policy-admin system — inbound via Webhook trigger (no first-party connector)", "Insurer quote data — via a Tool (MCP) node where an API exists, otherwise entered by the broker"],
          tools: ["knowledge_search (product rules + client policy record)", "document parsing (policy schedules/proposals)"],
          guardrails: [
            "No advice from the agent — it assembles a neutral comparison; recommending, ranking or binding a quote is blocked and routed to the broker Approval node",
            "Completeness gate — the Condition node prevents a comparison being built on missing details or documents",
            "Exact premiums only — the agent copies quoted figures and never estimates or 'adjusts' a premium",
          ],
          output:
            "Renewal run → Loop reminds each due client and collects the requirement + docs → the agent builds a neutral quote comparison → Condition enforces the completeness gate → the licensed broker reviews and adds the recommendation at the Approval node before anything reaches the client.",
        },
      },
      {
        id: "broking-fnol",
        title: "Claim first-notice hand-off + coverage Q&A",
        scenario:
          "A client messages to report a loss and to ask 'am I even covered for this?'; today a broker captures the notice by hand, reads the policy wording to answer, then emails the insurer to open the claim — slow, and coverage answers vary by whoever picks it up.",
        triggerLabel: "WhatsApp / Call / Webhook (loss reported)",
        steps: [
          { kind: "trigger", title: "Loss reported", detail: "A WhatsApp, Call or Webhook trigger opens the claim in the client's own words and language." },
          { kind: "agent", title: "Capture the structured first-notice", detail: "An Agent node collects policy no., date and time of loss, description, third parties and severity into a clean, consistent first-notice-of-loss record." },
          { kind: "knowledge", title: "Coverage Q&A from the policy wording", detail: "A Knowledge node answers 'is this covered?' strictly from the client's policy wording — with citations, framed as indicative only, never a confirmed coverage position." },
          { kind: "condition", title: "Completeness gate", detail: "A Condition node won't advance the notice until the mandatory details and any required photos/documents are captured and legible." },
          { kind: "approval", title: "Broker confirms the coverage position", detail: "An Approval node hands the complete notice and the indicative coverage read to a licensed broker to confirm before hand-off — a coverage position is never asserted by the agent. This is the control." },
          { kind: "tool", title: "Hand off to the insurer", detail: "On approval, a Tool (MCP) node lodges the first-notice into the insurer's portal/claims system — reached via MCP or an outbound Webhook, not a native connector." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Sarvam (multilingual)", "Knowledge base", "Approval / HITL", "Tool (MCP → insurer portal)"],
        outcome: "Losses are captured complete and consistent the moment they're reported, clients get an instant indicative coverage read, and the notice reaches the insurer fast — while the coverage position stays a licensed-broker decision.",
        impact: "Removes the manual capture and the days before a claim is lodged, and makes coverage answers consistent and cited, without letting the agent make the coverage call.",
        build: {
          agents: 1,
          agentsNote: "1 FNOL-capture agent; the coverage read is Knowledge-grounded and the coverage position is the broker's Approval step.",
          prompt:
            "You are an insurance first-notice-of-loss assistant. Capture the loss into strict JSON {policy_no, loss_datetime, description, third_parties[], severity, documents[]}. Then answer any coverage question ONLY from the client's policy wording in Knowledge, cite the clause, and state clearly that this is indicative and subject to broker/insurer confirmation. Never confirm that a loss is covered or will be paid, never quote a settlement figure, and never advise the client to admit or deny liability. If the wording is silent or unclear, say so and route to the broker.",
          mcp: ["Insurer portal / claims system — via a Tool (MCP) node or outbound Webhook (no first-party connector)", "Policy wordings — held in the Knowledge base"],
          tools: ["knowledge_search (policy wording + schedule)", "document parsing (photos / loss evidence)"],
          guardrails: [
            "Indicative-only coverage — the agent answers from the cited policy wording and explicitly flags it as not a confirmed position; a confirmed coverage position requires the broker Approval node",
            "Completeness gate — the Condition node blocks hand-off until mandatory details and evidence are captured and legible",
            "No liability or settlement statements — the agent never confirms payment, quotes a figure, or advises admitting/denying liability; those route to the broker",
          ],
          output:
            "Loss reported in-channel → the agent captures a structured FNOL and gives a cited, indicative coverage read → Condition enforces the completeness gate → the licensed broker confirms the coverage position at the Approval node → a Tool node lodges the notice into the insurer's portal via MCP, with the transcript and evidence attached.",
        },
      },
    ],
  },

  // ================================================= D2C CONSUMER BRANDS
  {
    id: "d2c",
    name: "D2C consumer brands",
    icon: Package,
    tagline: "Handle post-purchase support, tracking, returns and restock nudges on WhatsApp — with every refund or exchange gated to a human.",
    recipes: [
      {
        id: "d2c-support",
        title: "Post-purchase WhatsApp support & order tracking",
        scenario:
          "After every order the inbox fills with 'where's my order?', 'how do I use this?' and 'is size M back in stock?' — cheap questions individually, but a full-time job at launch volume, and slow replies read as a bad brand.",
        triggerLabel: "WhatsApp (inbound message)",
        steps: [
          { kind: "trigger", title: "Customer messages", detail: "A WhatsApp trigger starts on any inbound post-purchase message." },
          { kind: "agent", title: "Understand intent + identify the order", detail: "An Agent node classifies the intent (tracking, product help, stock, other) and extracts the order id / phone to identify the order." },
          { kind: "tool", title: "Look up live status", detail: "A Tool (MCP) node fetches the real order and shipment status from your OMS / 3PL / carrier — no first-party OMS connector, MCP calls yours." },
          { kind: "knowledge", title: "Ground product & policy answers", detail: "A Knowledge node answers product care, sizing, shipping and returns-policy questions strictly from your own content — with citations, no invented claims — and can offer a back-in-stock alert on an out-of-stock item." },
          { kind: "output", title: "Reply on WhatsApp", detail: "An Output node sends the answer in the customer's language and, for anything out of scope or upset, hands off to a human with the context attached." },
        ],
        connectors: ["WhatsApp", "Tool (MCP → OMS/3PL)", "Knowledge base", "Sarvam (multilingual)"],
        outcome: "The commonest post-purchase questions answer themselves instantly, day or night, with a human reached only for what genuinely needs one.",
        impact: "Removes the highest-volume, lowest-value inbox work from a small team, on the channel customers actually read, without the agent inventing product claims.",
        build: {
          agents: 1,
          agentsNote: "1 support agent; the live-status lookup and the human hand-off are workflow nodes.",
          prompt:
            "You are a post-purchase support agent for {{brand}}. Classify the message as {tracking | product_help | stock | returns | other}, identify the order, and answer in the customer's language. For tracking, use only the status the tool returns — never guess a delivery date. For product and policy questions, answer ONLY from Knowledge and cite it; if it isn't there, say so and offer a human. Never invent a product claim, ingredient, or policy. If the customer is upset or the request is out of scope, hand off to a human.",
          mcp: ["OMS / 3PL / carrier — via a Tool (MCP) node (no first-party connector)", "WhatsApp — send/receive channel"],
          tools: ["http_request (live order + shipment status)", "knowledge_search (product care, sizing, shipping & returns policy)"],
          guardrails: [
            "Grounded-only — product and policy answers come from Knowledge with a citation, or the agent defers to a human; no invented claims",
            "No guessed delivery dates — tracking answers use only the status the OMS/carrier tool returns",
            "Auto-hand-off on upset or out-of-scope intent, carrying the conversation context so the customer doesn't repeat themselves",
          ],
          output:
            "Inbound WhatsApp → the agent classifies intent and identifies the order → a Tool node pulls live status via MCP and Knowledge grounds product/policy answers → the reply is sent on WhatsApp in the customer's language, with upset or out-of-scope threads handed to a human with full context.",
        },
      },
      {
        id: "d2c-returns",
        title: "Returns / exchange intake with a refund approval",
        scenario:
          "Return requests arrive by DM and email, someone eyeballs each against the return window and condition, refunds get issued inconsistently, and the follow-up review request never gets sent — so a good return never becomes a good review.",
        triggerLabel: "WhatsApp (return request) or Webhook",
        steps: [
          { kind: "trigger", title: "Return requested", detail: "A WhatsApp trigger (or a Webhook from your store/help desk) starts the flow." },
          { kind: "agent", title: "Structure the request", detail: "An Agent node identifies the order and item and captures the reason, preferred resolution (refund or exchange), and any required photos into a clean record." },
          { kind: "knowledge", title: "Check the return policy", detail: "A Knowledge node checks the request against your return window and eligibility rules — with citations — so the policy is applied consistently, not by feel." },
          { kind: "condition", title: "Completeness & eligibility gate", detail: "A Condition node won't advance until the mandatory details and photos are in and the item is within policy; edge cases branch to review." },
          { kind: "approval", title: "Team approves the refund / exchange", detail: "An Approval node holds every refund or exchange for a human — the money/inventory decision is never automated. This is the control." },
          { kind: "output", title: "Confirm, then invite a review", detail: "On approval a Tool (MCP) node creates the RMA / issues the refund via your OMS + Razorpay UPI, an Output node confirms it on WhatsApp, and once the return is resolved it invites the customer to leave a review." },
        ],
        connectors: ["WhatsApp", "Knowledge base", "Approval / HITL", "Tool (MCP → OMS)", "Razorpay UPI"],
        outcome: "Returns and exchanges are captured completely and judged against the same policy, refunds are issued only with a human sign-off, and a resolved return automatically turns into a review request.",
        impact: "Removes the inconsistent manual triage and the missed review follow-up, while every refund and exchange stays a human decision with a full trail.",
        build: {
          agents: 1,
          agentsNote: "1 returns-intake agent; the policy check, the approval gate and the refund/RMA + review ask are workflow nodes.",
          prompt:
            "You are a returns assistant for {{brand}}. Identify the order and item, and capture strict JSON {order_id, item, reason, resolution: REFUND|EXCHANGE, photos[], within_policy: boolean, policy_citation}. Judge within_policy ONLY from the return rules in Knowledge and cite them; if the request is outside the window or ineligible, say so plainly rather than promising a refund. Never confirm a refund or exchange as approved — that is a human decision. Do not state a refund amount as final until the approval step has run.",
          mcp: ["OMS / store platform (Shopify/WooCommerce/etc.) — via a Tool (MCP) node (no first-party connector)", "Razorpay UPI — refund issued via a Tool (MCP) node on the payments API", "WhatsApp — send/receive channel"],
          tools: ["knowledge_search (return window + eligibility rules)", "http_request (order + payment lookup)"],
          guardrails: [
            "No refund or exchange without the Approval node — the agent captures and checks policy but never issues money or inventory; a human signs off every case",
            "Policy applied consistently — within-window/eligibility is judged only from cited Knowledge rules, not the agent's discretion",
            "Completeness gate — the Condition node blocks advancement until mandatory details and photos are present; the review ask only fires after the return is resolved",
          ],
          output:
            "Return requested on WhatsApp → the agent structures the request and checks it against the cited return policy → Condition enforces the completeness/eligibility gate → the team approves the refund/exchange at the Approval node → a Tool node creates the RMA and issues the refund via the OMS + Razorpay UPI, Output confirms it, and a review invitation is sent once resolved.",
        },
      },
    ],
  },
];

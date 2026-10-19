import type { Industry } from "./recipes-data";
import { Gem, Warehouse } from "lucide-react";

// ---------------------------------------------------------------------------
// Jewellery & luxury retail and FMCG distribution / wholesale workflow recipes
// — same "inline copy, no i18n keys" precedent and honesty rules as
// recipes-data.tsx. Every recipe is expressed ONLY in VegaDūta's real building
// blocks (Trigger / Agent / Knowledge / Condition / Approval / Output /
// Tool(MCP) / Voice / Device / Loop / Code / Parallel), so each maps 1:1 to
// something you can actually build in the Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — a jewellery POS / CRM / clienteling
// book, a distributor ERP / DMS (distribution-management system), a credit /
// ledger system — are reached the real way, via an inbound Webhook trigger
// and/or a Tool (MCP) node, and are LABELLED as such. Real channels only
// (Webhook, WhatsApp, SMS, Voice/Call, Email, ElevenLabs, Sarvam, Razorpay
// UPI). NON-NEGOTIABLY: every high-value transaction, discount, exchange,
// credit decision, scheme grant and return passes a human Approval (human-in-
// the-loop) step, and anything account-sensitive requires a verified identity
// first — the agent proposes, a person decides. Impact lines describe the
// mechanism (why it helps), never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_LUXFMCG: Industry[] = [
  // ============================================ JEWELLERY & LUXURY RETAIL
  {
    id: "jewellery",
    name: "Jewellery & luxury retail",
    icon: Gem,
    tagline:
      "High-value appointment booking and VIP clienteling, custom-order and repair status, certificate/warranty Q&A — with every discount, exchange and account-sensitive action gated on a verified human decision.",
    recipes: [
      {
        id: "jewellery-clienteling",
        title: "VIP appointment booking and clienteling follow-up",
        scenario:
          "A high-value client messages to see a piece, asks about a bespoke commission, or is due a courtesy follow-up after a big purchase. Today a sales associate handles it between walk-ins on the shop floor — the private-viewing calendar lives in someone's head, follow-ups after a large sale are remembered for the top few clients and forgotten for the rest, and a quiet request for 'the same discount as last time' gets an inconsistent answer depending on who picks up the phone.",
        triggerLabel: "WhatsApp / Call (client enquiry or booking request)",
        steps: [
          { kind: "trigger", title: "Client reaches out", detail: "A WhatsApp or Call trigger captures the enquiry — a private viewing, a bespoke commission, a follow-up — in the client's own words and language; spoken requests are transcribed live." },
          { kind: "agent", title: "Understand the request and match the client", detail: "An Agent node identifies what the client wants (view a piece, book an appointment, discuss a commission) and drafts a warm, on-brand reply grounded in the boutique's collection and appointment availability — never inventing a piece, a price or a slot that isn't real." },
          { kind: "condition", title: "Anything account-sensitive? verify identity first", detail: "A Condition node checks whether the request touches account-sensitive data (past purchases, a stored ring size, an existing order, a repeat discount): if so it requires a verified identity step before ANY detail is disclosed, and only proceeds once identity is confirmed." },
          { kind: "approval", title: "Discounts and exchanges pause for the manager", detail: "An Approval node holds any price concession, VIP discount, exchange or bespoke quote until a store manager signs off — a pricing or exchange decision on a high-value item is always a human's, never the agent's." },
          { kind: "tool", title: "Book the viewing / log the clienteling note", detail: "A Tool (MCP) node reserves the private-appointment slot in the boutique's POS / CRM / clienteling book and records the interaction against the client's profile — no first-party connector, MCP calls yours." },
          { kind: "output", title: "Confirm and follow up", detail: "An Output node sends the appointment confirmation or the approved reply on WhatsApp, and for a post-purchase courtesy follow-up sends a personal, non-pushy note referencing the actual piece the client bought." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Knowledge base", "Approval / HITL", "Tool (MCP → POS/CRM)", "Sarvam (translate and speech tools)"],
        outcome: "Private viewings get booked and confirmed without tying up the floor, every large purchase gets a consistent personal follow-up instead of only the top few, and any discount or exchange is held for a manager — with account-sensitive details released only after the client's identity is verified.",
        impact: "Consistent, personal clienteling is the mechanism: the manual process remembers to follow up with the top handful of clients and quietly drops the rest, and answers 'the usual discount' differently depending on who answers. Routing every concession through a manager approval and gating account data behind identity verification keeps the high-touch relationship human where it matters and consistent everywhere else.",
        build: {
          agents: 1,
          agentsNote: "1 clienteling/booking agent; the identity-verification branch, the manager discount/exchange Approval, the POS/CRM write and the follow-up send are workflow nodes.",
          prompt:
            "You are a client-relations assistant for {{boutique}}, a jewellery house. Greet warmly and in the client's language, and help with private-viewing bookings, bespoke-commission enquiries and post-purchase follow-ups. Only reference pieces, collections, appointment slots and lead times that the POS/CRM and collection catalogue return as real — never invent a piece, a stone, a price, an availability or a delivery date. NEVER disclose a client's past purchases, stored preferences, an existing order or any account detail until the workflow marks their identity as verified. NEVER offer, promise or imply a discount, a price concession, an exchange or a bespoke quote yourself — propose it and let the approval step decide; a concession on a high-value item is always a human's call. For authenticity, certification, valuation or repair questions, hand off to the certificate/warranty flow or a specialist. Keep it discreet and unhurried — this is luxury service, not a hard sell.",
          mcp: ["Jewellery POS / CRM / clienteling book (appointments, client profiles, purchase history) — via a Tool (MCP) node (no first-party connector)", "WhatsApp / Voice — client channel", "Sarvam — 22+ Indian languages"],
          tools: ["knowledge_search (collection catalogue, appointment policy, house services)", "http_request (check live appointment availability, write the booking + clienteling note)"],
          guardrails: [
            "Identity is verified before ANY account-sensitive detail (past purchases, stored preferences, existing orders) is disclosed — the Condition node blocks disclosure until the client is confirmed",
            "Every discount, price concession, exchange and bespoke quote is Approval-gated to a store manager — the agent proposes, a human decides; nothing on a high-value item goes out without sign-off",
            "Only real pieces, prices, slots and lead times from the POS/catalogue are quoted — no invented inventory, valuation or availability; the booking write is idempotent per client + slot so a repeated confirm never double-books a viewing",
          ],
          cost:
            "Booking, catalogue Q&A and follow-up drafting is structured, grounded work, so keep the agent on the economy/standard tier — the one judgement that would justify a premium model (a pricing or exchange concession) is a manager's by design, not the agent's. Cap tool-calls per conversation so an unhurried, chatty clienteling exchange stays cost-predictable.",
          output:
            "The agent understands the request and drafts a grounded reply → the Condition node forces identity verification before any account-sensitive detail → any discount/exchange/quote is held for the manager Approval node → a Tool node books the viewing and logs the clienteling note in the POS/CRM via MCP → an Output node sends the confirmation or approved reply on WhatsApp, plus a personal post-purchase follow-up referencing the actual piece; the interaction and the manager's decision are logged.",
        },
      },
      {
        id: "jewellery-order-care",
        title: "Custom-order and repair status, certificate and warranty Q&A",
        scenario:
          "Bespoke commissions and repairs take weeks, so clients message 'is my ring ready yet?' repeatedly, and each time someone has to walk to the workshop or dig through a job book to answer. In parallel, a steady stream of 'is this certificate genuine?', 'what does my warranty cover?' and 'can you re-issue my valuation?' questions land on the same busy sales staff — high-stakes questions where a wrong or invented answer damages trust in a luxury brand.",
        triggerLabel: "Webhook (order/repair status change) + WhatsApp (client question)",
        steps: [
          { kind: "trigger", title: "Status change or client question", detail: "A Webhook trigger fires when a custom order or repair changes stage in the workshop system (received, in progress, quality-checked, ready); separately a WhatsApp trigger captures a client's status, certificate or warranty question. The workshop / POS has no first-party connector, so status arrives by Webhook." },
          { kind: "agent", title: "Draft the status update or answer the question", detail: "An Agent node writes a clear status update referencing the specific order/repair and its current stage, or answers a certificate/warranty question grounded strictly in the house's documented warranty terms and the piece's own certificate record — never guessing a completion date, a coverage term or an authenticity verdict." },
          { kind: "condition", title: "Account detail or authenticity claim? verify / escalate", detail: "A Condition node checks whether the question needs the client's order record (verify identity first) or is a hallmarking / authenticity / valuation determination — those are routed to a specialist rather than answered by the agent." },
          { kind: "approval", title: "A specialist confirms high-stakes answers", detail: "An Approval node puts any warranty-coverage decision, re-issued valuation or authenticity/certificate confirmation in front of a gemologist / manager before it reaches the client — a binding statement about a valuable piece is always human-confirmed." },
          { kind: "output", title: "Reply to the client", detail: "An Output node sends the status update or the confirmed answer on WhatsApp/Email in the client's language, with a clear next step (collection date, book a valuation, visit the boutique) and an invitation to reply." },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Knowledge base", "Approval / HITL", "Tool (MCP → workshop/POS)"],
        outcome: "Clients get proactive, accurate updates as their commission or repair moves through the workshop instead of chasing for them, and certificate/warranty questions are answered from the real documentation — with any binding authenticity or coverage statement confirmed by a specialist first.",
        impact: "Proactive, grounded updates plus a specialist backstop are the mechanism: the manual process answers 'is it ready?' only when someone has time to check the job book, and risks an off-the-cuff warranty or authenticity answer that a luxury brand can't afford to get wrong. Grounding every answer in the real record and routing high-stakes determinations to a human keeps trust intact.",
        build: {
          agents: 1,
          agentsNote: "1 status/Q&A agent; the identity/authenticity routing, the specialist Approval and the reply are workflow nodes. Kept single-agent — the one high-stakes judgement is a specialist's, not another model's.",
          prompt:
            "You are an order-care and after-sales assistant for {{boutique}}, a jewellery house. Two jobs: (1) give clients clear status updates on their custom order or repair, referencing the specific job and its current workshop stage from the record; (2) answer certificate and warranty questions grounded ONLY in the house's documented warranty terms and the piece's own certificate record in Knowledge. Never guess or invent a completion date, a coverage term, a repair cost, a valuation figure or an authenticity verdict — if it isn't in the record or the documented terms, say so and offer a specialist. NEVER confirm that a certificate is genuine, issue or re-issue a valuation, or make a binding warranty-coverage decision yourself — route those to the specialist approval step. Before sharing any order or account detail, require the workflow's identity-verified flag. Keep the tone reassuring and precise.",
          mcp: ["Workshop / repair-tracking + POS (job stages, order records, certificate references) — via a Tool (MCP) node (no first-party connector)", "WhatsApp / Email — client channels"],
          tools: ["knowledge_search (documented warranty terms, certification policy, after-care guidance)", "http_request (read the order/repair status by reference)"],
          guardrails: [
            "No invented completion dates, coverage terms, valuations or authenticity verdicts — every answer is grounded in the workshop record or the documented warranty/certificate terms, or it defers to a specialist",
            "Every binding statement — an authenticity/certificate confirmation, a re-issued valuation, a warranty-coverage decision — is Approval-gated to a gemologist/manager before it reaches the client",
            "Order and account details are disclosed only once the Condition node confirms a verified identity; the status write/read is idempotent per job so a retried status webhook never sends a contradictory or duplicate update",
          ],
          cost:
            "Status updates and documented-warranty Q&A are grounded lookups the economy/standard tier handles well — reserve any premium tier for open-ended reasoning this flow doesn't need, since the genuinely high-stakes call (authenticity, coverage, valuation) is a specialist's by design. Cap tool-calls per conversation so a multi-question after-sales exchange stays predictable.",
          output:
            "A workshop status change (Webhook) or a client question drives the agent → it drafts a grounded status update or documented-terms answer → the Condition node verifies identity for account detail and routes authenticity/valuation to a specialist → the specialist Approval node confirms any binding answer → an Output node sends it on WhatsApp/Email with a clear next step; the answer and any specialist confirmation are logged.",
        },
      },
    ],
  },

  // ============================================ FMCG DISTRIBUTION & WHOLESALE
  {
    id: "fmcg-distribution",
    name: "FMCG distribution & wholesale",
    icon: Warehouse,
    tagline:
      "Retailer order intake over WhatsApp, stock/price/scheme Q&A, payment reminders and collections, new-retailer onboarding — with every credit, scheme and return decision held for a human.",
    recipes: [
      {
        id: "fmcg-order",
        title: "Retailer order intake over WhatsApp with stock, price and scheme Q&A",
        scenario:
          "A distributor serves hundreds of kirana stores and small retailers who order the way they always have — a WhatsApp message or a voice note: 'send 5 cartons of the usual, 2 cases of the new SKU, what's the scheme this month?'. Today a sales officer or tele-caller reads each one, checks stock and the current price list, keys it into the ERP/DMS, and answers scheme questions from memory — slow at peak, error-prone, and inconsistent on which retailer gets which scheme or credit.",
        triggerLabel: "WhatsApp / Call (retailer order or product question)",
        steps: [
          { kind: "trigger", title: "Retailer places an order", detail: "A WhatsApp or Call trigger captures the order or question in the retailer's own words and language — free-text lists and voice notes are transcribed and parsed, not forced into a form." },
          { kind: "agent", title: "Parse the order, price it, answer stock/scheme questions", detail: "An Agent node maps the retailer's shorthand to real SKUs, prices the order from the current price list, and answers stock-availability and scheme questions grounded in the live ERP/DMS and the published scheme document — never inventing a SKU, a price, a stock figure or a scheme." },
          { kind: "condition", title: "Check stock and flag credit exposure", detail: "A Condition node confirms each line is in stock (offering the nearest real alternative or a back-order if not) and checks whether the order pushes the retailer over their credit limit or applies a scheme they may not qualify for." },
          { kind: "approval", title: "Credit and scheme decisions pause for a human", detail: "An Approval node holds any over-limit credit extension, a special scheme/discount grant or a non-standard rate until a sales manager signs off — a credit or scheme decision is a financial call that stays with a person." },
          { kind: "tool", title: "Book the order in the ERP / DMS", detail: "A Tool (MCP) node creates the sales order / invoice against the retailer's account in the distributor ERP / DMS and reserves stock — no first-party connector, MCP calls yours." },
          { kind: "output", title: "Confirm the order + share a pay link", detail: "An Output node sends an itemised confirmation (SKUs, quantities, price, applicable scheme, dispatch window) on WhatsApp, with a Razorpay UPI link so the retailer can pay against the invoice in one tap." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Knowledge base", "Approval / HITL", "Tool (MCP → ERP/DMS)", "Razorpay UPI", "Sarvam (translate and speech tools)"],
        outcome: "Retailer orders that arrive as free-text WhatsApp messages or voice notes get parsed, priced, stock-checked and booked into the ERP/DMS automatically, with stock and scheme questions answered from live data — and every credit extension or special scheme held for a manager.",
        impact: "The mechanism is turning unstructured WhatsApp ordering into consistent, priced, stock-verified orders without a sales officer keying each one at peak — while keeping the two things that must stay human (extending credit, granting a scheme) behind an approval and every price/stock answer grounded in the live system rather than a tele-caller's memory.",
        build: {
          agents: 1,
          agentsNote: "1 order-intake + Q&A agent; the stock/credit-exposure branch, the credit/scheme Approval, the ERP/DMS order write and the confirmation + pay-link are workflow nodes.",
          prompt:
            "You are an order-desk assistant for {{distributor}}, an FMCG distributor serving retailers. Retailers message you in their own shorthand and language (including voice notes) — map their items to real SKUs, price the order strictly from the CURRENT price list, and confirm quantities before booking. Answer stock-availability and scheme questions using ONLY the live ERP/DMS stock and the published scheme document — never invent a SKU, a price, a stock quantity, a dispatch date or a scheme, and never apply a scheme the retailer's tier doesn't qualify for. NEVER extend credit beyond the retailer's limit, grant a special discount or scheme, or agree a non-standard rate yourself — propose it and let the approval step decide; credit and scheme are financial decisions for a human. If a line is out of stock, offer the nearest real alternative or a back-order, don't substitute silently. Confirm the itemised order back before it's placed.",
          mcp: ["Distributor ERP / DMS (SKU master, live stock, price list, retailer accounts + credit limits) — via a Tool (MCP) node (no first-party connector)", "Razorpay UPI — pay-link against the invoice", "WhatsApp / Voice — retailer channel", "Sarvam — 22+ Indian languages"],
          tools: ["knowledge_search (current price list, published scheme document, SKU aliases)", "http_request (check live stock + credit limit, create the sales order/invoice)"],
          guardrails: [
            "Every credit extension over the retailer's limit, special discount and scheme grant is Approval-gated to a sales manager — the agent prices and proposes, a human decides the financial call",
            "Only real SKUs, current prices, live stock and published schemes are used — no invented items, prices, stock figures or schemes, and no applying a scheme to a retailer whose tier doesn't qualify; out-of-stock lines offer a real alternative or back-order, never a silent substitution",
            "The order write is idempotent per retailer + order reference so a retried or re-sent message never double-books stock or raises a duplicate invoice; payment is collected only through the Razorpay UPI link, never by the agent handling account details",
          ],
          cost:
            "This is high-volume, repetitive order intake, so the parsing/pricing agent should sit on the economy tier — that single choice dominates total cost because it runs once per retailer order, and retailer order and query volume is exactly the kind of routine load that belongs on the economy tier rather than a premium model. Cap tool-calls per order and batch peak-hour traffic so cost stays linear and predictable.",
          output:
            "The agent parses the free-text/voice order into real SKUs and prices it from the current list → the Condition node verifies stock and flags credit/scheme exposure → any over-limit credit or special scheme is held for the sales-manager Approval node → a Tool node creates the sales order/invoice and reserves stock in the ERP/DMS via MCP → an Output node sends the itemised confirmation on WhatsApp with a Razorpay UPI pay-link; the order and any approval are logged.",
        },
      },
      {
        id: "fmcg-collections",
        title: "Payment reminders, collections and new-retailer onboarding",
        scenario:
          "Outstanding invoices age because nobody has time to chase every retailer, so the collections officer works only the biggest overdue accounts and the long tail slips. Meanwhile onboarding a new retailer — collecting GST and shop details, running a credit check, setting a limit — is a manual back-and-forth over days, and the credit limit gets set inconsistently depending on who signs it off.",
        triggerLabel: "Webhook (daily receivables run) + WhatsApp (new-retailer signup)",
        steps: [
          { kind: "trigger", title: "Daily receivables run + new-retailer signup", detail: "A scheduled Webhook trigger each morning pulls invoices that are due or overdue from the ERP/DMS; a second WhatsApp/Webhook trigger fires when a prospective retailer asks to open an account. The ERP/DMS has no first-party connector, so receivables arrive by Webhook." },
          { kind: "loop", title: "Per overdue account / per applicant", detail: "A Loop node iterates the receivables list so each retailer gets exactly the right reminder for how overdue they are, and iterates onboarding applicants so each gets the right next step in the account-opening flow." },
          { kind: "agent", title: "Draft the reminder or collect onboarding details", detail: "An Agent node writes a polite, firm, escalation-appropriate payment reminder referencing the specific invoice(s), amount and due date from the record; or, for onboarding, collects and validates the GST number, shop name, address and contact into a clean application — never inventing a balance, a due date or an interest/penalty figure." },
          { kind: "condition", title: "Verify identity / route disputes", detail: "A Condition node requires a verified identity before discussing account balances, and routes any payment dispute, request for a due-date extension or a return/damages claim to a human rather than negotiating it." },
          { kind: "approval", title: "Credit limits, extensions and returns pause for a human", detail: "An Approval node holds every new-retailer credit-limit decision, any due-date/credit extension and any return or damages credit-note until a credit manager signs off — these are financial and risk decisions that stay with a person." },
          { kind: "output", title: "Send the reminder or onboarding status + pay link", detail: "An Output node sends the reminder or onboarding update on WhatsApp/SMS, attaching a Razorpay UPI link so an overdue invoice can be settled in one tap, or confirming the approved account terms once a manager has set the limit." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "SMS", "Knowledge base", "Approval / HITL", "Tool (MCP → ERP/DMS)", "Razorpay UPI"],
        outcome: "Every overdue invoice — not just the biggest — gets a timely, appropriately-escalated reminder with a one-tap UPI pay link, and new retailers are onboarded through a consistent, validated flow with the credit limit always set by a manager.",
        impact: "Consistent, complete follow-up is the mechanism: the manual process chases only the largest overdue accounts and lets the long tail age, and sets credit limits inconsistently across whoever approves them. Chasing every account on a schedule while holding every credit, extension and return decision for a manager keeps collections thorough and credit risk human-owned — with payment always on a Razorpay UPI link the agent never touches directly.",
        build: {
          agents: 1,
          agentsNote: "1 reminders/onboarding agent per record; the per-account Loop, the identity/dispute routing, the credit/extension/return Approval and the pay-link/status send are workflow nodes.",
          prompt:
            "You are a collections and onboarding assistant for {{distributor}}, an FMCG distributor. For receivables, write ONE polite but firm reminder in the retailer's language, matched to how overdue the invoice is, referencing the specific invoice number(s), amount and due date from the ERP/DMS record — never invent a balance, a due date, an interest charge or a penalty. For onboarding, collect and validate the GST number, legal/shop name, address and contact into a clean application. Before discussing any account balance, require the workflow's identity-verified flag. NEVER set or change a credit limit, agree a due-date or credit extension, waive a charge, or approve a return or damages credit-note yourself — propose it and let the approval step decide; these are the credit manager's financial calls. Route any payment dispute or return/damages claim to a human rather than negotiating. Honour opt-outs and don't over-message an account that has already replied or paid.",
          mcp: ["Distributor ERP / DMS (open invoices, aging, retailer accounts, credit limits) — via a Tool (MCP) node (no first-party connector)", "Razorpay UPI — pay-link against the overdue invoice", "WhatsApp + SMS — retailer channels", "GST / credit-check source — via a Tool (MCP) node during onboarding"],
          tools: ["http_request (read the aging/receivables list + retailer record, validate GST, write the application)", "knowledge_search (collections policy, escalation ladder, onboarding requirements)"],
          guardrails: [
            "Every credit-limit decision, due-date/credit extension and return/damages credit-note is Approval-gated to a credit manager — the agent chases and assembles the application, a human owns the credit and risk decision",
            "Account balances are discussed only after the Condition node confirms a verified identity, and any payment dispute or return claim is routed to a human, never negotiated by the agent; no invented balances, due dates, interest or penalties",
            "Reminders honour frequency caps and opt-outs and stop once an account has paid or replied; the reminder run is idempotent per invoice so a retried daily run never double-chases, and payment is collected only through the Razorpay UPI link, never by the agent handling account details",
          ],
          cost:
            "Collections reminders and onboarding intake are high-volume, low-complexity outreach, so the drafting agent belongs on the economy tier — that one choice dominates total cost because it runs once per overdue account and per applicant. Batch the Loop and cap tool-calls per record so a large morning receivables run stays linear and predictable in cost.",
          output:
            "A Loop runs the agent per overdue invoice and per onboarding applicant → the agent drafts the escalation-appropriate reminder from the aging record, or validates the GST/shop details into an application → the Condition node verifies identity and routes disputes/returns to a human → every credit-limit, extension and return decision is held for the credit-manager Approval node → an Output node sends the reminder or approved account terms on WhatsApp/SMS with a Razorpay UPI pay-link on overdue invoices; the reminders, applications and approvals are logged.",
        },
      },
    ],
  },
];

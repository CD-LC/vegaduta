import type { Industry } from "./recipes-types";
import { Building2, GraduationCap, Headphones, HeartPulse, IndianRupee, Landmark, Megaphone, ShoppingCart, Truck, UtensilsCrossed } from "lucide-react";

// Core pattern pack, part b: Ads, call center, payments, e-commerce, healthcare, real estate, logistics, education, hospitality, banking. Content unchanged; split out of recipes-core-patterns.tsx.
export const INDUSTRIES_CORE_B: Industry[] = [
  // ===================================================================== ADS
  {
    id: "advertising",
    name: "Advertising & marketing agencies",
    icon: Megaphone,
    tagline: "Automate ad-spend decisions, lead follow-up and reporting across Google/Meta Ads.",
    featured: true,
    recipes: [
      {
        id: "ad-spend-guardrail",
        title: "Auto-optimize ad spend with a human guardrail",
        scenario:
          "Your Google Ads / Meta Ads campaigns drift: cost-per-acquisition creeps above target overnight and nobody catches it until the daily standup.",
        triggerLabel: "Webhook (scheduled Ads report, or Ads API push via Zapier/Make)",
        steps: [
          { kind: "trigger", title: "Ad platform posts performance", detail: "A Webhook trigger receives the daily spend/CPA/ROAS payload for each campaign. Google/Meta Ads have no first-party connector — you feed them in via Webhook (or a Tool/MCP node that calls their API)." },
          { kind: "agent", title: "Analyst agent evaluates each campaign", detail: "An Agent node compares CPA/ROAS against the target you set in its system prompt, and drafts a recommendation (pause, cut budget 20%, or scale winners)." },
          { kind: "condition", title: "Only act when it's material", detail: "A Condition node branches: if CPA is within tolerance, log and stop; if it breaches the threshold, continue to the change path." },
          { kind: "approval", title: "Media buyer signs off", detail: "An Approval node pauses the run and sends the proposed budget change to a human — nothing touches live spend without a click. This is the core guardrail." },
          { kind: "tool", title: "Apply the change", detail: "On approval, a Tool (MCP) node calls the Ads API to pause/adjust the campaign; without a connector, it posts the instruction to your ops queue instead." },
          { kind: "output", title: "Report to the team", detail: "An Output node posts a summary to Slack/Email — what changed, why, and the projected saving." },
        ],
        connectors: ["Webhook", "Tool (MCP)", "Slack", "Email", "Approval / HITL"],
        outcome: "Runaway campaigns get caught within the hour instead of the next morning, and every budget change is human-approved and logged.",
        impact: "Turns an overnight blind spot into a same-hour, reviewed decision — the value is speed of detection plus an audit trail, not a hands-off spend bot.",
        build: {
          agents: 1,
          agentsNote: "1 analyst agent. The Condition, Approval and Tool nodes are workflow steps, not extra agents.",
          prompt:
            "You are a paid-media performance analyst. For each campaign row you receive (name, spend, CPA, ROAS, conversions), compare against the target CPA of ₹{{target_cpa}} and target ROAS of {{target_roas}}. Recommend exactly ONE action: KEEP, CUT_BUDGET_20, PAUSE, or SCALE_15. Reply as strict JSON {campaign, action, reason}. Never propose a single-step budget change larger than 20%. If data is missing, return action=KEEP with reason='insufficient data'.",
          mcp: ["Google/Meta Ads — via a Tool (MCP) node (no first-party connector)", "Slack (MCP) for the summary"],
          tools: ["http_request (pull campaign detail)", "knowledge_search (target-threshold doc)"],
          guardrails: [
            "Hard cap: no budget change >20% — stated in the prompt AND re-checked at the Condition node",
            "No live spend touched without the Approval node (human media buyer clicks)",
            "Scope-locked to campaigns in the payload — the MCP tool can't read other accounts",
          ],
          output:
            "Agent emits per-campaign JSON → Condition routes only material breaches → on Approval, a Tool node applies the change via the Ads MCP → Output posts a plain-English summary to Slack and stores the JSON for audit.",
        },
      },
      {
        id: "lead-to-whatsapp",
        title: "Instant WhatsApp follow-up on every ad lead",
        scenario:
          "Leads from your Google/Meta lead forms sit in a spreadsheet for hours. Speed-to-lead is everything, and you're losing the ones a competitor calls first.",
        triggerLabel: "Webhook (lead-form submission)",
        steps: [
          { kind: "trigger", title: "New lead arrives", detail: "A Webhook trigger fires the moment the ad's lead form is submitted." },
          { kind: "agent", title: "Qualify + personalize", detail: "An Agent node reads the lead's answers and drafts a personalized opener referencing what they asked for." },
          { kind: "knowledge", title: "Pull the right offer", detail: "A Knowledge node grounds the reply in your current pricing/offer docs so nothing is invented." },
          { kind: "output", title: "WhatsApp within seconds", detail: "An Output node sends the message on WhatsApp and offers a booking link." },
        ],
        connectors: ["Webhook", "Knowledge base", "WhatsApp"],
        outcome: "Every lead gets a relevant WhatsApp reply within seconds of submitting the form, 24/7.",
        impact: "Speed-to-lead is the single biggest driver of conversion on paid traffic; replying instantly beats a callback hours later.",
        build: {
          agents: 1,
          agentsNote: "1 qualifier/writer agent; the Knowledge grounding and WhatsApp send are workflow steps.",
          prompt:
            "You are a lead-response agent for {{company}}. A new ad lead just submitted a form with fields {name, phone, interest, campaign}. Write ONE short, warm WhatsApp opener that references exactly what they asked about and offers a booking link. Ground every offer/price detail in the Knowledge base — never invent a discount, price, or availability. Keep it under 3 sentences, in the lead's language, and end with a single clear next step.",
          mcp: ["WhatsApp — send channel", "Lead-form source (Google/Meta) — inbound via Webhook trigger"],
          tools: ["knowledge_search (current pricing/offer docs)"],
          guardrails: [
            "Grounded offers only — prices/discounts must come from Knowledge, never invented",
            "One opener per lead — no repeated messaging on the same form submission",
            "No commitments the business can't honour (stock, delivery dates) unless present in Knowledge",
          ],
          output:
            "The agent's grounded opener is sent on WhatsApp within seconds of the form submission, with a one-tap booking link appended.",
        },
      },
    ],
  },

  // ============================================================== CALL CENTER
  {
    id: "call-center",
    name: "Call centers & BPO",
    icon: Headphones,
    tagline: "Answer, understand and speak back on live calls, with the speech provider you connect.",
    featured: true,
    recipes: [
      {
        id: "live-voice-agent",
        title: "Live voice agent that listens and replies out loud",
        scenario:
          "Your queue overflows at peak hours. Simple calls (order status, balance, store hours) tie up agents who should be handling the hard ones.",
        triggerLabel: "Call (inbound voice)",
        steps: [
          { kind: "trigger", title: "Inbound call is answered", detail: "A Call trigger picks up on the Voice channel; the caller's speech is transcribed live." },
          { kind: "agent", title: "Understand intent", detail: "An Agent node interprets what the caller wants and decides the next step." },
          { kind: "knowledge", title: "Ground the answer", detail: "A Knowledge node pulls the real answer from your policies/FAQ/order system so the agent doesn't guess." },
          { kind: "voice", title: "Speak back naturally", detail: "The reply is synthesized with ElevenLabs (or Sarvam for Indian languages) and played on the call — in the language the caller used." },
          { kind: "condition", title: "Know when to hand off", detail: "A Condition node detects frustration, high-value intent, or anything out of scope." },
          { kind: "approval", title: "Warm-transfer to a human", detail: "An Approval/Output node routes to a live agent with the transcript and context attached, so the caller never repeats themselves." },
        ],
        connectors: ["Voice / Call", "ElevenLabs", "Sarvam (translate and speech tools)", "Knowledge base"],
        outcome: "Routine calls are fully handled by voice; complex ones reach a human already briefed with the full context.",
        impact: "Deflects the repetitive volume that burns out agents and inflates wait times, while keeping a human in the loop for anything that matters.",
        build: {
          agents: 1,
          agentsNote: "1 voice agent on the live call; the Knowledge, Condition and hand-off are workflow steps.",
          prompt:
            "You are a phone support agent for {{company}}. Speak in the caller's language, one question at a time, in short spoken sentences. Only answer from the knowledge base; if you don't find it, say so and offer a human. Never quote a price, policy exception, or account change you can't cite. If the caller sounds angry, asks for a human, or the request is out of scope, call transfer_to_human immediately.",
          mcp: ["Order/CRM lookup — via a Tool (MCP) node", "Telephony transfer — via the Voice channel"],
          tools: ["knowledge_search (policies/FAQ/order status)", "transfer_to_human"],
          guardrails: [
            "Grounded-only: answers must come from Knowledge with a citation, or it defers to a human",
            "Auto-escalate on frustration / explicit human request / out-of-scope intent (Condition node)",
            "No account changes or payments over voice — those route to a human or a separate approved flow",
          ],
          output:
            "The agent's text reply is synthesized by ElevenLabs (or Sarvam for Indian languages) and played on the call; on escalation, a warm-transfer carries the live transcript + detected intent so the human never asks the caller to repeat.",
        },
      },
      {
        id: "post-call-wrap",
        title: "Zero-effort post-call wrap-up and CRM update",
        scenario:
          "Agents spend minutes after every call writing notes and updating the CRM — time that isn't spent on the next customer.",
        triggerLabel: "Webhook (call-ended event)",
        steps: [
          { kind: "trigger", title: "Call ends", detail: "A Webhook trigger fires with the recording/transcript reference." },
          { kind: "agent", title: "Summarize + tag", detail: "An Agent node writes a crisp disposition, next steps, and sentiment." },
          { kind: "tool", title: "Write to the CRM", detail: "A Tool (MCP) node updates the customer record — no first-party CRM connector needed; MCP calls yours." },
          { kind: "output", title: "Follow-up on WhatsApp", detail: "An Output node sends the promised summary/next step to the customer on WhatsApp." },
        ],
        connectors: ["Webhook", "Tool (MCP → CRM)", "WhatsApp"],
        outcome: "Every call is documented and followed up automatically, with the agent freed the instant they hang up.",
        impact: "Recovers after-call work time and removes the note-taking that agents skip when they're busy — which is exactly when it matters.",
        build: {
          agents: 1,
          agentsNote: "1 wrap-up agent; the CRM write and WhatsApp follow-up are workflow nodes.",
          prompt:
            "You are a call wrap-up agent. Given a call transcript, produce strict JSON {disposition, summary (max 3 lines), sentiment: positive|neutral|negative, next_steps[], follow_up_promised: boolean, follow_up_text?}. Summarize only what was actually said — never infer a commitment the agent didn't make. If a follow-up was promised on the call, draft follow_up_text in the customer's language; otherwise leave it empty.",
          mcp: ["CRM — via a Tool (MCP) node (no first-party connector)", "WhatsApp — send channel", "Call-ended event — inbound via Webhook trigger"],
          tools: ["http_request (fetch the transcript/recording by reference)"],
          guardrails: [
            "Summarize only what was said — no invented promises, figures, or next steps",
            "CRM write is keyed to the call id — idempotent, never double-posts the same call",
            "A customer follow-up is only sent when one was actually promised on the call",
          ],
          output:
            "The agent's JSON drives a Tool node that updates the customer's CRM record via MCP, and — when a follow-up was promised — an Output node sends the recap/next step to the customer on WhatsApp.",
        },
      },
    ],
  },

  // ================================================================ PAYMENTS
  {
    id: "payments",
    name: "Payments & fintech (UPI)",
    icon: IndianRupee,
    tagline: "Turn a UPI payment event into instant confirmation, fulfilment and reconciliation.",
    featured: true,
    recipes: [
      {
        id: "upi-confirm-fulfil",
        title: "UPI payment → instant receipt, confirmation and fulfilment",
        scenario:
          "A customer pays you over UPI. Today someone manually checks the bank/Razorpay dashboard, then confirms the order and sends a receipt — slowly, and only in business hours.",
        triggerLabel: "Webhook (Razorpay payment.captured)",
        steps: [
          { kind: "trigger", title: "Payment captured", detail: "A Webhook trigger receives the Razorpay UPI 'payment.captured' event the instant money lands." },
          { kind: "condition", title: "Verify amount & status", detail: "A Condition node checks the amount matches the order and the signature is valid before anything runs." },
          { kind: "agent", title: "Draft the receipt & message", detail: "An Agent node generates a branded receipt and a thank-you in the customer's language." },
          { kind: "output", title: "Confirm on WhatsApp", detail: "An Output node sends the receipt + order confirmation on WhatsApp within seconds of payment." },
          { kind: "tool", title: "Update order / inventory", detail: "A Tool (MCP) node marks the order paid and decrements stock in your system." },
        ],
        connectors: ["Webhook (Razorpay UPI)", "WhatsApp", "Tool (MCP → orders/inventory)"],
        outcome: "The customer gets an instant, professional confirmation and the order moves to fulfilment automatically — day or night.",
        impact: "Removes the manual 'did the payment come in?' check and the delay before fulfilment; reconciliation happens as the money arrives.",
        build: {
          agents: 1,
          agentsNote: "1 agent writes the receipt/message; verification and the order update are workflow nodes.",
          prompt:
            "You are a payments assistant. Given a verified payment {amount, order_id, customer_name, language}, write a short, warm confirmation in the customer's language and a clean receipt (order id, amount, date, GST if present). Never state a payment is successful unless the input is marked verified=true. Do not invent order contents — use only the fields provided.",
          mcp: ["Razorpay UPI — inbound via Webhook trigger", "Orders/inventory — via a Tool (MCP) node", "WhatsApp — send channel"],
          tools: ["http_request (fetch order line items)"],
          guardrails: [
            "Signature + amount verification at the Condition node before ANY message or fulfilment (stops spoofed webhooks)",
            "Idempotency on payment_id — a retried webhook never double-fulfils or double-messages",
            "Agent may only confirm when verified=true; refunds are a separate approval-gated flow",
          ],
          output:
            "On verify, the agent's receipt text is sent via the WhatsApp channel within seconds, and a Tool node marks the order paid + decrements stock in the OMS via MCP — the whole thing keyed idempotently on the Razorpay payment id.",
        },
      },
      {
        id: "refund-with-approval",
        title: "Refund request with mandatory human approval",
        scenario:
          "Refunds are high-stakes and fraud-prone. You want them fast, but never automatic.",
        triggerLabel: "WhatsApp (customer refund request) or Webhook",
        steps: [
          { kind: "trigger", title: "Refund requested", detail: "A WhatsApp trigger (or Webhook from your support tool) starts the flow." },
          { kind: "agent", title: "Assemble the case", detail: "An Agent node pulls the order, payment and reason into a one-screen summary." },
          { kind: "approval", title: "Finance approves", detail: "An Approval node holds the refund until a human explicitly approves — the non-negotiable control." },
          { kind: "tool", title: "Issue the refund", detail: "On approval, a Tool (MCP) node calls the Razorpay refund API." },
          { kind: "output", title: "Tell the customer", detail: "An Output node confirms the refund and timeline on WhatsApp." },
        ],
        connectors: ["WhatsApp", "Approval / HITL", "Tool (MCP → Razorpay)", "Webhook"],
        outcome: "Refunds are decided in minutes with a clear approver and a full trail — never issued without a human.",
        impact: "Combines speed with control: the mechanical work is instant, the money decision stays with a person.",
        build: {
          agents: 1,
          agentsNote: "1 case-assembler agent; the finance approval and the refund call are workflow nodes.",
          prompt:
            "You are a refunds case assembler. Given a refund request {order_id, customer, reason, channel}, pull the order and original payment and produce a one-screen summary {order_id, paid_amount, payment_date, refundable_amount, reason, prior_refunds, risk_flags[]}. Never state or imply the refund is approved — you only assemble the case for a human approver. Flag anything unusual (partial use, repeat refunder, amount mismatch) in risk_flags. Do not initiate the refund yourself.",
          mcp: ["Razorpay refund — via a Tool (MCP) node, only after approval", "Orders/payments lookup — via a Tool (MCP) node", "WhatsApp — request + confirmation channel"],
          tools: ["http_request (order + payment history)"],
          guardrails: [
            "No refund is ever issued without the finance Approval node — the agent only assembles, a human decides",
            "The agent may not initiate the Razorpay refund; the Tool node fires only on explicit approval",
            "Duplicate-request check on order_id so the same refund can't be approved twice",
          ],
          output:
            "The agent's case summary goes to the finance Approval node; on approval a Tool node calls the Razorpay refund API, and an Output node confirms the refund + timeline to the customer on WhatsApp.",
        },
      },
    ],
  },

  // ============================================================== E-COMMERCE
  {
    id: "ecommerce",
    name: "Retail & e-commerce",
    icon: ShoppingCart,
    tagline: "Order tracking, cart recovery and support on the channels customers already use.",
    recipes: [
      {
        id: "order-status-whatsapp",
        title: "Self-serve order tracking on WhatsApp",
        scenario: "Most support tickets are just 'where is my order?' — cheap to answer, expensive at volume.",
        triggerLabel: "WhatsApp (inbound message)",
        steps: [
          { kind: "trigger", title: "Customer messages", detail: "A WhatsApp trigger starts on any inbound message." },
          { kind: "agent", title: "Identify the order", detail: "An Agent node extracts the order id / phone and confirms identity." },
          { kind: "tool", title: "Look up live status", detail: "A Tool (MCP) node fetches real status from your OMS/carrier." },
          { kind: "output", title: "Reply with the answer", detail: "An Output node sends the status and expected delivery." },
        ],
        connectors: ["WhatsApp", "Tool (MCP → OMS/carrier)"],
        outcome: "The commonest question answers itself, instantly, in any language.",
        impact: "Removes the highest-volume, lowest-value ticket type from the human queue.",
        build: {
          agents: 1,
          agentsNote: "1 order-status agent; the OMS lookup is a workflow Tool node.",
          prompt:
            "You are an order-status assistant for {{store}}. From an inbound WhatsApp message, extract the order id (or phone/email) and verify it belongs to the sender before revealing anything. Call the order-lookup tool, then reply in the customer's language with the current status and expected delivery date, exactly as returned. Never guess a status or date — if the lookup fails or the order can't be matched to the sender, say so and offer a human.",
          mcp: ["OMS / carrier — via a Tool (MCP) node (no first-party connector)", "WhatsApp — trigger + reply channel"],
          tools: ["http_request (order/tracking lookup)"],
          guardrails: [
            "Identity check before disclosing any order detail — the order must match the sender",
            "Status and dates are read from the OMS only, never inferred or estimated",
            "On a lookup failure, defer to a human rather than guess",
          ],
          output:
            "The agent reads live status via the OMS/carrier Tool node and replies on WhatsApp with the status and expected delivery, in the customer's language, within seconds.",
        },
      },
      {
        id: "cart-recovery",
        title: "Abandoned-cart recovery with a real conversation",
        scenario: "Abandoned carts get a generic email that gets ignored.",
        triggerLabel: "Webhook (cart-abandoned event)",
        steps: [
          { kind: "trigger", title: "Cart abandoned", detail: "A Webhook trigger fires after the inactivity window." },
          { kind: "agent", title: "Personalize the nudge", detail: "An Agent node references the exact items and any objection it can infer." },
          { kind: "condition", title: "Respect frequency caps", detail: "A Condition node ensures you don't over-message the same customer." },
          { kind: "output", title: "WhatsApp nudge + offer", detail: "An Output node sends a helpful, on-brand message with a one-tap return link." },
        ],
        connectors: ["Webhook", "WhatsApp"],
        outcome: "Carts are recovered through a real conversation the customer can reply to, not a dead-end email.",
        impact: "Meets shoppers on the channel they read, with context — the mechanism that lifts recovery over generic email.",
        build: {
          agents: 1,
          agentsNote: "1 recovery agent; the frequency-cap check is a workflow Condition node.",
          prompt:
            "You are a cart-recovery agent for {{store}}. Given an abandoned cart {customer, items[], cart_value, last_seen}, write ONE helpful, on-brand WhatsApp nudge that names the exact items left behind and addresses a likely objection (sizing, shipping, price). Include a one-tap return link. Do not invent a discount or offer unless one is supplied in the input. Keep it friendly and brief, in the customer's language.",
          mcp: ["WhatsApp — send channel", "Store cart events — inbound via Webhook trigger"],
          tools: ["knowledge_search (active promotions / shipping policy)"],
          guardrails: [
            "Frequency cap enforced at the Condition node — a customer is never over-messaged on the same cart",
            "Discounts/offers only when supplied in the input — the agent never fabricates a promo",
            "One nudge per abandonment window, on the channel the customer opted into",
          ],
          output:
            "After the Condition node clears the frequency cap, the agent's personalized nudge is sent on WhatsApp with a one-tap link back to the exact cart.",
        },
      },
    ],
  },

  // ============================================================== HEALTHCARE
  {
    id: "healthcare",
    name: "Healthcare & clinics",
    icon: HeartPulse,
    tagline: "Reminders, intake and triage — with clinicians firmly in the loop.",
    recipes: [
      {
        id: "appointment-reminders",
        title: "Appointment reminders that can reschedule themselves",
        scenario: "No-shows waste clinician time; phone reminders don't scale.",
        triggerLabel: "Webhook (scheduled, day-before)",
        steps: [
          { kind: "trigger", title: "Day-before reminder due", detail: "A scheduled Webhook trigger fires for tomorrow's appointments." },
          { kind: "output", title: "Send the reminder", detail: "An Output node messages the patient on WhatsApp/SMS with the time and prep." },
          { kind: "condition", title: "Handle the reply", detail: "A Condition node routes 'confirm', 'reschedule', or 'cancel'." },
          { kind: "tool", title: "Update the calendar", detail: "A Tool (MCP) node moves or frees the slot in your scheduling system." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS", "Tool (MCP → scheduler)"],
        outcome: "Patients confirm or reschedule in one tap; freed slots reopen automatically.",
        impact: "Cuts no-shows by making the easy action (reschedule) frictionless, without a receptionist on the phone.",
        build: {
          agents: 1,
          agentsNote: "1 agent drafts the reminder and classifies the reply; the send, routing and calendar write are workflow nodes.",
          prompt:
            "You are an appointment-reminder assistant for {{clinic}}. First, given an appointment {patient_name, date, time, prep_instructions, language}, write a short reminder with the time and any prep. Then, when the patient replies, classify their intent as CONFIRM | RESCHEDULE | CANCEL | UNCLEAR and extract any preferred new time. Never confirm a slot change yourself — output the intent so the workflow can update the calendar. Do not give medical advice; for clinical questions, defer to the clinic.",
          mcp: ["Scheduling system (calendar/EHR) — via a Tool (MCP) node", "WhatsApp / SMS — reminder + reply channel"],
          tools: ["knowledge_search (prep instructions per appointment type)"],
          guardrails: [
            "No clinical or medical advice — the agent only handles scheduling; clinical questions defer to the clinic",
            "The calendar is only changed by the Tool node after the reply is classified — the agent never books directly",
            "Unclear replies route to a human rather than a guessed reschedule",
          ],
          output:
            "The reminder is sent on WhatsApp/SMS; the patient's reply is classified, the Condition node routes confirm/reschedule/cancel, and a Tool node updates or frees the slot in the scheduler.",
        },
      },
    ],
  },

  // ============================================================= REAL ESTATE
  {
    id: "real-estate",
    name: "Real estate",
    icon: Building2,
    tagline: "Qualify portal leads and match inventory before a rival even calls back.",
    recipes: [
      {
        id: "portal-lead-qualify",
        title: "Portal lead → qualify → matched properties → site visit",
        scenario: "Leads from property portals go cold while agents are on viewings.",
        triggerLabel: "Webhook (portal lead)",
        steps: [
          { kind: "trigger", title: "Portal sends a lead", detail: "A Webhook trigger receives the enquiry (budget, area, BHK)." },
          { kind: "agent", title: "Qualify the requirement", detail: "An Agent node clarifies budget/location/timeline over WhatsApp." },
          { kind: "knowledge", title: "Match live inventory", detail: "A Knowledge node searches your current listings for genuine matches." },
          { kind: "output", title: "Send matches + book a visit", detail: "An Output node shares 2–3 properties and offers site-visit slots." },
          { kind: "approval", title: "Hand a hot lead to the agent", detail: "An Approval/Output node alerts the right agent when the lead is sales-ready." },
        ],
        connectors: ["Webhook", "WhatsApp", "Knowledge base", "Approval / HITL"],
        outcome: "Every portal lead is engaged and pre-qualified in minutes; agents only spend time on ready buyers.",
        impact: "Response speed and relevant matches are what convert portal leads — this does both before the lead cools.",
        build: {
          agents: 1,
          agentsNote: "1 qualification agent; the inventory match is Knowledge and the hand-off is an Approval node.",
          prompt:
            "You are a real-estate lead-qualification agent. Given a portal enquiry {name, budget, area, bhk, timeline}, hold a short WhatsApp conversation to confirm budget, location, configuration and buying timeline. Then use Knowledge to find 2–3 genuine matches from current listings — never present a property outside the stated budget/area, and never invent inventory. Score the lead's readiness as hot/warm/cold and offer site-visit slots. Hand hot, sales-ready leads to a human agent.",
          mcp: ["WhatsApp — conversation channel", "Property portal — inbound via Webhook trigger"],
          tools: ["knowledge_search (current live listings)"],
          guardrails: [
            "Only real, matching inventory is shown — no invented or off-budget listings",
            "A hot lead is handed to the right human agent via the Approval node before any commitment",
            "Budget and area constraints from the enquiry are respected in every match",
          ],
          output:
            "The agent shares 2–3 Knowledge-matched properties on WhatsApp with site-visit slots, and routes sales-ready leads to the assigned agent through the Approval node.",
        },
      },
    ],
  },

  // ================================================================ LOGISTICS
  {
    id: "logistics",
    name: "Logistics & delivery",
    icon: Truck,
    tagline: "Proactive shipment updates and exception handling before customers complain.",
    recipes: [
      {
        id: "delivery-exception",
        title: "Proactive delivery-exception updates",
        scenario: "Customers find out about a delay by chasing you — a bad first signal.",
        triggerLabel: "Webhook (carrier tracking event)",
        steps: [
          { kind: "trigger", title: "Carrier posts an event", detail: "A Webhook trigger receives each tracking update." },
          { kind: "condition", title: "Detect exceptions", detail: "A Condition node flags delays, failed attempts, or address issues." },
          { kind: "agent", title: "Explain + offer options", detail: "An Agent node writes a clear, calm update and the choices (redeliver, reschedule)." },
          { kind: "output", title: "Notify proactively", detail: "An Output node reaches the customer on WhatsApp before they notice the problem." },
        ],
        connectors: ["Webhook", "WhatsApp"],
        outcome: "Customers hear about problems from you first, with a fix already offered.",
        impact: "Proactive, contextual updates turn a complaint moment into a trust moment.",
        build: {
          agents: 1,
          agentsNote: "1 updates agent; exception detection is a workflow Condition node.",
          prompt:
            "You are a delivery-updates agent for {{company}}. Given a carrier exception event {tracking_id, status, reason, eta}, write a calm, clear WhatsApp update in the customer's language that explains what happened and offers the available options (redeliver, reschedule, pickup). State only the facts in the event — never invent a new delivery date or a cause the carrier didn't report. Keep it short and reassuring, with one clear next action.",
          mcp: ["WhatsApp — send channel", "Carrier tracking — inbound via Webhook trigger"],
          tools: ["http_request (fetch full tracking detail)"],
          guardrails: [
            "Only the carrier's reported status and ETA are communicated — no invented dates or causes",
            "The Condition node fires the message only on genuine exceptions (delay/failed attempt/address issue), not routine scans",
            "Options offered are limited to what the carrier actually supports for that shipment",
          ],
          output:
            "When the Condition node flags a real exception, the agent's calm, factual update is sent on WhatsApp — before the customer notices — with the available options as one-tap replies.",
        },
      },
    ],
  },

  // ================================================================ EDUCATION
  {
    id: "education",
    name: "Education & EdTech",
    icon: GraduationCap,
    tagline: "Answer admissions questions instantly and route serious enquiries to counsellors.",
    recipes: [
      {
        id: "admissions-nurture",
        title: "Admissions enquiry → instant answers → counsellor callback",
        scenario: "Prospective students ask the same admissions questions at all hours; counsellors can't keep up.",
        triggerLabel: "Webhook (enquiry form) or WhatsApp",
        steps: [
          { kind: "trigger", title: "Enquiry arrives", detail: "A Webhook or WhatsApp trigger starts the flow." },
          { kind: "knowledge", title: "Answer from the prospectus", detail: "A Knowledge node answers fees, courses, eligibility and dates — grounded, with citations." },
          { kind: "agent", title: "Nurture + gauge intent", detail: "An Agent node keeps the conversation going and scores readiness." },
          { kind: "approval", title: "Book a counsellor", detail: "An Approval/Output node schedules a human callback for serious applicants." },
        ],
        connectors: ["Webhook", "WhatsApp", "Knowledge base", "Approval / HITL"],
        outcome: "Every enquiry gets accurate answers instantly; counsellors focus on applicants ready to commit.",
        impact: "Instant, accurate answers keep prospects warm; humans spend time where it converts.",
        build: {
          agents: 1,
          agentsNote: "1 admissions agent; the counsellor booking is an Approval node.",
          prompt:
            "You are an admissions assistant for {{institution}}. Answer prospective students' questions about fees, courses, eligibility and dates strictly from the prospectus in Knowledge, with citations — never invent a fee, deadline or eligibility rule. Keep the conversation going, answer follow-ups, and gauge how ready the applicant is. When someone is serious and ready, offer a counsellor callback. Be warm and factual; if you don't find an answer, say so and offer a human.",
          mcp: ["WhatsApp — conversation channel", "Enquiry form — inbound via Webhook trigger", "Counsellor calendar — via a Tool (MCP) node"],
          tools: ["knowledge_search (prospectus: fees, courses, eligibility, dates)"],
          guardrails: [
            "Grounded-only: fees, dates and eligibility come from the prospectus with citations, never invented",
            "Serious applicants are booked with a human counsellor via the Approval node",
            "Unanswerable questions defer to a human rather than guessing",
          ],
          output:
            "The agent answers from the prospectus with citations over WhatsApp and, for ready applicants, schedules a human counsellor callback through the Approval node.",
        },
      },
    ],
  },

  // =============================================================== HOSPITALITY
  {
    id: "hospitality",
    name: "Hospitality & restaurants",
    icon: UtensilsCrossed,
    tagline: "Reservations, reminders and feedback that run themselves.",
    recipes: [
      {
        id: "reservations",
        title: "Reservations, reminders and post-visit feedback",
        scenario: "Bookings come by call and DM; reminders and feedback rarely happen.",
        triggerLabel: "WhatsApp / Call (booking request)",
        steps: [
          { kind: "trigger", title: "Guest requests a table", detail: "A WhatsApp or Call trigger captures the request." },
          { kind: "agent", title: "Confirm the booking", detail: "An Agent node checks availability and confirms details." },
          { kind: "tool", title: "Write to the booking system", detail: "A Tool (MCP) node records the reservation." },
          { kind: "output", title: "Reminder + feedback", detail: "An Output node sends a day-of reminder, then a feedback ask after the visit." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Tool (MCP → bookings)"],
        outcome: "Bookings are confirmed and logged, reminders cut no-shows, and feedback arrives without staff chasing it.",
        impact: "Closes the loop from booking to feedback with zero manual follow-up.",
        build: {
          agents: 1,
          agentsNote: "1 reservations agent; the booking write, reminder and feedback are workflow nodes.",
          prompt:
            "You are a reservations assistant for {{venue}}. From a WhatsApp or voice request, capture party size, date, time and any special requests, check availability, and confirm the booking in the guest's language. Only confirm a slot the availability check actually returned — never promise a table you haven't verified. After the visit, send a short feedback request. Be concise and gracious.",
          mcp: ["Booking/table-management system — via a Tool (MCP) node", "WhatsApp / Voice — request + confirmation channel"],
          tools: ["http_request (availability check)"],
          guardrails: [
            "Only verified-available slots are confirmed — no double-booking or unverified promises",
            "The reservation is written by the Tool node and keyed to the booking id — idempotent on retries",
            "Reminder and feedback are each sent once, on the guest's channel",
          ],
          output:
            "The agent confirms the booking, a Tool node records it in the booking system, and Output nodes send a day-of reminder and a post-visit feedback request automatically.",
        },
      },
    ],
  },

  // ========================================================= BANKING / GOV
  {
    id: "public-services",
    name: "Banking, insurance & public services",
    icon: Landmark,
    tagline: "High-trust, multilingual citizen/customer service — approval-gated end to end.",
    recipes: [
      {
        id: "multilingual-scheme",
        title: "Multilingual enquiry desk (schemes / policies / claims)",
        scenario:
          "Citizens and customers ask about schemes, policies or claims in many languages; call centres can't cover them all.",
        triggerLabel: "WhatsApp / Call",
        steps: [
          { kind: "trigger", title: "Enquiry in any language", detail: "A WhatsApp or Call trigger accepts the question as spoken/typed." },
          { kind: "agent", title: "Understand + respond in-language", detail: "An Agent node works in the caller's language (Sarvam covers 22+ Indian languages)." },
          { kind: "knowledge", title: "Ground in official docs", detail: "A Knowledge node answers strictly from the scheme/policy documents — with citations, no guessing." },
          { kind: "approval", title: "Escalate sensitive cases", detail: "An Approval node routes claims/KYC/eligibility decisions to a human officer." },
          { kind: "output", title: "Confirm + record", detail: "An Output node summarizes and logs the interaction." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Sarvam (translate and speech tools)", "Knowledge base", "Approval / HITL"],
        outcome: "Anyone gets accurate, in-language answers; officers handle only the decisions that need judgement.",
        impact: "Extends coverage to every language and hour, while keeping every consequential decision with a human.",
        build: {
          agents: 1,
          agentsNote: "1 in-language service agent; the sensitive-case escalation is an Approval node.",
          prompt:
            "You are a citizen/customer service agent for {{organization}}. Work in the language the person uses (Sarvam covers 22+ Indian languages). Answer questions about schemes, policies and claims strictly from the official documents in Knowledge, with citations — never state an eligibility rule, entitlement amount, or policy exception you can't cite. Route any claim decision, KYC, or eligibility determination to a human officer. Be clear, patient and neutral.",
          mcp: ["WhatsApp / Voice — conversation channel", "Sarvam — Indian-language voice/translation", "Case/record system — via a Tool (MCP) node"],
          tools: ["knowledge_search (official scheme/policy/claims documents)"],
          guardrails: [
            "Grounded-only with citations — no invented eligibility rules, amounts or exceptions",
            "Claims, KYC and eligibility decisions always escalate to a human officer via the Approval node",
            "Every interaction is summarized and logged for audit",
          ],
          output:
            "The agent answers in the citizen's language from official docs with citations; sensitive decisions route to a human officer via the Approval node, and an Output node summarizes and records the interaction.",
        },
      },
    ],
  },

];

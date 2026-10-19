// Freelancers & solo consultants recipes. Header notes and honesty rules: recipes-freelancer-smb.tsx.

import type { Industry } from "./recipes-data";
import { Laptop } from "lucide-react";

export const FREELANCER_INDUSTRIES: Industry[] = [
  // ================================================ FREELANCERS & CONSULTANTS
  {
    id: "freelancer",
    name: "Freelancers & solo consultants",
    icon: Laptop,
    tagline:
      "You are the sales team, the support desk and the accounts department. These recipes take the retyping, the remembering and the chasing off your plate — and leave every price and promise as your decision.",
    recipes: [
      {
        id: "free-enquiry-to-quote",
        title: "Enquiry → grounded quote, priced by your own formula",
        scenario:
          "Every enquiry gets the same treatment: read the message, work out what they actually want, dig out the rate card, do the maths in your head or in a phone calculator, retype most of last month's quote into a fresh email, and send it two days later because the day job got in the way. The rate card lives in a spreadsheet; the rush surcharge lives in your head.",
        triggerLabel: "WhatsApp / Email / Instagram DM / Webhook (site contact form)",
        steps: [
          {
            kind: "trigger",
            title: "The enquiry lands, wherever it lands",
            detail:
              "WhatsApp, Email, Instagram and a Webhook from your site's contact form all feed the same workflow, so you stop having four inboxes and one memory.",
          },
          {
            kind: "agent",
            title: "Read the brief, extract the job",
            detail:
              "An Agent node pulls the actual spec out of the message — deliverable, scope, quantity, estimated hours, deadline, rush or not — into structured fields instead of prose you have to re-read.",
          },
          {
            kind: "condition",
            title: "Enough to price it?",
            detail:
              "A Condition node checks the fields you must have before a number is honest. Missing scope or deadline branches to ONE specific clarifying question, not a guessed price.",
          },
          {
            kind: "knowledge",
            title: "Price from YOUR rate card",
            detail:
              "A Knowledge node grounds the estimate in your own rate card, package definitions, minimum engagement and payment terms. Nothing is priced from the model's imagination.",
          },
          {
            kind: "tool",
            title: "Your rate maths, as a user-defined tool",
            detail:
              "A user-defined tool `quote_total` (SpEL) does the arithmetic deterministically: `input.hours * input.rate * (input.rush ? 1.25 : 1)`. You wrote that expression, you can change the 1.25 to 1.4 tonight, and the model never has to be trusted with the multiplication.",
          },
          {
            kind: "approval",
            title: "You approve the price before anyone sees it",
            detail:
              "An Approval node holds the drafted quote on your phone. A price is a commitment — you tap approve, or edit the number first. Nothing goes out unseen.",
          },
          {
            kind: "output",
            title: "Send it in your voice",
            detail:
              "An Output node sends the approved quote back on the channel the enquiry arrived on, itemised, with validity and a clear next step.",
          },
        ],
        connectors: [
          "WhatsApp",
          "Email",
          "Instagram",
          "Webhook (site contact form)",
          "Knowledge base",
          "User-defined tool (SpEL)",
          "Approval / HITL",
        ],
        outcome:
          "Enquiries come back the same day as an itemised, rate-card-grounded quote you approved with one tap — instead of a quote you meant to write on Sunday.",
        impact:
          "The mechanism is separation of labour: the model reads and drafts, a SpEL formula you own does the arithmetic, and you keep the only judgement that matters — the price. What disappears is the retyping and the two-day gap, not your control over what you charge.",
        build: {
          agents: 1,
          agentsNote:
            "1 estimating agent. The completeness gate, the rate maths (user-defined SpEL tool) and the price approval are workflow nodes — not extra agents.",
          pattern: "Extract → completeness gate → grounded pricing → human approves → send",
          prompt:
            "You are the quoting assistant for {{freelancer}}, an independent {{discipline}}. From an incoming enquiry, extract {deliverable, scope_summary, quantity, estimated_hours, deadline, rush (true|false), channel}. If scope or deadline is missing, do NOT price: return {complete: false, missing[], one_question} so the workflow asks a single clarification. When complete, look up the applicable rate and package terms in Knowledge, call the user-defined tool quote_total for the amount, and draft an itemised quote {line_items[{desc, qty, rate, amount}], total, validity_days, payment_terms, next_step}. NEVER invent a rate, a discount, a turnaround or an availability date — if it is not in Knowledge, say it needs confirmation. This is always a DRAFT for {{freelancer}} to approve; never phrase it as a confirmed price.",
          agentRoles: [
            {
              role: "Brief reader / estimator (once per enquiry)",
              model: "Standard tier — it has to understand a messy, half-written brief",
              prompt:
                "Turn an unstructured enquiry into a structured job spec, flag anything missing rather than guessing it, and assemble an itemised draft quote using only Knowledge rates and the quote_total tool's returned amount.",
            },
          ],
          mcp: [
            "Your invoicing / accounting app (past quotes, client history) — no first-party connector, reached via a Tool (MCP) node",
            "Your site's contact form — inbound via Webhook",
            "WhatsApp / Email / Instagram — the enquiry and quote channel (first-party)",
          ],
          tools: [
            "knowledge_search (your rate card, package definitions, minimum engagement, payment terms)",
            "user-defined tool `quote_total` (SpEL): input.hours * input.rate * (input.rush ? 1.25 : 1) — a tenant-authored expression over the tool's JSON input, sandboxed by construction (no method calls, no constructors, no bean/type refs, no loop in the grammar). It is a formula tool, not a scripting engine, which is why you can own it: your rate card, your rush surcharge, editable whenever you like, no developer and no redeploy.",
            "http_request (pull a returning client's prior quote from the invoicing app via MCP)",
          ],
          guardrails: [
            "The agent proposes; it never sends. Every price passes the Approval node on your phone before the client sees a number.",
            "Prices, discounts, turnarounds and availability come only from your Knowledge and the quote_total formula — the model is explicitly barred from inventing any of them; unknown terms come back as 'needs confirmation'.",
            "The completeness gate blocks pricing on a half-written brief: one clear question goes out instead of a wrong number that you then have to walk back.",
            "The arithmetic is deterministic — the amount is whatever the SpEL expression returns, so the same inputs always produce the same total and you can audit it by reading one line.",
          ],
          cost:
            "One standard-tier call per enquiry. There is no batch, so there is no multiplier to manage — but put the completeness check on the economy tier and let only genuinely complete briefs reach the standard-tier pricing pass, because clarification replies are the majority of early traffic and they do not need the bigger model. Set a fallback model so a provider hiccup at 11pm degrades to a slower answer instead of a silent miss, and remember the arithmetic costs nothing at all: it runs in the SpEL tool, not in tokens.",
          output:
            "The agent extracts the spec → the Condition node gates completeness → Knowledge supplies your rates → the `quote_total` SpEL tool computes the amount → you approve or edit at the Approval node → an Output node sends the itemised quote on the original channel, and a Tool (MCP) node files it against the client in your invoicing app.",
        },
      },
      {
        id: "free-followup-memory",
        title: "The follow-up that currently lives in your head",
        scenario:
          "You sent the quote. Then a project ate the week. The client never replied, you never nudged, and three weeks later you notice the thread and it is too awkward to reopen. The entire follow-up system is you remembering — which works right up until you are busy, which is exactly when you have quotes out.",
        triggerLabel: "Webhook (quote sent) — then scheduled re-checks",
        steps: [
          {
            kind: "trigger",
            title: "A quote goes out",
            detail:
              "The moment the quote is approved and sent, a Webhook starts a follow-up clock for that specific client and quote. No sticky note, no calendar reminder you snooze.",
          },
          {
            kind: "condition",
            title: "Did they reply?",
            detail:
              "A Condition node checks for a reply on the original channel before anything is sent. A client who already answered is never nudged — that is the thing that makes automated follow-up embarrassing.",
          },
          {
            kind: "loop",
            title: "A finite, polite ladder",
            detail:
              "A Loop node walks a fixed sequence you define — day 3 gentle, day 7 with a question, day 14 a graceful close — and stops. It cannot run forever, and it exits the instant a reply lands.",
          },
          {
            kind: "agent",
            title: "Write the nudge, in context",
            detail:
              "An Agent node drafts each nudge against the actual quote and the last thing the client said, so message three is not a copy of message one with a different date.",
          },
          {
            kind: "approval",
            title: "You see anything that changes the deal",
            detail:
              "A plain nudge can go on its own. But an Approval node holds anything that touches the commercials — a revised price, an extended validity, a discount — because that is a new commitment, not a reminder.",
          },
          {
            kind: "output",
            title: "Send on their channel",
            detail:
              "An Output node sends on WhatsApp or Email, whichever the thread was already on, and logs the touch against the quote.",
          },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Approval / HITL", "Tool (MCP → invoicing app)"],
        outcome:
          "Every outstanding quote gets a considered, finite follow-up sequence that stops the moment the client replies — whether or not you remembered it existed.",
        impact:
          "The mechanism is moving the follow-up out of your working memory and into a workflow with a stop condition. You are not more diligent; the reminder simply no longer competes with billable work for attention. The commercial terms stay yours because the discount path is gated.",
        build: {
          agents: 1,
          agentsNote:
            "1 drafting agent, invoked once per rung of the ladder. The reply check, the ladder itself and the commercial gate are workflow nodes.",
          pattern: "Event-started ladder: Loop over a fixed schedule, exits on reply, gated on commercial change",
          prompt:
            "You write follow-ups for {{freelancer}} on an outstanding quote. Given {quote_summary, sent_on, days_elapsed, last_client_message, rung (1|2|3)}, write ONE short message appropriate to that rung: rung 1 a light check-in, rung 2 a specific question that makes replying easy, rung 3 a graceful close that leaves the door open. Reference the actual quote and the client's own last words — never repeat a previous rung's wording. NEVER offer a discount, extend validity, change scope or invent a new deadline: if the situation seems to call for one, return {needs_owner: true, reason} and stop. Keep it under 60 words and sound like a person, not a sequence.",
          agentRoles: [
            {
              role: "Follow-up writer (once per rung)",
              model: "Economy tier — short, formulaic drafting; the volume here is rungs × open quotes",
              prompt:
                "Write one short, rung-appropriate, context-aware nudge referencing the real quote and the client's last message. Escalate to the owner instead of ever offering commercial concessions.",
            },
          ],
          mcp: [
            "Your invoicing / quoting app (quote status, whether it was accepted) — no first-party connector, reached via a Tool (MCP) node",
            "WhatsApp / Email — the follow-up channel (first-party)",
          ],
          tools: [
            "knowledge_search (your follow-up cadence and tone guide, so rung 3 sounds like you)",
            "http_request (check quote status in the invoicing app via MCP before sending anything)",
          ],
          guardrails: [
            "Hard stop conditions: the ladder is finite (three rungs) and exits immediately on any client reply or on the quote being marked accepted/lost — it can never become a drip campaign.",
            "The agent may never offer a discount, extend validity or change scope; any such need returns needs_owner and lands in your Approval queue as a decision, not a draft that slipped out.",
            "Quote status is re-checked via the MCP tool immediately before each send, so a client who paid or declined out-of-band is never chased.",
            "One touch per rung per quote, keyed to the quote id — a re-run or a duplicate webhook cannot double-message a client.",
          ],
          cost:
            "This is the one recipe with a real multiplier: rungs × open quotes. That makes the model choice the entire cost story — put the drafting agent on the economy tier, cap its output length, and it stays negligible even with a full pipeline. Nothing here needs reasoning; it needs brevity and context. Add a fallback model so a failed provider call retries rather than silently skipping a rung.",
          output:
            "Each rung: the Condition node re-checks for a reply and the MCP tool re-checks quote status → the economy-tier agent drafts one contextual nudge → a plain nudge sends, anything commercial diverts to your Approval queue → an Output node delivers on the original channel and logs the touch against the quote.",
        },
      },
      {
        id: "free-invoice-upi",
        title: "Invoice chasing, with a UPI link instead of an argument",
        scenario:
          "The invoice was emailed a month ago. Chasing it means opening WhatsApp, finding the thread, writing the awkward message, and then screenshotting your bank details again because the client has lost them. You do this for four clients, feel bad about all four, and one of them pays because they genuinely forgot.",
        triggerLabel: "Webhook (invoice due / overdue from your accounting app)",
        steps: [
          {
            kind: "trigger",
            title: "An invoice comes due",
            detail:
              "Your accounting or invoicing app fires an inbound Webhook on due and overdue dates (no first-party connector — Webhook in, Tool (MCP) node to read back the balance).",
          },
          {
            kind: "tool",
            title: "Confirm it is genuinely unpaid",
            detail:
              "A Tool (MCP) node reads the live balance before anything is drafted. Chasing an invoice that was paid yesterday is worse than not chasing at all.",
          },
          {
            kind: "agent",
            title: "Draft the reminder, matched to the relationship",
            detail:
              "An Agent node writes the reminder from your own payment-terms Knowledge — the tone for a three-day-late long-standing client is not the tone for sixty days and silence.",
          },
          {
            kind: "tool",
            title: "Attach a Razorpay UPI payment link",
            detail:
              "A Razorpay (UPI) payment link for the exact outstanding amount is generated and attached, so paying is one tap in the same thread — no re-sending bank details, no 'send me the IFSC again'.",
          },
          {
            kind: "condition",
            title: "How late, and how much?",
            detail:
              "A Condition node branches on ageing and amount. Routine reminders flow; a large or long-overdue balance routes to you instead of escalating on its own.",
          },
          {
            kind: "approval",
            title: "You own every escalation",
            detail:
              "An Approval node holds anything beyond a routine reminder — a late fee, a work-stop notice, a payment-plan offer, or a refund. These are relationship decisions, so they are yours.",
          },
          {
            kind: "output",
            title: "Send on WhatsApp or Email",
            detail:
              "An Output node sends the reminder plus the UPI link where the client actually reads things, and records the touch against the invoice.",
          },
        ],
        connectors: [
          "Webhook (accounting app)",
          "Tool (MCP → accounting / invoicing app)",
          "Razorpay (UPI)",
          "WhatsApp",
          "Email",
          "Approval / HITL",
        ],
        outcome:
          "Overdue invoices get a timely, appropriately-toned reminder with a one-tap UPI link, and you only get involved when it stops being a reminder and starts being a decision.",
        impact:
          "Two mechanisms stack: the reminder stops depending on you feeling up to writing it, and the payment stops depending on the client finding your bank details. The awkward judgement calls — late fees, stopping work, refunds — stay behind an approval precisely because those are the ones you should not automate.",
        build: {
          agents: 1,
          agentsNote:
            "1 reminder-drafting agent. The paid-check, the UPI link, the ageing branch and the escalation gate are workflow nodes.",
          pattern: "Due-date trigger → live balance re-check → grounded draft + UPI link → ageing branch → owner approves escalations",
          prompt:
            "You draft payment reminders for {{freelancer}}. Given {client, invoice_no, amount_due, currency, due_date, days_overdue, relationship_length, prior_reminders}, write ONE short reminder whose tone matches the ageing: recently due is a friendly nudge that assumes it was missed, long overdue is direct but never threatening. State the invoice number and exact outstanding amount as given — never recalculate, never round, never add a fee. Reference the attached payment link as the easiest way to settle. You may NOT threaten legal action, announce a late fee, stop work, offer a payment plan or promise a refund: if any of those seem warranted, return {escalate: true, recommendation, reason} and write nothing. Terms come only from the payment terms in Knowledge.",
          agentRoles: [
            {
              role: "Reminder writer (once per due invoice)",
              model: "Economy tier — short, high-frequency, tone-matched drafting",
              prompt:
                "Write one short reminder toned to the ageing, quoting the invoice number and exact amount verbatim, pointing at the payment link. Escalate rather than ever inventing a fee, a plan or a threat.",
            },
          ],
          mcp: [
            "Your accounting / invoicing app (due dates out, live balance back) — no first-party connector: inbound Webhook + Tool (MCP) node",
            "Razorpay (UPI) — first-party: payment link creation and paid-status callback",
            "WhatsApp / Email — the reminder channel (first-party)",
          ],
          tools: [
            "knowledge_search (your payment terms, late-payment policy, and what you are willing to offer)",
            "http_request (read live balance from the accounting app via MCP before drafting)",
            "user-defined tool `overdue_band` (SpEL): input.days_overdue >= 60 ? 'ESCALATE' : (input.days_overdue >= 30 ? 'FIRM' : 'GENTLE') — you author the thresholds, so the day you decide 30 days is too generous you change one expression and every future reminder shifts, with no code change and no redeploy.",
          ],
          guardrails: [
            "Live balance is re-read via the MCP tool immediately before drafting — an invoice settled out-of-band is never chased.",
            "Amounts and invoice numbers are quoted verbatim from the accounting app; the agent is barred from recalculating, rounding, or adding any charge.",
            "Late fees, work-stop notices, payment plans, legal language and refunds are all impossible for the agent to send — they return escalate:true and wait for your Approval. Refunds in particular never leave the approval queue on their own.",
            "One reminder per invoice per rung, keyed to invoice id, so a duplicate webhook from the accounting app cannot double-chase a client.",
            "The payment link is generated for the exact outstanding amount from the accounting record — the agent does not supply the figure.",
          ],
          cost:
            "Volume equals your open invoices, which for a solo practice is small and predictable. Economy tier is correct here — this is short tone-matched drafting, not reasoning. The genuinely valuable spend is elsewhere: the MCP balance re-check before every send costs nothing in tokens and prevents the one failure mode (chasing a paid invoice) that would make you switch the whole thing off.",
          output:
            "Webhook fires on due date → MCP tool confirms the balance is really outstanding → the economy-tier agent drafts a tone-matched reminder → a Razorpay UPI link for the exact amount is attached → the Condition node bands the ageing → routine reminders send on WhatsApp/Email while fees, plans, work-stops and refunds queue for your Approval → the touch is logged against the invoice.",
        },
      },
      {
        id: "free-lead-triage",
        title: "One queue instead of five inboxes",
        scenario:
          "Work arrives on WhatsApp, Instagram DMs, email, a form on your site and occasionally Telegram. You check them at different times with different levels of attention, so a serious enquiry can sit unread under three 'can you do this for free?' messages, and you have no idea what is actually in the pipeline without scrolling five apps.",
        triggerLabel: "WhatsApp / Instagram / Telegram / Email / Webhook (site form)",
        steps: [
          {
            kind: "trigger",
            title: "Every channel, one entry point",
            detail:
              "Five first-party triggers — WhatsApp, Instagram, Telegram, Email and a Webhook from your site form — all land in the same workflow. The channel stops determining how fast something gets seen.",
          },
          {
            kind: "agent",
            title: "Classify and summarise",
            detail:
              "An Agent node decides what each message actually is — new enquiry, existing client, supplier, recruiter, spam — and extracts budget signal, timeline and what they want, in two lines you can read while walking.",
          },
          {
            kind: "tool",
            title: "Your own priority rule, as a SpEL tool",
            detail:
              "A user-defined tool `lead_priority` (SpEL) applies YOUR thresholds: `input.budget >= 50000 ? 'HOT' : (input.budget >= 10000 ? 'WARM' : 'NURTURE')`. Not a vendor's idea of a hot lead — yours, in one expression you can edit the moment your rates change.",
          },
          {
            kind: "condition",
            title: "Route by what it is",
            detail:
              "A Condition node splits the paths: HOT enquiries alert you now, WARM ones queue for the daily digest, existing-client messages route to the support path, spam is dropped silently.",
          },
          {
            kind: "output",
            title: "Acknowledge, honestly",
            detail:
              "An Output node sends a short acknowledgement that sets a real expectation — it does not promise a price, a slot or a delivery date, because none of those exist yet.",
          },
          {
            kind: "output",
            title: "One digest, once a day",
            detail:
              "Everything non-urgent arrives as a single ranked digest on Slack or Email, so your pipeline is one list rather than five scroll-throughs.",
          },
        ],
        connectors: [
          "WhatsApp",
          "Instagram",
          "Telegram",
          "Email",
          "Webhook (site form)",
          "Slack",
          "User-defined tool (SpEL)",
        ],
        outcome:
          "Every inbound message from every channel is classified, prioritised by your own rule and either surfaced immediately or queued into one daily digest — so nothing serious hides behind noise.",
        impact:
          "The mechanism is a single ranked queue replacing five unranked ones. Attention stops being allocated by which app you happened to open, and the priority rule is a SpEL expression you own rather than a black-box score — so when it is wrong, you can see exactly why and fix it yourself.",
        build: {
          agents: 1,
          agentsNote:
            "1 classifier agent per message, plus an optional once-a-day digest pass reusing the same agent. The prioritisation is a SpEL tool, not an agent.",
          pattern: "Fan-in from many channels → classify → deterministic priority rule → route → daily digest",
          prompt:
            "You triage inbound messages for {{freelancer}}, an independent {{discipline}}. Classify ONE message into strict JSON {type (NEW_ENQUIRY|EXISTING_CLIENT|SUPPLIER|RECRUITER|SPAM), summary (max 2 lines), stated_budget (number|null), timeline (string|null), asks[], channel}. Extract budget ONLY if the sender stated one — never estimate or infer a figure, use null. Do not reply to the sender, do not quote a price, do not promise availability or a delivery date, and do not accept work. Your output is a triage record for {{freelancer}} to act on.",
          agentRoles: [
            {
              role: "Message classifier (once per inbound message)",
              model: "Economy tier — this is the highest-volume, lowest-judgement step in the whole pack; its model choice dominates total spend",
              prompt:
                "Classify and two-line-summarise one message into strict JSON. null for anything not explicitly stated. Never reply, never price, never commit.",
            },
            {
              role: "Digest writer (once a day)",
              model: "Standard tier — one call, ranks and narrates the day's queue",
              prompt:
                "Given the day's triage records, produce one ranked digest: HOT first with the specific next action, then WARM, then a one-line count of everything filtered. Use only the records provided.",
            },
          ],
          mcp: [
            "Your CRM / notes app, if you keep one — no first-party connector, reached via a Tool (MCP) node",
            "Your site's contact form — inbound via Webhook",
            "WhatsApp / Instagram / Telegram / Email / Slack — first-party channels",
          ],
          tools: [
            "knowledge_search (what you do and explicitly do not take on, so out-of-scope enquiries are filtered rather than half-answered)",
            "user-defined tool `lead_priority` (SpEL): input.budget >= 50000 ? 'HOT' : (input.budget >= 10000 ? 'WARM' : 'NURTURE') — a tenant-authored expression over the tool's JSON input, sandboxed by construction (no method calls, no constructors, no bean/type refs, no loop in the grammar). A formula, not a script. It means your discount slabs and priority thresholds are yours to change on a Tuesday evening without a developer, a ticket or a redeploy.",
            "send_email (the daily digest, if you would rather have it in your inbox than Slack)",
          ],
          guardrails: [
            "The triage agent never replies with anything substantive — the acknowledgement is a fixed Output node that promises nothing about price, availability or delivery.",
            "Budget is recorded only when the sender stated it; the agent may not infer a figure, because an inferred budget silently changes your priority rule's answer.",
            "Prioritisation is a deterministic SpEL expression, not a model judgement — same inputs, same band, every time, and auditable by reading one line.",
            "Existing-client messages are routed, never auto-answered, so a support question from a paying client always reaches you.",
            "Spam is dropped silently rather than replied to, so an automated acknowledgement never confirms your number is live.",
          ],
          cost:
            "This is your highest-volume workflow, so it is also the one where model tier matters most: every inbound message hits the classifier. Economy tier with a hard output cap, and a fallback model for provider blips, keeps a busy week from becoming a surprise bill. The one standard-tier call per day is the digest — that is the right place for the reasoning budget, because it runs once regardless of how noisy the day was.",
          output:
            "Five channels fan into one workflow → the economy-tier classifier emits a strict-JSON triage record → the `lead_priority` SpEL tool bands it against your own thresholds → the Condition node alerts you on HOT, queues WARM, routes existing clients to support and drops spam → a fixed, non-committal acknowledgement goes out → a single standard-tier digest lands once a day on Slack or Email.",
        },
      },
    ],
  },

];

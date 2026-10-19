// Small business owners (1-10 people) recipes. Header notes and honesty rules: recipes-freelancer-smb.tsx.

import type { Industry } from "./recipes-data";
import { Store } from "lucide-react";

export const SMALL_BUSINESS_INDUSTRIES: Industry[] = [
  // =============================================== SMALL BUSINESS OWNERS 1–10
  {
    id: "small-business",
    name: "Small business owners (1–10 people)",
    icon: Store,
    tagline:
      "The shop's front desk, order book and ledger, run from the same phone you use for family. These recipes answer the fiftieth 'what's the price' of the day — and still leave every price, booking and refund as yours to confirm.",
    recipes: [
      {
        id: "smb-front-desk",
        title: "The fiftieth 'what's the price / are you open?' of the day",
        scenario:
          "Your personal number is the business number. The same six questions arrive fifty times a day across WhatsApp, Instagram and Messenger — price, timings, do you deliver, where exactly are you, do you have it in stock, do you take UPI — often in a different language than you answered in last. You reply between serving customers, at 10pm, and on Sunday, and you still miss the one message that was an actual order.",
        triggerLabel: "WhatsApp / Instagram / Messenger / Telegram / Voice call",
        steps: [
          {
            kind: "trigger",
            title: "Any channel, including the phone ringing",
            detail:
              "WhatsApp, Instagram, Messenger and Telegram feed the same workflow, and a Voice trigger picks up calls after hours instead of letting them ring out.",
          },
          {
            kind: "knowledge",
            title: "Answers come from YOUR shop's facts",
            detail:
              "A Knowledge node holds your price list, opening hours, holiday closures, delivery radius, payment methods and directions. Every answer is grounded there — the agent has no other source for a price or a timing.",
          },
          {
            kind: "agent",
            title: "Answer the routine six, in their language",
            detail:
              "An Agent node handles the repeat questions. Sarvam covers Indian-language conversations so a customer messaging in Hindi, Kannada or Tamil gets a real answer rather than an English wall.",
          },
          {
            kind: "condition",
            title: "Routine, or actually a customer?",
            detail:
              "A Condition node separates the FAQ traffic from the things that need you — an order, a complaint, a bulk enquiry, anything about a price that is not on the list. Those escalate immediately with the conversation attached.",
          },
          {
            kind: "voice",
            title: "The phone gets answered too",
            detail:
              "A Voice node with ElevenLabs handles after-hours calls: it states hours and location, takes a callback request, and never quotes a price or accepts an order by voice.",
          },
          {
            kind: "output",
            title: "You get the ones that matter",
            detail:
              "An Output node pushes escalations to you on WhatsApp or Slack with the full thread, so you pick up mid-conversation instead of asking the customer to repeat themselves.",
          },
        ],
        connectors: [
          "WhatsApp",
          "Instagram",
          "Messenger",
          "Telegram",
          "Voice / Call",
          "ElevenLabs",
          "Sarvam",
          "Knowledge base",
          "Slack",
        ],
        outcome:
          "The repeat questions get answered instantly, in the customer's language, around the clock — and the messages that are actually business reach you with context instead of being buried under them.",
        impact:
          "The mechanism is separating constant, answerable traffic from the small number of messages that need a human. Nothing about your prices or hours becomes negotiable: the agent can only repeat what is in your Knowledge, and anything outside it is escalated rather than guessed. What changes is that the fiftieth identical question no longer costs you a reply.",
        build: {
          agents: 1,
          agentsNote:
            "1 front-desk agent across all text channels, plus the Voice node for calls. The escalation split is a Condition node, not another agent.",
          pattern: "Omnichannel fan-in → Knowledge-grounded FAQ answering → escalate anything consequential",
          prompt:
            "You are the front desk for {{business}}, a small {{business_type}}. Answer only from the shop facts in Knowledge: prices, opening hours, holiday closures, delivery area, payment methods (including UPI), address and directions. Reply in the SAME language the customer wrote in, briefly and warmly. If the answer is not in Knowledge — an item not on the price list, a bulk or custom price, stock on a specific day, a delivery date, a complaint, a refund, or anything about an existing order — do NOT guess and do NOT approximate: say the owner will confirm shortly and hand off. You may never quote a price that is not in Knowledge, never confirm stock, never promise a delivery date, never accept an order and never offer a discount.",
          agentRoles: [
            {
              role: "Front-desk agent (every inbound message)",
              model: "Economy tier — this is constant, repetitive, Knowledge-grounded traffic; the tier choice here IS the cost story for a small shop",
              prompt:
                "Answer the routine six questions from Knowledge only, in the customer's own language, in one or two lines. Hand off — never guess — on price, stock, delivery dates, complaints, refunds and orders.",
            },
          ],
          mcp: [
            "Your billing / POS or stock sheet, if you keep one digitally — no first-party connector, reached via a Tool (MCP) node for a live stock check",
            "WhatsApp / Instagram / Messenger / Telegram / Voice — first-party channels",
            "Sarvam (Indian-language conversation) and ElevenLabs (voice) — first-party",
          ],
          tools: [
            "knowledge_search (price list, hours, holidays, delivery radius, payment methods, directions, common policies)",
            "http_request (optional live stock lookup from your POS via MCP — used only to say 'in stock', never to promise a quantity)",
            "user-defined tool `delivery_fee` (SpEL): input.distance_km <= 3 ? 0 : (input.distance_km <= 8 ? 30 : 60) — your delivery slabs, authored by you as one expression over the tool's JSON input. Sandboxed by construction (no method calls, no constructors, no bean/type refs, no loop in the grammar): a formula tool, not a scripting engine. Raise the free-delivery radius before Diwali and lower it after, yourself, in seconds, with no developer and no redeploy.",
          ],
          guardrails: [
            "The agent answers ONLY from your Knowledge. It is explicitly barred from inventing or approximating a price, confirming stock it cannot see, promising a delivery date or offering any discount — those hand off to you instead.",
            "Anything consequential — an order, a complaint, a refund request, a bulk or custom price — escalates with the full conversation attached; the agent proposes nothing and commits nothing.",
            "The Voice node states hours and location and takes callbacks only. It never quotes a price or accepts an order by phone, where there is no written record to check later.",
            "Language is matched to the customer via Sarvam rather than assumed, so a hand-off never arrives with half the context in a language you have to translate.",
            "Out-of-hours replies say when you will actually respond — the agent never implies you are available when the shop is shut.",
          ],
          cost:
            "This is THE cost recipe for a one-phone business, because it is the only workflow with genuinely constant volume. Put the front-desk agent on the economy tier with a hard output cap and a fallback model: the traffic is repetitive Knowledge lookup, not reasoning, and the bigger model would buy you nothing while multiplying every one of those fifty daily messages. Reserve any standard-tier spend for the escalated conversations you actually join. Voice minutes are the other real line — keep the after-hours Voice node to hours, location and callback capture rather than a long conversation.",
          output:
            "All text channels fan in → the economy-tier agent answers the routine six from Knowledge in the customer's own language (Sarvam) → the Condition node escalates orders, complaints, refunds, bulk and off-list prices to you on WhatsApp or Slack with the full thread → an ElevenLabs Voice node covers after-hours calls with hours, location and a callback request, and nothing else.",
        },
      },
      {
        id: "smb-booking-gate",
        title: "Bookings that block your day — so you confirm them",
        scenario:
          "Appointment requests arrive as messages: 'tomorrow 4pm ok?'. You check the diary if you can reach it, say yes, forget to write it down, and then double-book. Or you do write it down, but the customer never confirms, the slot stays blocked, and you turn away someone who would have come.",
        triggerLabel: "WhatsApp / Instagram / Webhook (booking form) / Voice call",
        steps: [
          {
            kind: "trigger",
            title: "Someone asks for a slot",
            detail:
              "A booking request arrives on WhatsApp, Instagram, a Webhook from your site's booking form, or as a Voice call your phone did not have to answer.",
          },
          {
            kind: "agent",
            title: "Understand what they actually want",
            detail:
              "An Agent node extracts service, preferred date and time, duration from your service list in Knowledge, and any constraint the customer mentioned — into fields, not a sentence you have to re-read.",
          },
          {
            kind: "tool",
            title: "Check the real diary",
            detail:
              "A Tool (MCP) node reads your calendar or booking sheet — there is no first-party calendar connector, so this is an explicit MCP call. The agent never claims a slot is free from memory.",
          },
          {
            kind: "condition",
            title: "Free, clashing, or outside hours?",
            detail:
              "A Condition node branches. A clean in-hours slot becomes a proposal; a clash or an out-of-hours request becomes options for you to choose from, not a refusal in your name.",
          },
          {
            kind: "approval",
            title: "You confirm anything that blocks the calendar",
            detail:
              "An Approval node holds the booking. Accepting a slot commits your time and turns away the next customer, so it is a tap from you — never the agent's decision.",
          },
          {
            kind: "output",
            title: "Confirm, then remind",
            detail:
              "On approval, an Output node confirms the slot to the customer and schedules a reminder the day before, which is the cheapest no-show prevention there is.",
          },
        ],
        connectors: [
          "WhatsApp",
          "Instagram",
          "Webhook (booking form)",
          "Voice / Call",
          "Tool (MCP → calendar / booking sheet)",
          "Approval / HITL",
          "SMS / Twilio",
        ],
        outcome:
          "Booking requests come back as a checked, clash-free proposal you confirm with one tap, and every confirmed slot gets an automatic day-before reminder.",
        impact:
          "The mechanism is that availability is read from the real diary instead of anyone's memory, and the commitment stays a human tap. Double-bookings stop coming from the gap between saying yes and writing it down; no-shows drop because the reminder no longer depends on you remembering to send it.",
        build: {
          agents: 1,
          agentsNote:
            "1 booking agent. The diary read, the clash branch, the confirmation gate and the reminder are workflow nodes.",
          pattern: "Extract → live availability check → propose → owner approves → confirm + reminder",
          prompt:
            "You handle booking requests for {{business}}. From a message, extract {service, requested_date, requested_time, party_size, notes}. Look up the service duration and opening hours in Knowledge and check the diary via the calendar tool. Propose {slot_start, slot_end, service, price_from_knowledge_or_null} ONLY if the diary shows it free and it fits inside opening hours. NEVER say a slot is available without the tool confirming it, never invent a price or a duration that is not in Knowledge, and NEVER tell the customer the booking is confirmed — it is a proposal until {{owner}} approves. If there is a clash or the request is out of hours, return up to three genuine alternative slots from the diary instead.",
          agentRoles: [
            {
              role: "Booking agent (once per request)",
              model: "Economy tier for extraction and the availability read — the reasoning is in the calendar tool's answer, not the model",
              prompt:
                "Extract the request, check the real diary via the tool, and propose only slots the tool confirmed free and Knowledge confirmed in-hours. Offer alternatives on a clash. Never confirm anything.",
            },
          ],
          mcp: [
            "Your calendar / booking sheet — no first-party connector: read and write via a Tool (MCP) node",
            "Your site's booking form — inbound via Webhook",
            "WhatsApp / Instagram / SMS (Twilio) / Voice — first-party channels",
          ],
          tools: [
            "knowledge_search (service list, durations, opening hours, holiday closures, deposit policy)",
            "http_request (read availability and, after approval, write the booking to the calendar via MCP)",
            "user-defined tool `slot_end` (SpEL): input.start_minutes + input.service_minutes + (input.first_visit ? 15 : 0) — your own buffer rule for new customers, authored as one expression over the tool's JSON input and sandboxed by construction (no method calls, constructors, bean/type refs or loops in the grammar). Change the buffer when you learn what your shop actually needs; no developer, no redeploy.",
          ],
          guardrails: [
            "Availability is only ever asserted from a live calendar read via the MCP tool — the agent may not say a slot is free on its own judgement.",
            "The customer is never told a booking is confirmed until you approve it at the Approval node; every message before that is explicitly a proposal.",
            "Prices and durations come from Knowledge only — no invented service, no invented price, no invented turnaround.",
            "The calendar write happens only after approval and is keyed to the request id, so a resent message or a duplicate form submission cannot double-book you.",
            "Out-of-hours and clashing requests become alternatives for you to pick from — the agent never refuses a customer in your name.",
          ],
          cost:
            "One request, one short economy-tier call: the expensive part of booking is the diary read, and that is a tool call, not tokens. Volume is naturally bounded by how many slots you actually have. Keep the standard tier out of this entirely — the model is doing extraction, not judgement — and add a fallback model so an evening booking request never fails silently while you are closed.",
          output:
            "The agent extracts the request → an MCP tool reads the live diary → the Condition node splits clean slots from clashes and out-of-hours → you approve or pick an alternative at the Approval node → the booking is written to the calendar keyed to the request id, the customer is confirmed on their channel, and an SMS/WhatsApp reminder is scheduled for the day before.",
        },
      },
      {
        id: "smb-order-to-upi",
        title: "Order on WhatsApp → UPI link → paid, without the screenshot dance",
        scenario:
          "Orders come in as chat messages — '2 of the big ones, delivery to the same address'. You total it in your head, type the amount, send your UPI ID again, wait for a payment screenshot, squint at it, and hope it was the right amount. Refunds are worse: someone asks, you feel bad, you send money back with no record anywhere.",
        triggerLabel: "WhatsApp / Instagram / Messenger (order message)",
        steps: [
          {
            kind: "trigger",
            title: "An order arrives as a chat message",
            detail:
              "WhatsApp, Instagram or Messenger — wherever your customers already are. No app for them to download, no portal to log into.",
          },
          {
            kind: "agent",
            title: "Turn the message into a line-item order",
            detail:
              "An Agent node maps what they wrote onto your actual price list in Knowledge — items, quantities, and anything it could not match, listed explicitly rather than assumed.",
          },
          {
            kind: "tool",
            title: "Your pricing rules, as a SpEL formula",
            detail:
              "A user-defined tool `order_total` (SpEL) computes the amount deterministically from quantities, your unit prices, your bulk slab and your delivery fee — so the total is arithmetic you own, not something a model produced.",
          },
          {
            kind: "condition",
            title: "Anything unmatched or unusual?",
            detail:
              "A Condition node routes any unmatched item, custom request or unusually large quantity to you before a total is ever shown to a customer.",
          },
          {
            kind: "approval",
            title: "You confirm the total before the link goes out",
            detail:
              "An Approval node holds the priced order. A total sent to a customer is a price commitment, so you see it first — and can fix a quantity or waive the delivery fee before it goes.",
          },
          {
            kind: "output",
            title: "Razorpay UPI link, right in the thread",
            detail:
              "On approval, a Razorpay (UPI) payment link for the exact approved amount goes back into the same chat. The customer taps and pays; the paid callback closes the loop — no screenshot to squint at.",
          },
          {
            kind: "approval",
            title: "Refunds are always yours",
            detail:
              "A refund request never executes automatically. It is drafted with the order and payment record attached and waits in your Approval queue as a decision with evidence.",
          },
        ],
        connectors: [
          "WhatsApp",
          "Instagram",
          "Messenger",
          "Razorpay (UPI)",
          "Knowledge base",
          "User-defined tool (SpEL)",
          "Approval / HITL",
          "Tool (MCP → POS / stock sheet)",
        ],
        outcome:
          "Chat orders become itemised, correctly-totalled orders with a one-tap UPI link and an automatic paid confirmation — and both the price and any refund stay your explicit decision.",
        impact:
          "Three separate manual steps collapse: the mental arithmetic becomes a SpEL formula you wrote, the bank-details message becomes a payment link, and the screenshot verification becomes a payment callback. What does not collapse is authority — the total and the refund are both approval-gated, because those are the two places a mistake actually costs you money.",
        build: {
          agents: 1,
          agentsNote:
            "1 order-parsing agent. Totalling is a deterministic SpEL tool, the price gate and the refund gate are Approval nodes, and the payment link is a Razorpay action.",
          pattern: "Parse to line items → deterministic total → owner approves price → UPI link → paid callback; refunds separately gated",
          prompt:
            "You take orders for {{business}} from chat messages. Map the customer's words onto the price list in Knowledge and return {line_items[{sku, name, qty, unit_price}], unmatched[], delivery_requested (true|false), address_given (true|false), notes}. Use ONLY items and unit prices that exist in Knowledge — if something the customer asked for is not on the list, put it in unmatched[] with their exact wording; never substitute a similar item and never guess a price. Do NOT compute the total yourself: the order_total tool does that. Do not confirm stock, do not promise a delivery time, and do not tell the customer the order is placed — it is a draft until {{owner}} approves.",
          agentRoles: [
            {
              role: "Order parser (once per order message)",
              model: "Economy tier — mapping chat text onto a fixed price list is matching, not reasoning, and it runs on every order",
              prompt:
                "Map the message onto the Knowledge price list into strict JSON line items. Anything unmatched goes in unmatched[] verbatim. Never substitute, never price, never total, never confirm.",
            },
          ],
          mcp: [
            "Razorpay (UPI) — first-party: payment link for the approved amount, plus the paid-status callback",
            "Your POS / stock sheet — no first-party connector: optional stock check and order record via a Tool (MCP) node",
            "WhatsApp / Instagram / Messenger — first-party order channels",
          ],
          tools: [
            "knowledge_search (price list, pack sizes, bulk slabs, delivery policy, refund policy)",
            "user-defined tool `order_total` (SpEL): (input.subtotal * (input.qty >= 12 ? 0.9 : 1)) + (input.subtotal >= 500 ? 0 : input.delivery_fee) — your bulk discount and your free-delivery threshold, authored by you as a single expression over the tool's JSON input. Sandboxed by construction: no method calls, no constructors, no bean or type references, and no loop in the grammar — a data-transform/formula tool, not a scripting engine. That is precisely why it is safe for you to own: change the slab from 12 to 10, or the free-delivery threshold from 500 to 400, whenever you like, with no developer involved and no redeploy.",
            "http_request (write the approved order to your POS via MCP after payment)",
          ],
          guardrails: [
            "The total is computed by the SpEL formula, never by the model — same basket, same number, every time, and you can audit it by reading one expression.",
            "Only items and unit prices present in Knowledge are used; unmatched requests are surfaced verbatim rather than substituted or guessed.",
            "The priced order passes your Approval node before any total or payment link reaches the customer — a price sent is a price committed.",
            "Refunds NEVER execute automatically. Every refund request is drafted with the order and payment record attached and waits for your explicit approval.",
            "The payment link is created for exactly the approved amount and keyed to the order id, so a resent message cannot generate a second link or a double charge.",
            "The agent never confirms stock or promises a delivery time — both hand off to you.",
          ],
          cost:
            "Order volume in a small shop is steady but modest, and the parsing step is matching text against a fixed list — economy tier, hard output cap, fallback model. The genuinely important cost lever is that the totalling is NOT a model call: it is a SpEL expression, so the arithmetic is free, deterministic and unaffected by which tier you are on. Spend nothing more than you must here and put your reasoning budget into the front-desk escalations instead.",
          output:
            "The economy-tier agent maps the chat message onto your price list → the `order_total` SpEL tool computes the amount from your own slabs → unmatched or unusual orders divert to you → you approve or adjust the total at the Approval node → a Razorpay UPI link for exactly that amount lands in the same chat → the paid callback confirms the order and a Tool (MCP) node records it in your POS → any later refund request waits, with evidence, in your Approval queue.",
        },
      },
      {
        id: "smb-books-drip",
        title: "Bookkeeping that stops being a last-Sunday-of-the-month problem",
        scenario:
          "The books get done on the last Sunday of the month, from a carrier bag of receipts, a bank statement PDF and whatever you can reconstruct from WhatsApp. It takes the whole day, you are guessing at half the categories by the end of it, and anything you genuinely forgot is simply lost. Meanwhile you never actually know, mid-month, whether this month is going well.",
        triggerLabel: "Webhook (bank / payment feed) + WhatsApp (photo of a bill) — daily schedule",
        steps: [
          {
            kind: "trigger",
            title: "Capture the moment it happens",
            detail:
              "A Webhook takes transactions from your bank or payment feed, and a WhatsApp trigger lets you photograph a bill and forget about it — capture at the point of spending, not four weeks later.",
          },
          {
            kind: "loop",
            title: "Work through the day's items",
            detail:
              "A Loop node walks each captured transaction or bill so the daily volume is handled in small, cheap, identical passes rather than one heroic monthly session.",
          },
          {
            kind: "agent",
            title: "Read it and propose a category",
            detail:
              "A cheap agent extracts vendor, date, amount and tax from each item and proposes a category from YOUR chart of accounts in Knowledge — proposes, with a confidence flag, never posts.",
          },
          {
            kind: "condition",
            title: "Confident, or ask?",
            detail:
              "A Condition node lets clean, confidently-matched items flow to the draft ledger and pulls anything ambiguous, unreadable or unusually large into a short review queue.",
          },
          {
            kind: "output",
            title: "One question a day, not a hundred on Sunday",
            detail:
              "An Output node sends you the small review queue on WhatsApp — usually a handful of items, answerable while the kettle boils.",
          },
          {
            kind: "approval",
            title: "Nothing is filed without you",
            detail:
              "An Approval node holds the reconciled batch. The books are your legal responsibility, so the agent produces a decision-ready draft and you approve what actually gets posted.",
          },
          {
            kind: "output",
            title: "A month that is already done",
            detail:
              "On approval, a Tool (MCP) node writes the batch to your accounting app and a short weekly summary tells you how the month is tracking — while you can still do something about it.",
          },
        ],
        connectors: [
          "Webhook (bank / payment feed)",
          "WhatsApp",
          "Knowledge base",
          "Approval / HITL",
          "Tool (MCP → accounting app)",
          "Email",
        ],
        outcome:
          "Receipts and transactions are captured and categorised the day they happen, you answer a handful of questions instead of a hundred, and the last Sunday of the month becomes an approval rather than a reconstruction.",
        impact:
          "The mechanism is moving the work from one large batch to a small daily drip, where the context is still fresh and the memory of what a payment was for still exists. The secondary effect matters as much: because the ledger is current, you can see mid-month how the month is going, instead of learning it four weeks after you could have acted.",
        build: {
          agents: 2,
          agentsNote:
            "1 cheap extractor/categoriser run once per item by the Loop node, plus 1 weekly summariser that runs once. The review queue and the filing gate are workflow nodes.",
          pattern: "Daily map (Loop over items) → confidence gate → owner approves the batch → weekly reduce into a summary",
          prompt:
            "(Extractor) Process ONE transaction or bill image for {{business}}. Return strict JSON {vendor, date, amount, currency, tax_amount (number|null), suggested_category, confidence (HIGH|MED|LOW), reason}. Choose suggested_category ONLY from the chart of accounts in Knowledge. Read values from the document or feed record — never estimate an amount, never infer a tax figure that is not shown, use null. If the image is unreadable or the vendor is ambiguous, return confidence=LOW with a specific reason; do not guess. You are proposing an entry for {{owner}} to approve — nothing you return is filed by you.",
          agentRoles: [
            {
              role: "Extractor / categoriser (once per item, via the Loop node)",
              model: "Economy tier — it runs on every single transaction and receipt, so this model choice dominates the whole recipe's cost",
              prompt:
                "Extract vendor, date, amount and tax from one item and propose a category strictly from the Knowledge chart of accounts, with a confidence flag. null over estimates. LOW confidence over guesses.",
            },
            {
              role: "Weekly summariser (once a week)",
              model: "Standard tier — one call, and the only step that needs actual reasoning",
              prompt:
                "From the week's approved entries only, write a short plain-language summary: money in, money out, the largest categories, anything unusual versus prior weeks, and what is still awaiting review. Cite only real figures from the entries provided; never project or estimate.",
            },
          ],
          mcp: [
            "Your accounting / bookkeeping app — no first-party connector: the approved batch is written via a Tool (MCP) node",
            "Your bank or payment feed — inbound via Webhook",
            "WhatsApp — receipt capture and the daily review queue (first-party)",
          ],
          tools: [
            "knowledge_search (your chart of accounts, category rules, what counts as claimable, vendor aliases)",
            "http_request (post the approved batch to the accounting app via MCP)",
            "user-defined tool `needs_review` (SpEL): input.amount >= 5000 || input.confidence != 'HIGH' — your own review threshold as one expression over the tool's JSON input, sandboxed by construction (no method calls, no constructors, no bean/type refs, no loop in the grammar). Raise the amount when the shop grows and the daily queue shrinks accordingly — your rule, your edit, no developer, no redeploy.",
          ],
          guardrails: [
            "Nothing is filed to the accounting app without your approval — the agents produce a draft ledger, and the Approval node is what turns it into a posted batch.",
            "Amounts and tax figures are read from the document or feed record only; the agent may never estimate a number or infer a tax amount that is not printed, and returns null instead.",
            "Categories come strictly from your chart of accounts in Knowledge — no invented category, and an ambiguous vendor is flagged LOW rather than guessed.",
            "Every item is keyed to its transaction or image id, so a re-sent photo or a replayed webhook cannot create a duplicate entry.",
            "The weekly summary reports only approved entries and real figures — it never projects, forecasts or estimates a month-end position.",
          ],
          cost:
            "This is the one recipe here with a true per-item multiplier, so the rule is the same as any map-reduce: N × (economy-tier, output-capped extractor) + ONE standard-tier weekly reduce. The extractor's tier is the whole cost story — it runs on every receipt and every bank line — while the summariser's single weekly call is where the reasoning budget belongs. Run the daily map in Parallel batches if you have a busy day; that buys wall-clock speed without changing per-item cost. A fallback model matters here because a dropped extraction is a lost receipt.",
          output:
            "Transactions arrive by Webhook and receipts by WhatsApp photo → a Loop node maps the economy-tier extractor over each item → the `needs_review` SpEL rule and the Condition node split confident items from the ones worth asking about → a short daily queue reaches you on WhatsApp → you approve the batch at the Approval node → a Tool (MCP) node posts it to your accounting app → a single standard-tier weekly summary tells you how the month is actually tracking.",
        },
      },
    ],
  },
];

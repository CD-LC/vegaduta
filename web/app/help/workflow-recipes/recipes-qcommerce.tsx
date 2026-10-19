// Quick-commerce & dark-store recipes. Header notes and honesty rules: recipes-qcommerce-cloudkitchen.tsx.

import type { Industry } from "./recipes-data";
import { PackageCheck } from "lucide-react";

export const QCOMMERCE_INDUSTRIES: Industry[] = [
  // ================================================ QUICK-COMMERCE & DARK STORES
  {
    id: "quick-commerce",
    name: "Quick-commerce & dark stores",
    icon: PackageCheck,
    tagline:
      "Catch a sub-10-minute promise before it breaks, resolve mid-pick substitutions with the customer, and sweep near-expiry batches — with every refund, markdown and out-of-stock flag human-approved.",
    recipes: [
      {
        id: "qc-promise-breach",
        title: "Sub-10-minute promise about to break → proactive save, not a silent late delivery",
        scenario:
          "Today: the store manager keeps the ops dashboard open on one tab and the rider-app in another, and watches the amber timers. When one goes red she pings the store WhatsApp group — 'order 8842 kahan hai?' — someone replies four minutes later, and by then the customer has already seen the timer hit zero. The apology, if it happens, is a CX agent copying a template into the ticket the next morning, and the goodwill amount comes from a per-city rule that lives in a pinned message in that same WhatsApp group.",
        triggerLabel: "Webhook (order-state / ETA-slip event from the order feed or rider-dispatch platform)",
        steps: [
          {
            kind: "trigger",
            title: "ETA slip event fires",
            detail:
              "An inbound Webhook carries the live order state — promised window, pick-start time, pack status, rider-assignment state. The order feed, POS and rider-dispatch platform have no first-party connector; the event arrives via Webhook and anything extra is read back via a Tool (MCP) node.",
          },
          {
            kind: "condition",
            title: "Is the promise actually at risk?",
            detail:
              "A Condition node applies your own breach rule (projected delivery beyond the promised window by more than the tolerance in Knowledge) so the workflow only wakes up for orders genuinely heading for a breach — not for every normal order tick.",
          },
          {
            kind: "agent",
            title: "Diagnose where the minutes went",
            detail:
              "An Agent node reads the timestamps and attributes the slip to a stage — pick queue depth, an item hunt, packing, or no rider assigned — and drafts the honest customer message. The remedy TIER is not the model's opinion: it calls the tenant's own SpEL formula tool.",
          },
          {
            kind: "tool",
            title: "Ops' own goodwill band (user-defined SpEL tool)",
            detail:
              "A user-defined tool — a sandboxed SpEL expression over the tool input, not code — returns the goodwill band from the order value and minutes late. The city ops lead edits that one expression when Bengaluru's threshold differs from Indore's; no developer, no deploy, and the model can't drift from the policy because it isn't deciding it.",
          },
          {
            kind: "output",
            title: "Tell the customer before the timer does",
            detail:
              "An Output node sends the revised ETA and a plain explanation on the customer's channel — WhatsApp or SMS, in their language via Sarvam for Indian-language customers — and offers the option to cancel while it's still cheap for everyone.",
          },
          {
            kind: "approval",
            title: "Store manager approves any goodwill",
            detail:
              "An Approval node holds every goodwill credit, refund or cancellation-with-compensation. The agent proposes a band with its reason; the manager clicks. Money never moves on the agent's own judgement.",
          },
          {
            kind: "subworkflow",
            title: "Hand off to the shared credit sub-workflow",
            detail:
              "On approval a Sub-workflow node calls the one shared 'issue goodwill credit' flow used by every other CX recipe — so the credit ledger write, the Razorpay UPI refund path and the audit trail exist in exactly one place, not copy-pasted into five workflows.",
          },
          {
            kind: "output",
            title: "Nudge the floor + log the cause",
            detail:
              "A parallel Output node posts the at-risk order to the store's Slack/Telegram floor channel with the diagnosed stage, and the attributed cause is logged so the daily roll-up can show which stage is breaking promises.",
          },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS", "Slack", "Telegram", "Sarvam (Indian languages)", "Razorpay (UPI)", "Tool (MCP → order feed / rider dispatch)", "Approval / HITL"],
        outcome:
          "At-risk orders get an honest heads-up and a floor nudge while the order is still recoverable, and every rupee of goodwill is a manager's decision with the cause attached.",
        impact:
          "Moves the customer conversation from after-the-fact refund to before-the-breach expectation-setting, and turns each slip into an attributed data point (pick / pack / rider) instead of an anonymous late order. The compensation policy moves from a pinned WhatsApp message to a one-line SpEL expression the city ops lead owns and can change per city in seconds — the model drafts, the formula decides the band, the manager approves.",
        build: {
          agents: 1,
          agentsNote:
            "1 diagnosis-and-drafting agent; the breach rule, the goodwill band (a user-defined SpEL tool, not a model call), the approval and the shared credit sub-workflow are workflow nodes.",
          pattern: "Event-triggered single agent behind a Condition gate → tenant-owned SpEL band → human approval → shared Sub-workflow for the money",
          prompt:
            "You are a dark-store fulfilment assistant for {{store}}. Given one live order {order_id, promised_window, placed_at, pick_started_at, pick_completed_at, packed_at, rider_assigned_at, current_stage, items_count, order_value} plus the store's breach-tolerance rules from Knowledge: (1) attribute the delay to exactly ONE stage — PICK_QUEUE | ITEM_HUNT | PACKING | RIDER_UNASSIGNED | IN_TRANSIT — using only the timestamps given; if the timestamps do not support an attribution, return stage=UNKNOWN rather than guessing. (2) Compute a revised ETA only from the observed stage durations — never invent a number to sound reassuring. (3) Draft a short, non-defensive customer message stating the revised ETA and the cancel option. (4) Call the `goodwill_band` tool for the remedy band — do NOT decide the band yourself and do NOT override its answer. You PROPOSE the remedy — you never issue credit, never cancel an order, and never promise a time the stage data does not support.",
          agentRoles: [
            {
              role: "Slip diagnostician + drafter (once per at-risk order)",
              model:
                "Economy tier — this fires on every at-risk order across every store, so it is the volume step; the work is timestamp arithmetic plus a short message. Configure a fallback model on the same tier: this runs on the live order path, and a provider blip must degrade to a second economy model rather than leave the customer unmessaged.",
              prompt:
                "Attribute the delay to one stage from timestamps only, compute a revised ETA from observed durations, draft the customer message, and call `goodwill_band` for the remedy tier. Return strict JSON {stage, revised_eta, customer_message, remedy_band, reason}. UNKNOWN is a valid stage; a guessed one is not.",
            },
          ],
          mcp: [
            "Order-management / marketplace order feed — inbound via Webhook; read back via Tool (MCP): fetch order header, line items and stage timestamps by order_id",
            "Rider-dispatch platform — via Tool (MCP): get rider assignment state, current leg and last known ETA for the order",
            "Wallet / credit ledger — via Tool (MCP): post an approved goodwill credit (called only from inside the approved sub-workflow, never by the agent)",
          ],
          tools: [
            "knowledge_search (scoped collection: breach tolerances + the per-city compensation matrix)",
            "http_request (read live order + rider state; post the approved credit)",
            "user-defined tool `goodwill_band` (SpEL): input.orderValue > 2000 ? 'ESCALATE' : (input.lateMinutes > 20 ? 'CREDIT_FULL' : 'CREDIT_PARTIAL') — a sandboxed expression over the tool input, so ops encodes its own thresholds and edits them per city without a developer or a deployment",
            "send_email (daily stage-attribution roll-up to the ops lead)",
          ],
          guardrails: [
            "Every goodwill credit, refund and compensated cancellation passes the Approval node before anything is issued — the agent only proposes a band and a reason",
            "The compensation band comes from the user-defined SpEL tool, not the model — the LLM cannot argue itself into a bigger credit, and ops can change the thresholds without touching the prompt",
            "Revised ETAs are computed from observed stage timestamps, never invented to sound better; when the data doesn't support an attribution the agent must return UNKNOWN",
            "One proactive message per order per stage change, keyed to the order id — a re-fired webhook cannot spam the customer or double-issue a credit",
            "Customer-language replies go through Sarvam for Indian languages rather than defaulting to English on a bad-news message",
          ],
          cost:
            "This is the highest-volume workflow in the store, so it sits on the economy tier by design: one short, structured call per AT-RISK order — and the Condition gate is what keeps 'at-risk' from meaning 'every order'. Tighten the breach tolerance first and the model bill follows. The goodwill band costs nothing at all: a SpEL expression is evaluated in-process, so the most policy-sensitive decision in the flow is also the only free one. Reserve standard-tier spend for the end-of-day roll-up that reads the attributed causes in bulk.",
          output:
            "The Condition gate admits only genuinely at-risk orders → the agent attributes the stage and drafts the message → the `goodwill_band` SpEL tool returns the band from ops' own thresholds → an Output node sends the revised ETA on WhatsApp/SMS (Sarvam-localised) and posts the order to the floor channel → the manager approves at the Approval node → a Sub-workflow node runs the shared credit flow, which is the only place that ever touches the ledger or the UPI refund path.",
        },
      },
      {
        id: "qc-substitution",
        title: "SKU out mid-pick → customer-consented substitution instead of a silent cut",
        scenario:
          "Today: the picker is holding a printed pick list and a torch, the shelf is empty, and the app still says 4 in stock. He shouts across the aisle; the shift supervisor opens the 'Substitution Sheet' — an Excel file on a shared drive, last edited two months ago — scrolls for the SKU, doesn't find it, and makes a call. Either the item is silently dropped and refunded three days later, or something 'similar enough' goes in the bag and the customer discovers it at the door. Nobody asks the customer, because asking means someone has to type on WhatsApp while the pick clock runs.",
        triggerLabel: "Webhook (pick-fail / item-not-found scan from the picking app)",
        steps: [
          {
            kind: "trigger",
            title: "Picker marks item not found",
            detail:
              "A Webhook fires the moment the picking app records a pick-fail — order id, SKU, quantity, and the shelf/zone. The picking app and inventory/WMS have no first-party connector; the event comes in via Webhook and stock is checked back via a Tool (MCP) node.",
          },
          {
            kind: "knowledge",
            title: "Ground the swap in real rules",
            detail:
              "A Knowledge node backed by a scoped collection supplies the substitution policy — allowed pack-size and variant swaps, brand-equivalence rules, and the hard-blocked categories (infant formula, prescription-adjacent, allergen-sensitive) where a swap is never acceptable. This is the Excel sheet, except it's versioned and every agent reads the same copy.",
          },
          {
            kind: "agent",
            title: "Propose ranked substitutes",
            detail:
              "An Agent node proposes at most three substitutes that are actually in stock right now (verified against the live on-hand lookup), each with the price difference stated up front — or returns 'no acceptable substitute' for a blocked category.",
          },
          {
            kind: "tool",
            title: "Ops' own price-delta gate (user-defined SpEL tool)",
            detail:
              "A user-defined SpEL expression classifies each proposed swap's price delta against the tenant's own tolerance — inside tolerance is offered plainly, outside it is called out as a paid upgrade. Category managers tune that one expression per city; the model never gets to decide what counts as 'close enough on price'.",
          },
          {
            kind: "output",
            title: "Ask the customer, with a clock on it",
            detail:
              "An Output node sends the ranked options on WhatsApp/SMS in the customer's language (Sarvam), with an explicit short reply window so the pick isn't held hostage — 'reply 1, 2 or SKIP'.",
          },
          {
            kind: "condition",
            title: "Reply, or time out safely",
            detail:
              "A Condition node branches on the reply. An explicit choice proceeds; silence past the window falls back to the SAFE default — drop the item and refund it — never to a guessed swap.",
          },
          {
            kind: "approval",
            title: "Approve the money movement",
            detail:
              "An Approval node holds the refund or the price-difference charge/credit that the chosen path implies. A customer's 'yes' authorises the swap; the store still authorises the money.",
          },
          {
            kind: "output",
            title: "Tell the picker, adjust the order",
            detail:
              "On approval, the picker's device gets the swap instruction (or a skip), and a Tool (MCP) node writes the amended order line and the approved refund/adjustment back to the order system.",
          },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS", "Sarvam (Indian languages)", "Razorpay (UPI)", "Tool (MCP → inventory/WMS + order system)", "Knowledge base", "Approval / HITL"],
        outcome:
          "The customer chooses their own substitute — or knowingly skips it — inside the pick window, and the swap is checked against real live stock rather than a stale availability flag or a two-month-old spreadsheet.",
        impact:
          "Converts the silent-cut / guessed-swap dilemma into a 30-second consented choice: stock is verified at proposal time, blocked categories are absolute, and silence defaults to a refund — so the failure mode is a missing item, never a wrong product in the bag. The Excel substitution sheet becomes a scoped Knowledge collection plus one SpEL price-tolerance expression that a category manager owns directly.",
        build: {
          agents: 1,
          agentsNote:
            "1 substitution agent; the live stock verification, the SpEL price gate, the reply timeout, the money approval and the picker instruction are workflow nodes.",
          pattern: "Grounded proposal → tenant-owned SpEL price gate → customer consent loop with a timeout → safe default → human-approved settlement",
          prompt:
            "You are a dark-store substitution assistant for {{store}}. Given a pick-fail {order_id, sku, name, qty, category, unit_price} and a LIVE in-stock list from the stock tool, propose at most 3 substitutes ranked by closeness {sku, name, pack_size, price, price_delta, why}. HARD RULES: (a) never propose an item that is not in the live in-stock list you were given — no memory, no assumptions; (b) if the category appears in the blocked-substitution list in Knowledge, return {substitutable: false, reason} and propose nothing; (c) call `swap_price_gate` for each candidate and carry its verdict verbatim — you do not judge price acceptability yourself; (d) state every price difference explicitly, including when the substitute is cheaper; (e) never propose more than one pack-size step away from the original unless the policy allows it. You PROPOSE — the customer chooses, and the refund or price adjustment is executed by the workflow only after approval.",
          agentRoles: [
            {
              role: "Substitution proposer (once per pick-fail)",
              model:
                "Economy tier — high frequency (every pick-fail in every store) and the hard part is already done by the live stock lookup, the Knowledge policy and the SpEL gate; the model is ranking and phrasing, not reasoning. Set a same-tier fallback model: a pick-fail waits on a picker standing at an empty shelf, so a provider outage must fail over, not queue.",
              prompt:
                "Rank up to 3 in-stock substitutes with explicit price deltas and the `swap_price_gate` verdict attached, or return substitutable:false for a blocked category. Strict JSON only. Never name a SKU that isn't in the live stock list provided.",
            },
          ],
          mcp: [
            "Inventory / WMS — via Tool (MCP): live on-hand lookup for this dark store, filtered to the SKU's category and sellable status (no first-party connector)",
            "Order-management system — via Tool (MCP): amend the order line to the chosen substitute and post the approved refund or price-difference adjustment",
            "Picking app — inbound pick-fail event via Webhook; via Tool (MCP): push the swap-or-skip instruction back to the picker's handheld",
          ],
          tools: [
            "knowledge_search (scoped collection: substitution policy — pack-size/variant rules, brand equivalence, blocked categories)",
            "http_request (live stock lookup; amend the order line after approval)",
            "user-defined tool `swap_price_gate` (SpEL): (input.substitutePrice - input.originalPrice) / input.originalPrice > 0.15 ? 'FLAG_PAID_UPGRADE' : 'WITHIN_TOLERANCE' — one sandboxed expression, so a category manager sets what 'close enough on price' means per city or per category without a release",
          ],
          guardrails: [
            "Every refund, price-difference charge and goodwill adjustment passes the Approval node — customer consent authorises the swap, the store authorises the money",
            "Substitutes are drawn ONLY from a live stock lookup taken at proposal time, so the workflow can't offer something the picker also can't find",
            "Hard-blocked categories (infant formula, allergen-sensitive, prescription-adjacent) return 'no acceptable substitute' — the agent has no discretion here",
            "Price acceptability is decided by the tenant's SpEL expression, not by the model's sense of fairness",
            "Silence past the reply window falls back to skip-and-refund, never to an assumed swap; the timeout default is the safe one",
            "One substitution ask per order line, keyed to order id + SKU — a duplicate pick-fail event cannot re-ask or double-adjust",
          ],
          cost:
            "Economy tier per pick-fail, and pick-fails scale with store availability rather than order volume — so the bill tracks a metric you're already trying to reduce. There is no batch multiplier and no second agent: the expensive-looking parts (live stock, policy, price tolerance) are a tool call, a Knowledge lookup and a free SpEL evaluation, not model reasoning. Only move to a standard tier if you extend it to multi-line baskets where trade-offs across items start to matter.",
          output:
            "The pick-fail webhook triggers a live on-hand lookup → the agent ranks up to three real substitutes, each carrying the `swap_price_gate` verdict (or blocks the swap outright) → an Output node asks the customer on WhatsApp/SMS with a reply window → the Condition node takes the explicit choice or safely defaults to skip-and-refund → the Approval node gates the money → a Tool (MCP) node amends the order line, posts the approved refund, and pushes the swap-or-skip instruction to the picker's handheld.",
        },
      },
      {
        id: "qc-expiry-sweep",
        title: "Near-expiry batch sweep (map-reduce, 2 agents) — markdown or pull, never auto-priced",
        scenario:
          "Today: the closing shift is supposed to walk the fresh, dairy and bakery aisles with a clipboard and a torch, reading batch codes off the back of packs and writing SKUs into a notebook. In practice it happens on the nights someone has time. Whoever does it WhatsApps a blurry photo of the list to the store manager, who eyeballs it against the pricing portal the next morning and marks a few things down. The rest surfaces as a write-off line at month end — or in a customer's bag with yesterday's date on it.",
        triggerLabel: "Webhook (scheduled nightly batch/expiry export from the inventory system)",
        steps: [
          {
            kind: "trigger",
            title: "Nightly batch export lands",
            detail:
              "A scheduled Webhook delivers the batch-level stock file — SKU, batch, expiry date, quantity on hand, shelf zone, recent sell-through. The inventory/batch system has no first-party connector; the export arrives via Webhook and writebacks go through a Tool (MCP) node.",
          },
          {
            kind: "loop",
            title: "Map: one pass per batch",
            detail:
              "A Loop node fans a cheap mapper agent across every batch line — isolated context per batch, so a 2,000-line store export is handled exactly like a 20-line one. This is the clipboard walk, done exhaustively, every night.",
          },
          {
            kind: "agent",
            title: "Mapper classifies the batch",
            detail:
              "The mapper returns strict JSON per batch — days-to-expiry, cover (days of stock at current sell-through) and a proposed action: MONITOR / MARKDOWN / PULL_FROM_SALE / DONATE_OR_WRITE_OFF. The BAND and the markdown depth come from the tenant's own SpEL expression, not from the model.",
          },
          {
            kind: "tool",
            title: "Ops' own markdown ladder (user-defined SpEL tool)",
            detail:
              "A user-defined SpEL expression maps days-to-expiry to a band and a depth, with an elvis default for SKUs that have no sell-through history. The category team edits that single expression to run a deeper Thursday ladder on bakery than on chilled — no developer, no deployment, and the depth is identical across every store because it's one expression.",
          },
          {
            kind: "parallel",
            title: "Run the map in batches",
            detail:
              "A Parallel node runs the mapper in concurrent batches so a full-store sweep finishes inside the night window — same per-item cost, far less wall-clock.",
          },
          {
            kind: "agent",
            title: "Reduce: one sweep plan",
            detail:
              "A reducer agent rolls the validated per-batch results into a single plan: the markdown list with proposed depths, the pull-now list ordered by shelf zone (walked in aisle order, not SKU order), and the categories where the expiry pattern points at over-ordering rather than slow sale.",
          },
          {
            kind: "approval",
            title: "Store manager approves prices and pulls",
            detail:
              "An Approval node holds EVERY price change and every pull-from-sale / mark-out-of-stock. A markdown is a live price on a live storefront and a pull removes sellable stock — both are human decisions, item by item or list by list.",
          },
          {
            kind: "device",
            title: "Print the approved shelf labels",
            detail:
              "A Device node drives the store's label printer to produce shelf-edge markdown labels for exactly the approved lines — so the price on the shelf and the price in the app change from the same approval, instead of the floor re-typing a WhatsApp list.",
          },
          {
            kind: "output",
            title: "Apply approved + hand the floor its walk-list",
            detail:
              "A Tool (MCP) node applies only the approved markdowns and out-of-stock flags; the pull list goes to the floor on Slack/Telegram in shelf-walk order, and unapproved items simply stay on tomorrow's sweep.",
          },
        ],
        connectors: ["Webhook", "Slack", "Telegram", "Email", "Device (shelf-label printer)", "Tool (MCP → inventory / batch-expiry + pricing system)", "Knowledge base", "Approval / HITL"],
        outcome:
          "Every batch in the store is looked at every night and lands as one decision-ready sweep plan — markdowns, pulls and a shelf-order walk-list with labels ready to print — and a human owns every price and every removal.",
        impact:
          "Map-reduce replaces a clipboard walk that only happens when there's time with an exhaustive nightly pass: per-batch classification against a documented shelf-life policy surfaces near-expiry stock while a markdown can still sell it rather than after it can't. The markdown ladder itself lives in one SpEL expression the category team edits, so 'deeper on bakery' is a policy change, not a ticket.",
        build: {
          agents: 2,
          agentsNote:
            "1 mapper (runs once per batch line via the Loop node) + 1 reducer (runs once). Loop, Parallel, the SpEL ladder, the Approval and the label-printer Device node are workflow nodes.",
          pattern: "Map-reduce: Loop/Parallel (map over batches) → reducer (one sweep plan) → human approves every price change and pull → Device prints only what was approved",
          prompt:
            "(Mapper) Assess ONE batch line for a dark store. Input {sku, name, category, batch_id, expiry_date, qty_on_hand, shelf_zone, avg_daily_sales_7d, current_price}. Compute days_to_expiry and cover_days = qty_on_hand / avg_daily_sales_7d (null if sales are 0 — do NOT divide by zero and do NOT substitute a guess). Call `markdown_ladder` for the band and the proposed depth; carry its answer verbatim and never propose a depth it did not return. Return strict JSON {sku, batch_id, days_to_expiry, cover_days, band, proposed_action (MONITOR|MARKDOWN|PULL_FROM_SALE|DONATE_OR_WRITE_OFF), proposed_markdown_pct, shelf_zone, reason}. A proposed markdown is a recommendation; you never set a price.",
          agentRoles: [
            {
              role: "Batch mapper (per batch line)",
              model:
                "Economy tier — it runs once per batch across the whole store export, so this single model choice dominates the total cost of the sweep; the task is bounded arithmetic plus a formula-tool call. A fallback model on the same tier keeps a 2,000-line sweep from dying half-way through the night window on a provider hiccup.",
              prompt:
                "Classify ONE batch into schema-valid JSON, taking the band and depth from `markdown_ladder`. Null for missing inputs, never a guessed sell-through, never a depth the tool did not return, never an action presented as applied.",
            },
            {
              role: "Sweep reducer (once per night)",
              model:
                "Standard tier — this is the one call that reasons across the whole store, so it is where the thinking budget belongs",
              prompt:
                "Aggregate the validated per-batch results into ONE sweep plan: (a) markdown list with SKU, batch, proposed depth and shelf-life left; (b) pull-from-sale list ordered by shelf zone for a single efficient walk; (c) the categories where repeated URGENT bands point at an ordering problem, not a sales problem. Use only the items provided; cite real counts and never estimate a total.",
            },
          ],
          mcp: [
            "Inventory / batch-expiry system — nightly export inbound via Webhook; via Tool (MCP): set the out-of-stock / pull-from-sale flag on approved batch lines (no first-party connector)",
            "Pricing / catalogue system — via Tool (MCP): apply the approved markdown to the SKU at this store only, effective-dated to the sweep",
            "Shelf-label printer — via a Device node: print markdown labels for the approved lines in shelf-zone order",
          ],
          tools: [
            "knowledge_search (scoped collection: shelf-life bands, category markdown policy, donation/write-off SOP)",
            "http_request (pull the batch export; apply approved markdowns and out-of-stock flags)",
            "user-defined tool `markdown_ladder` (SpEL): input.daysToExpiry <= 1 ? 'PULL' : (input.daysToExpiry <= 3 ? 'DEEP' : (input.daysToExpiry <= 7 ? 'SHALLOW' : 'MONITOR')) — a sandboxed expression; pair it with a second one for depth, e.g. (input.avgDailySales ?: 0) == 0 ? 0 : input.baseDepth, so category managers own the ladder per city without a release",
            "send_email (the nightly sweep plan to the store manager and the category desk)",
          ],
          guardrails: [
            "No price is ever changed and no stock is ever marked out by an agent — every markdown and every pull-from-sale passes the Approval node first",
            "Markdown depth comes from the tenant's SpEL ladder, not the model — the agent physically cannot propose a deeper discount than the expression returns",
            "Schema validation on each mapper output — a malformed or zero-sales batch line is excluded from the reduce and flagged for a manual look, never silently marked down",
            "Idempotent per batch id per sweep date, so a re-run of the export cannot double-apply a markdown",
            "Labels print only for approved lines — the Device node is downstream of the Approval, so a shelf can never show a price the app doesn't have",
            "Past-date stock is always a PULL/write-off candidate; a markdown is never a valid proposal for stock already expired",
          ],
          cost:
            "Cost is linear and predictable: N × (economy-tier, hard-capped mapper) + ONE standard-tier reduce. Because the mapper touches every batch line in the store, its tier is the dominant lever — put the reasoning budget in the single reduce instead. The markdown ladder is a SpEL evaluation, so the most commercially sensitive number in the sweep costs nothing per row. The Parallel node buys wall-clock speed inside the night window without changing per-item cost, and scoping the map to categories with real shelf-life (fresh, dairy, bakery, chilled) rather than the full ambient catalogue is the cheapest optimisation available.",
          output:
            "Loop/Parallel maps the cheap agent over every batch line, each row banded by the tenant's SpEL ladder → the reducer produces one sweep plan (markdown list, zone-ordered pull walk-list, over-ordering signals) → the manager approves prices and pulls at the Approval node → a Tool (MCP) node applies only the approved markdowns and out-of-stock flags, a Device node prints the matching shelf labels, and the floor gets its walk-list on Slack/Telegram; anything unapproved simply reappears in tomorrow's sweep.",
        },
      },
      {
        id: "qc-issue-triage",
        title: "Order-issue triage at volume — cluster by picker, station and SKU (map-reduce, 2 agents)",
        scenario:
          "Today: two CX agents work a shared inbox and the in-app issue queue side by side, with the refund console open in a third tab. Missing item, damaged pack, wrong variant, spilled liquid, late. The unwritten rule is 'under ₹200, just refund it' because arguing costs more than the item. Nobody has ever cross-tabbed the tickets, so nobody knows that most damage claims come off one packing station on the evening shift, or that one dishwashing-liquid SKU leaks in every third bag. The pattern exists in the tickets; it just has never been looked at.",
        triggerLabel: "Webhook (customer issue raised in-app) / WhatsApp / Email — batched for the daily reduce",
        steps: [
          {
            kind: "trigger",
            title: "Issues arrive all day",
            detail:
              "Issues land as inbound Webhooks from the app, plus WhatsApp and Email messages from customers. The marketplace/app issue feed has no first-party connector — it arrives via Webhook.",
          },
          {
            kind: "loop",
            title: "Map: classify each issue cheaply",
            detail:
              "A Loop node fans a cheap classifier agent across each issue — one focused pass per complaint, isolated context, so a spike day costs proportionally and not exponentially.",
          },
          {
            kind: "agent",
            title: "Classifier proposes a resolution",
            detail:
              "The classifier returns strict JSON per issue — type, affected SKUs, evidence present (photo? weight mismatch? rider note?), confidence, suspected stage — and a proposed resolution. The AMOUNT is not its call: it reads the tenant's SpEL cap expression.",
          },
          {
            kind: "tool",
            title: "Ops' own refund cap (user-defined SpEL tool)",
            detail:
              "A user-defined SpEL expression clamps the proposed amount to the per-issue cap and flags repeat claimants for human review instead of auto-deciding abuse. CX leadership edits the cap directly — the 'under ₹200 just refund it' rule stops being folklore and becomes an expression with an owner.",
          },
          {
            kind: "approval",
            title: "CX approves the money, in bulk",
            detail:
              "An Approval node presents the proposed resolutions as a reviewable list — obvious ones approved in a batch, anything low-confidence, high-value or repeat-flagged opened individually. No refund or credit issues without a click.",
          },
          {
            kind: "code",
            title: "Cross-tabulate the day",
            detail:
              "A Code node builds the deterministic cross-tabs — defect × packing station, defect × picker, defect × SKU, defect × hour — so the reducer reasons over real counts instead of recounting in prose.",
          },
          {
            kind: "agent",
            title: "Reduce: find the pattern behind the noise",
            detail:
              "Once a day a reducer agent reads the cross-tabs and turns 'lots of damage complaints' into 'liquid SKUs, packing station 3, evening shift' — with exact counts and a stated minimum-occurrence threshold.",
          },
          {
            kind: "output",
            title: "Resolutions out, pattern report in",
            detail:
              "Approved resolutions go back to the customer on their own channel (Sarvam-localised) and to the refund/credit system via a Tool (MCP) node; the pattern report goes to the store manager on Slack and by email as an operational fix list, not a CX metric.",
          },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Slack", "Sarvam (Indian languages)", "Razorpay (UPI)", "Tool (MCP → order system / credit ledger)", "Approval / HITL"],
        outcome:
          "Every issue gets a policy-grounded proposed resolution the same day, and the day's complaints resolve into a ranked list of operational causes tied to a picker, a station, a zone or a SKU.",
        impact:
          "Two jobs that are usually mashed together get separated: cheap per-issue classification for speed, and one daily cross-issue reduce for cause-finding. Refunding faster makes customers happier; clustering by station and SKU is what stops the next complaint being raised at all. And the refund cap moves out of tribal knowledge into a SpEL expression CX leadership can change for one city without redeploying anything.",
        build: {
          agents: 2,
          agentsNote:
            "1 classifier (runs once per issue via the Loop node) + 1 clustering reducer (runs once a day). The SpEL cap, the Code cross-tab, the Loop and the Approval are workflow nodes.",
          pattern: "Map-reduce: per-issue classify (economy) → tenant-owned SpEL cap → human-approved resolutions → deterministic cross-tab → daily cluster (standard)",
          prompt:
            "(Classifier) Triage ONE quick-commerce order issue. Input {issue_id, order_id, customer_text, attachments_present, order_lines[], delivery_timestamps, prior_issue_count_90d}. Return strict JSON {type (MISSING_ITEM|DAMAGED|WRONG_ITEM|QUALITY|LATE|OTHER), affected_skus[], evidence (PHOTO|WEIGHT_MISMATCH|RIDER_NOTE|NONE), confidence (0-1), proposed_resolution (REFUND_ITEM|GOODWILL_CREDIT|REPLACE|DECLINE_WITH_EXPLANATION), proposed_amount, reason, suspected_stage (PICK|PACK|TRANSIT|UNKNOWN)}. Take proposed_amount from the `refund_cap` tool — never exceed or second-guess it. Never treat a high prior_issue_count as proof of abuse on your own; the tool flags it and a human decides. Confidence below the threshold means the human reviews it individually. You PROPOSE; the refund is issued by the workflow only after approval.",
          agentRoles: [
            {
              role: "Issue classifier (per issue)",
              model:
                "Economy tier — this is the single highest-volume model call in the whole CX stack; classification into a fixed schema against a documented matrix is exactly what the cheap tier is good at, and its tier sets the entire bill. Configure a same-tier fallback so a provider outage during a spike day degrades throughput, not the queue.",
              prompt:
                "Classify ONE issue into strict JSON with a proposed resolution, taking the amount from `refund_cap`. Never exceed the cap, never infer abuse from history alone, never present a proposal as issued.",
            },
            {
              role: "Pattern reducer (once daily)",
              model:
                "Standard tier — one call per day that has to reason across the full day's issues to find non-obvious co-occurrence; this is the right place for the reasoning spend, and the Code node has already done the counting",
              prompt:
                "Read the cross-tabs and report the top clusters with real counts and the specific operational hypothesis for each (e.g. 'liquid SKUs, station 3, evening shift'). State counts exactly; never estimate, and never assert a cause a single occurrence can support.",
            },
          ],
          mcp: [
            "App / marketplace issue feed — inbound via Webhook (no first-party connector)",
            "Order-management system — via Tool (MCP): fetch order lines, weights and delivery timestamps for the disputed order",
            "Picking / WMS attribution — via Tool (MCP): resolve which picker and which packing station handled the order",
            "Refund + credit ledger — via Tool (MCP): post the approved refund or goodwill credit, called only after the Approval node",
          ],
          tools: [
            "knowledge_search (scoped collection: resolution matrix, per-issue caps, evidence requirements)",
            "http_request (fetch order lines and station attribution; post approved refunds/credits)",
            "user-defined tool `refund_cap` (SpEL): input.priorIssues90d >= 3 ? 'HUMAN_REVIEW' : (input.itemValue > input.categoryCap ? input.categoryCap : input.itemValue) — a sandboxed expression, so CX leadership owns the cap and the repeat-claim flag per city and per category, with no deployment",
            "send_email (daily operational fix list to the store manager)",
          ],
          guardrails: [
            "No refund, credit or replacement is ever issued by an agent — every resolution passes the Approval node, in bulk for the obvious ones and individually for low-confidence, high-value or repeat-flagged cases",
            "Amounts are clamped by the tenant's SpEL cap expression; the model cannot propose above it and cannot argue around it",
            "A high prior-issue count is never treated as proof of abuse by the agent — the expression routes it to a human, because getting this wrong punishes real customers",
            "Counting happens in a deterministic Code node, so the daily report states exact figures and refuses to assert a cause from a single occurrence",
            "Idempotent per issue id — a resent webhook or a duplicate WhatsApp message cannot double-refund",
          ],
          cost:
            "Cost is N × economy classifier + ONE standard reduce per day. Because the classifier runs on every single issue, keeping it on the economy tier with a hard output cap is the whole cost strategy — a premium model here would multiply the bill by the day's complaint count for no accuracy the resolution matrix and the SpEL cap don't already provide. Moving the counting into the Code node is what lets the daily reduce stay on the standard tier instead of a premium one.",
          output:
            "Loop maps the economy classifier over every issue, with amounts clamped by the tenant's `refund_cap` expression → CX approves in bulk at the Approval node → a Tool (MCP) node issues only the approved refunds/credits and the customer is answered on their own channel via Sarvam → a Code node cross-tabs the day → the standard-tier reducer names the top clusters by picker/station/SKU/hour and sends a ranked operational fix list to the store manager on Slack and by email.",
        },
      },
    ],
  },

];

// Cloud-kitchen & delivery-only brand recipes. Header notes and honesty rules: recipes-qcommerce-cloudkitchen.tsx.

import type { Industry } from "./recipes-data";
import { ChefHat } from "lucide-react";

export const CLOUD_KITCHEN_INDUSTRIES: Industry[] = [
  // ======================================= CLOUD KITCHENS & DELIVERY-ONLY BRANDS
  {
    id: "cloud-kitchen",
    name: "Cloud kitchens & delivery-only brands",
    icon: ChefHat,
    tagline:
      "Root-cause an aggregator rating drop, separate a brand's problem from the shared kitchen's, throttle prep capacity before the surge breaks it — with every 86, price change and refund human-approved.",
    recipes: [
      {
        id: "ck-rating-drop",
        title: "Aggregator rating drop → root-cause across four evidence streams (supervisor + 3 specialists)",
        scenario:
          "Today: the brand owner has the aggregator partner dashboard open in six tabs — one per outlet — scrolling reviews on a phone at 11pm and copying the bad ones into a WhatsApp note. The account manager's advice is 'improve quality and packaging'. The order-timing export is a CSV nobody opens because it's 40 columns wide, the menu change log is whatever the ops WhatsApp group remembers, and the packaging switch three weeks ago has already been forgotten. So the fortnight's rating slide gets diagnosed by vibes, and the fix is usually 'tell the kitchen to be careful'.",
        triggerLabel: "Webhook (scheduled rating/review pull from the aggregator partner portal)",
        steps: [
          {
            kind: "trigger",
            title: "Rating window closes",
            detail:
              "A scheduled Webhook delivers the review and rating export plus the order-timing export for the period. The aggregator partner portal has no first-party connector — data arrives via Webhook and is read back via a Tool (MCP) node.",
          },
          {
            kind: "supervisor",
            title: "Supervisor frames the question",
            detail:
              "A Supervisor node scopes the investigation to the brand, the outlet and the window, then delegates the SAME period to three specialists so their findings are directly comparable rather than each analysing a different slice.",
          },
          {
            kind: "parallel",
            title: "Three specialists, same window",
            detail:
              "A Parallel node runs the review-text specialist, the timing specialist and the menu/change-log specialist concurrently — one wall-clock pass instead of three sequential ones, and none of them can see the others' evidence.",
          },
          {
            kind: "agent",
            title: "Review-text specialist",
            detail:
              "Reads only the free-text reviews and low-star comments for the window, extracting recurring complaint themes with verbatim quotes and counts — food, packaging/spill, missing item, portion, temperature. It is blind to timing data by design.",
          },
          {
            kind: "agent",
            title: "Timing specialist",
            detail:
              "Reads only the order-timing export — accept-to-ready, ready-to-pickup, pickup-to-delivery — and reports which leg moved during the window and on which day-parts. It never sees a review, so it cannot be led into agreeing with one.",
          },
          {
            kind: "agent",
            title: "Menu & change specialist",
            detail:
              "Reads only the menu, price and packaging change log (via a Tool (MCP) node) and returns the dated timeline any proposed cause has to line up with. If the packaging switch post-dates the decline, this is what catches it.",
          },
          {
            kind: "agent",
            title: "Synthesis: one ranked cause list",
            detail:
              "A synthesis agent correlates the three streams into ranked hypotheses, each stated with the evidence it rests on, explicitly labelled correlation, capped at LOW confidence when only one stream supports it — and each with the test that would confirm or kill it.",
          },
          {
            kind: "approval",
            title: "Owner approves any action",
            detail:
              "An Approval node holds every action the report implies — re-listing or delisting an item, a price or portion change, a packaging switch, a prep-time commitment change on the aggregator. The report is free; the actions are human.",
          },
        ],
        connectors: ["Webhook", "Email", "Slack", "Tool (MCP → aggregator partner portal / menu + change log)", "Knowledge base", "Approval / HITL"],
        outcome:
          "A rating slide becomes a ranked, evidence-cited cause list within one run — six dashboard tabs and a WhatsApp note replaced by one report — with the actions it implies queued for the owner rather than executed.",
        impact:
          "The mechanism is stream isolation: each specialist sees only its own evidence, so the timing analysis cannot be talked into agreeing with the review themes. The synthesis then has to show where INDEPENDENT streams line up, which is what separates a real cause from a plausible story — and the change-log timeline is what stops a fix being aimed at something that happened after the decline started.",
        build: {
          agents: 4,
          agentsNote:
            "1 supervisor-scoped synthesis agent + 3 evidence specialists (review text / timing / menu-and-change log). The Parallel fan-out, the reportability threshold (a SpEL tool) and the action Approval are workflow nodes.",
          pattern: "Supervisor → parallel specialists on isolated evidence streams → synthesis → human-approved actions",
          prompt:
            "(Synthesis) You are investigating a rating decline for {{brand}} at {{outlet}} over {{window}}. You receive three INDEPENDENT specialist reports (review themes with counts and quotes; order-timing leg movements by day-part; the menu/price/packaging change log). Produce a ranked list of at most 5 hypotheses. For each: {hypothesis, supporting_evidence[] (cite the specialist and the exact figure or quote), contradicting_evidence[], confidence (LOW|MED|HIGH), confirming_test}. RULES: (a) label every relationship as correlation, never causation; (b) a hypothesis supported by only ONE stream is capped at LOW confidence; (c) if a change in the change log post-dates the decline, say so and drop it; (d) never invent a percentage, a rating delta or a count that a specialist did not report; (e) propose actions as RECOMMENDATIONS ONLY — delisting an item, changing a price or portion, switching packaging and changing the committed prep time all require human approval.",
          agentRoles: [
            {
              role: "Review-text specialist",
              model:
                "Economy tier — volume work (every review in the window) and the task is theme extraction with quotes, not judgement",
              prompt:
                "Read ONLY the free-text reviews for the window. Return recurring themes with exact counts and verbatim supporting quotes, keeping only those the `theme_threshold` tool marks REPORTABLE. No causes, no recommendations, no reading of timing data.",
            },
            {
              role: "Timing specialist",
              model:
                "Economy tier — bounded arithmetic over an export; deliberately blind to review text so it cannot be led",
              prompt:
                "Read ONLY the order-timing export. Report which leg (accept→ready, ready→pickup, pickup→delivery) moved during the window, by day-part, with real figures. State 'no material movement' when that is what the data shows.",
            },
            {
              role: "Menu & change-log specialist",
              model:
                "Economy tier — a small, factual timeline extraction",
              prompt:
                "Read ONLY the menu/price/packaging change log. Return a dated timeline of what changed. Do not speculate about effects; the timeline is the deliverable.",
            },
            {
              role: "Synthesis (once per investigation)",
              model:
                "Premium tier — the single hard step: correlating three independent streams, resisting the obvious story, and being explicit about what would disprove each hypothesis. One call per investigation, so the cost is bounded; a standard-tier fallback model is configured so an outage delays the report rather than losing the run.",
              prompt:
                "Correlate the three specialist reports into at most 5 ranked hypotheses with cited evidence, contradicting evidence, a LOW-confidence cap for single-stream support, and a confirming test for each. Correlation language only. Recommendations, never actions.",
            },
          ],
          mcp: [
            "Aggregator partner portal — reviews/ratings export inbound via Webhook; via Tool (MCP): fetch reviews, star distribution and the order-timing export for the brand/outlet/window (no first-party connector)",
            "Menu / price / packaging change log — via Tool (MCP): list dated menu, price and packaging changes for the outlet in the window",
            "Email / Slack — where the ranked cause report lands",
          ],
          tools: [
            "knowledge_search (scoped collection: brand standards, packaging spec, committed prep times per brand)",
            "http_request (pull reviews, timings and the change log for the window)",
            "user-defined tool `theme_threshold` (SpEL): input.mentions >= input.minOccurrences ? 'REPORTABLE' : 'INSUFFICIENT' — a sandboxed expression the brand owner tunes per outlet, so a small outlet isn't held to a big outlet's evidence bar and nobody has to argue about it in a meeting",
            "web_search (optional: check whether a rating dip coincides with a publicly reported aggregator-wide outage or city-wide delivery disruption, so an external cause isn't misattributed to the kitchen)",
            "send_email (the ranked cause report to the brand owner and kitchen operator)",
          ],
          guardrails: [
            "Every action the report implies — delist/re-list an item, change a price or portion, switch packaging, change the committed prep time — passes the Approval node; the workflow ships a report, not a change",
            "Specialists are evidence-isolated by design: the timing specialist never sees review text and vice versa, so agreement between streams is real rather than induced",
            "A hypothesis supported by a single stream is capped at LOW confidence, and every relationship is stated as correlation with a named confirming test",
            "Theme reportability is decided by the tenant's SpEL threshold, not by the model deciding what feels significant",
            "No invented ratings, deltas or counts — the synthesis may only cite figures a specialist actually reported",
            "Reviews are customer content: quotes are carried verbatim for evidence and never rewritten to make a point",
          ],
          cost:
            "Three economy specialists (the volume readers) plus ONE premium synthesis per investigation — cheap where the work is reading, expensive exactly once where the work is thinking. The Parallel node cuts wall-clock without changing spend. This runs per brand per window, not per order, so absolute cost is small; do not upgrade the specialists, because a premium model reading reviews adds cost proportional to review volume and buys nothing the synthesis doesn't already do.",
          output:
            "The supervisor scopes brand/outlet/window → three evidence-isolated specialists run in Parallel, with theme reportability gated by the tenant's SpEL threshold → the premium synthesis returns at most five ranked hypotheses, each with cited evidence, contradicting evidence and a confirming test → the report goes to the owner on Email/Slack, and every action it recommends waits at the Approval node.",
        },
      },
      {
        id: "ck-shared-kitchen-clusters",
        title: "One kitchen, five brands — is it the brand's recipe or the kitchen's station? (map-reduce, 2 agents)",
        scenario:
          "Today: one kitchen runs five delivery-only brands off three shared stations, and each brand has its own partner login. So five different people each look at their own small pile of complaints on their own dashboard, each concludes 'my biryani travels badly', and each separately asks the kitchen to be more careful. Nobody has ever put the five piles on one table — which is the only way to see that every leaking curry, whichever brand's name was on the bag, went into the same 500ml container, or that the cold items are only cold when the fryer station is slammed.",
        triggerLabel: "Webhook (complaint/review events across all brands in the kitchen) — reduced on a daily cycle",
        steps: [
          {
            kind: "trigger",
            title: "Complaints from every brand, one stream",
            detail:
              "Inbound Webhooks carry complaints and low-star reviews for all brands operating from the outlet, plus WhatsApp and Email complaints. The aggregator feeds have no first-party connector — everything arrives via Webhook and is enriched via a Tool (MCP) node.",
          },
          {
            kind: "loop",
            title: "Map: normalise each complaint",
            detail:
              "A Loop node fans a cheap mapper across every complaint, normalising it to a deliberately brand-agnostic vocabulary — item, station that cooked it, container SKU, day-part, defect type. Brand-agnostic is the whole point: it's what lets five piles become one table.",
          },
          {
            kind: "agent",
            title: "Mapper tags defect + attribution",
            detail:
              "The mapper returns strict JSON — defect type (LEAK / COLD / UNDERCOOKED / MISSING_ITEM / PORTION / WRONG_ITEM), the item, the mapped station and container SKU (from the recipe→station map in Knowledge), and whether the text is specific enough to be usable at all.",
          },
          {
            kind: "code",
            title: "Cross-tabulate before reasoning",
            detail:
              "A Code node builds the cross-tabs — defect × station, defect × container SKU, defect × day-part, defect × brand — so the reducer reasons over real counts instead of recounting in prose. Counting is what models do expensively and badly.",
          },
          {
            kind: "tool",
            title: "Operator's own cluster gate (user-defined SpEL tool)",
            detail:
              "A user-defined SpEL expression decides what counts as a pattern: spread across brands plus enough occurrences means kitchen issue; concentrated in one brand means brand issue; below the floor means insufficient evidence. The operator raises that floor when a bigger kitchen makes coincidences more likely — one expression, no deployment, and no model deciding what 'enough' means.",
          },
          {
            kind: "agent",
            title: "Reduce: brand problem vs kitchen problem",
            detail:
              "A reducer agent reads the gated cross-tabs and splits the findings into two lists: defects concentrated in ONE brand's items (recipe/spec issue, that owner's call) and defects spread ACROSS brands but concentrated on a shared station, container or shift (kitchen issue, the operator's call).",
          },
          {
            kind: "approval",
            title: "Approve the fix, per owner",
            detail:
              "An Approval node routes each proposed action to whoever owns it — an 86/delist or recipe-spec change to the brand owner, a container switch or station re-sequencing to the kitchen operator. Nothing is delisted or changed automatically.",
          },
          {
            kind: "output",
            title: "Two reports, two audiences",
            detail:
              "Output nodes send each brand owner only their own brand's findings, and the kitchen operator the shared-cause report — so a shared-kitchen tenant never sees another tenant's complaint data.",
          },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Slack", "Tool (MCP → aggregator feeds / KDS / recipe-station map)", "Knowledge base", "Approval / HITL"],
        outcome:
          "Every complaint across every brand in the kitchen is tagged to a station, a container and a day-part, and the daily output separates 'this brand's recipe' from 'this kitchen's station' — with each fix routed to the person who can actually make it.",
        impact:
          "The mechanism is pooling evidence that is normally siloed by brand login: a defect that looks like noise inside one small brand's complaints becomes an obvious pattern once the same container or station is seen across all five. Per-tenant output boundaries keep that pooling operationally legitimate, and the pattern floor is a SpEL expression the operator owns rather than an argument about whether three is a trend.",
        build: {
          agents: 2,
          agentsNote:
            "1 mapper (once per complaint via the Loop node) + 1 reducer (once per cycle). The cross-tab Code node, the SpEL cluster gate, the per-owner Approval routing and the split Outputs are workflow nodes.",
          pattern: "Map-reduce with a deterministic cross-tab and a tenant-owned SpEL pattern gate in between, then owner-routed approvals",
          prompt:
            "(Mapper) Normalise ONE complaint from a multi-brand cloud kitchen. Input {complaint_id, brand, item_name, customer_text, order_timestamp, attachments_present}. Using the recipe→station and item→container map in Knowledge, return strict JSON {complaint_id, brand, item, defect (LEAK|COLD|UNDERCOOKED|MISSING_ITEM|PORTION|WRONG_ITEM|UNCLEAR), station, container_sku, day_part, usable (true|false), reason}. RULES: (a) if the text does not clearly indicate a defect, set defect=UNCLEAR and usable=false — a vague 'bad experience' is not evidence; (b) station and container come ONLY from the map in Knowledge, never guessed from the item name; (c) never assign blame to a brand or a person — you tag, you do not conclude; (d) carry no customer personal details beyond what the schema needs.",
          agentRoles: [
            {
              role: "Complaint mapper (per complaint)",
              model:
                "Economy tier — it runs on every complaint from every brand in the kitchen, so its tier drives the total cost; the task is tagging against a lookup map, not judgement. A same-tier fallback model keeps a bad provider day from silently dropping a brand's complaints out of the day's evidence.",
              prompt:
                "Tag ONE complaint to defect/station/container/day-part using only the Knowledge map. UNCLEAR + usable:false is the correct answer for vague text. Strict JSON, no blame, no conclusions.",
            },
            {
              role: "Cross-brand reducer (once per cycle)",
              model:
                "Standard tier — one call that reasons over the cross-tabs after the Code node has done the counting and the SpEL gate has ruled on what qualifies as a pattern; that division of labour is exactly why this doesn't need a premium model",
              prompt:
                "From the gated cross-tabs, produce TWO lists: (1) BRAND-SPECIFIC defects concentrated in a single brand's items, with counts and the proposed recipe/spec action; (2) SHARED-KITCHEN defects spread across brands but concentrated on one station, container SKU or day-part, with counts and the proposed operational action. State exact counts, respect the gate's 'insufficient evidence' verdicts, and never propose an 86/delist or container switch as done.",
            },
          ],
          mcp: [
            "Aggregator complaint/review feeds for every brand at the outlet — inbound via Webhook; via Tool (MCP): fetch the complaint body and order reference per brand login (no first-party connector)",
            "Kitchen display system (KDS) — via Tool (MCP): resolve which station fired each item and its completion timestamp",
            "Packaging/container master — via Tool (MCP): resolve the container SKU actually used for the item on that date",
          ],
          tools: [
            "knowledge_search (scoped collection per outlet: recipe→station map, item→container spec, defect taxonomy)",
            "http_request (pull complaint feeds and KDS station timings)",
            "user-defined tool `cluster_gate` (SpEL): input.occurrences < input.minOccurrences ? 'INSUFFICIENT' : (input.brandCount > 1 ? 'KITCHEN_ISSUE' : 'BRAND_ISSUE') — a sandboxed expression the kitchen operator owns, so the evidence floor rises with kitchen size without a developer and without re-prompting the model",
            "send_email (per-brand findings to each brand owner; the shared-cause report to the operator)",
          ],
          guardrails: [
            "Every proposed fix — 86-ing or delisting an item, a recipe/portion spec change, a container switch, a station re-sequence — passes the Approval node, routed to the owner who can actually authorise it",
            "Per-tenant output boundary: each brand owner receives only their own brand's findings; only the kitchen operator sees the cross-brand shared-cause report",
            "Vague complaints are marked UNCLEAR/unusable rather than force-fitted into a defect category to make a cluster look bigger",
            "Cross-tabs are computed in a deterministic Code node, and pattern significance is decided by the tenant's SpEL gate — the reducer reasons, it does not count and it does not set the bar",
            "Station and container attribution comes only from the maintained Knowledge map — the agent never infers which station cooked an item from its name",
          ],
          cost:
            "N × economy mapper + one deterministic cross-tab (free — it's code) + one free SpEL gate + ONE standard reduce per cycle. Moving counting and thresholding out of the model entirely is what lets the reduce stay standard rather than premium, and running the reduce daily rather than per complaint is what makes cross-brand pattern-finding affordable at all.",
          output:
            "Loop maps the economy tagger over every brand's complaints → a Code node builds defect × station / container / day-part / brand cross-tabs → the `cluster_gate` SpEL expression rules each candidate KITCHEN_ISSUE, BRAND_ISSUE or INSUFFICIENT → the standard-tier reducer writes the two lists with exact counts → each proposed fix waits at the Approval node routed to its real owner → Output nodes send each brand owner only their own findings and the operator the shared-cause report.",
        },
      },
      {
        id: "ck-surge-throttle",
        title: "Prep-capacity surge → throttle before the kitchen breaks its own prep time",
        scenario:
          "Today: it starts raining, or a match goes to the last over, and tickets stack five deep on the KDS across all five brands sharing three stations. The head chef is at the pass with oil on both hands and no chance of reading a dashboard. The manager notices ten minutes late, phones the aggregator account manager to ask for prep time to be extended, gets voicemail, then starts pausing items in the partner app one brand login at a time. By the time the third login is done, the hour is already late, riders are stacked at the door, and the rating damage is booked.",
        triggerLabel: "Webhook (order-accept events + KDS queue depth, evaluated on a rolling window)",
        steps: [
          {
            kind: "trigger",
            title: "Queue depth crosses the watch line",
            detail:
              "A rolling Webhook feed of accepted orders and KDS queue depth per station. The aggregator order feed and the KDS have no first-party connector — both arrive via Webhook and a Tool (MCP) node.",
          },
          {
            kind: "condition",
            title: "Real surge or a normal spike?",
            detail:
              "A Condition node compares live queue depth and accept rate against the station's documented throughput in Knowledge, so the workflow only escalates when committed prep times are genuinely at risk — not on every busy ten minutes.",
          },
          {
            kind: "agent",
            title: "Propose the least-damaging throttle",
            detail:
              "An Agent node projects prep time per station for the next 30 minutes and proposes the smallest intervention that restores commitment — extend prep time by N minutes, pause the slowest-station items, or pause a brand — each with its projected effect and what it costs in orders at risk.",
          },
          {
            kind: "tool",
            title: "Operator's own throttle ladder (user-defined SpEL tool)",
            detail:
              "A user-defined SpEL expression maps the projected overshoot to the permitted intervention step, so the escalation ladder is the operator's policy, not the model's mood — and it can differ for a premium brand and a value brand in the same kitchen by editing one expression.",
          },
          {
            kind: "approval",
            title: "Kitchen manager approves the throttle",
            detail:
              "An Approval node holds it. Extending prep time, pausing an item and pausing a brand are all commercial decisions visible to customers on the aggregator — the agent proposes, the manager decides, with the projected effect in front of them.",
          },
          {
            kind: "tool",
            title: "Apply across every brand login at once",
            detail:
              "On approval, a Tool (MCP) node applies the approved prep-time change or item pause on the partner portal for each affected brand — the three-logins-in-a-row problem collapses into one write, keyed to the surge event so a repeat trigger can't stack changes.",
          },
          {
            kind: "voice",
            title: "Tell the floor out loud",
            detail:
              "A Voice node (ElevenLabs, with Sarvam for the kitchen's working language) announces the approved change on the floor speaker or the manager's phone — hands are busy and covered in oil; nobody is reading a dashboard mid-surge.",
          },
          {
            kind: "output",
            title: "Auto-restore proposal when it clears",
            detail:
              "When queue depth falls back under the line, the workflow proposes reverting the throttle — and that revert goes through the same Approval node, because un-pausing an item is just as commercial as pausing it. Items don't stay paused overnight because everyone forgot.",
          },
        ],
        connectors: ["Webhook", "Slack", "Voice / Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "Tool (MCP → aggregator partner portal / KDS)", "Knowledge base", "Approval / HITL"],
        outcome:
          "Surges are met with an approved, reversible throttle while the hour is still savable, applied across every brand login in one write — and the floor hears about it without looking at a screen.",
        impact:
          "Prep time is projected from live queue depth against documented station throughput, so the choice becomes 'extend prep by 6 minutes or pause two items', made in advance with the trade-off visible, instead of discovered after the fact. The escalation ladder lives in a SpEL expression the operator edits per brand, and the auto-restore proposal is what stops the classic failure of items left paused long after the rain stopped.",
        build: {
          agents: 1,
          agentsNote:
            "1 capacity agent; the surge gate, the SpEL throttle ladder, the manager approval, the portal write, the floor announcement and the restore proposal are workflow nodes.",
          pattern: "Rolling-window Condition gate → single agent projection with trade-offs → tenant-owned SpEL ladder → human approval → reversible action + Voice broadcast",
          prompt:
            "You are a kitchen capacity assistant for {{outlet}}, which runs multiple delivery-only brands off shared stations. Given {live_queue_depth_by_station, accept_rate_last_15m, committed_prep_time_by_brand, open_orders[], station_throughput (from Knowledge), item→station map} project the prep time per station for the next 30 minutes. Call `throttle_step` with the projected overshoot and propose only the intervention it permits, choosing from EXTEND_PREP_TIME (by N minutes), PAUSE_ITEMS (list them) or PAUSE_BRAND (last resort). For each option give {projected_prep_after, orders_at_risk, revenue_exposure_note}. RULES: (a) project only from the throughput figures in Knowledge and the live queue — never invent a capacity number; (b) always present the least-disruptive viable option FIRST and say plainly what it costs; (c) if no intervention is needed, say so and stop; (d) you PROPOSE — pausing an item, pausing a brand and changing a committed prep time are all human-approved.",
          agentRoles: [
            {
              role: "Capacity projector (once per surge evaluation)",
              model:
                "Standard tier — it runs only when the Condition gate says a real surge is underway (a handful of times a day, not per order), and the work is a genuine trade-off under time pressure where a wrong call is expensive. A fallback model matters more here than almost anywhere else: the one moment you cannot afford a provider outage is mid-surge, so a same-tier fallback is configured.",
              prompt:
                "Project prep time per station for the next 30 minutes from live queue depth and documented throughput, then propose the least-disruptive throttle permitted by `throttle_step`, with its projected effect and cost. Strict JSON, ranked least-disruptive first, and 'no action needed' when that is the honest answer.",
            },
          ],
          mcp: [
            "Aggregator partner portal — via Tool (MCP): read the committed prep time per brand, and on approval set the prep-time offset or pause/unpause named items for each affected brand login (no first-party connector)",
            "Kitchen display system (KDS) — via Tool (MCP): read live queue depth and open-ticket age per station",
            "Order-accept events — inbound via Webhook on a rolling window",
          ],
          tools: [
            "knowledge_search (scoped collection: station throughput, item→station map, committed prep times per brand)",
            "http_request (read KDS queue depth; apply the approved prep-time change or item pause)",
            "user-defined tool `throttle_step` (SpEL): input.projectedPrep - input.committedPrep > 15 ? 'PAUSE_ITEMS' : (input.projectedPrep - input.committedPrep > 5 ? 'EXTEND_PREP' : 'NONE') — a sandboxed expression, so the operator sets a different escalation ladder for a premium brand than for a value brand without touching the prompt or shipping code",
          ],
          guardrails: [
            "Extending prep time, pausing an item and pausing a brand all pass the Approval node — and so does un-pausing, because restoring availability is equally commercial",
            "Projections use only documented station throughput and live queue depth; the agent may not invent a capacity figure to justify a throttle",
            "The intervention step is bounded by the tenant's SpEL ladder, so the model cannot jump straight to pausing a brand because the queue looks scary",
            "Least-disruptive option is always presented first with its cost stated, so the manager is choosing rather than rubber-stamping",
            "Portal writes are keyed to the surge event id — a repeated trigger cannot stack prep-time extensions on top of each other",
            "The Voice announcement is informational and fires only AFTER approval, so the floor can never act on an unapproved instruction",
          ],
          cost:
            "Deliberately NOT a per-order workflow: the Condition gate means the standard-tier agent runs a handful of times a day, only when a surge is real. That is what makes a standard tier affordable here — this is the hard step (a live trade-off with commercial consequences) and also a rare one. If it fires constantly, the fix is the throughput figures in Knowledge, not a cheaper model. The ladder itself is a free SpEL evaluation.",
          output:
            "The rolling Condition gate confirms a real surge → the agent projects prep time per station and ranks the options the `throttle_step` ladder permits, least-disruptive first with its cost → the manager approves at the Approval node → a Tool (MCP) node applies the change across every affected brand login in one write → a Voice node (ElevenLabs/Sarvam) announces it on the floor → when the queue clears, the revert is proposed and goes through the same approval.",
        },
      },
      {
        id: "ck-missing-item-dispute",
        title: "Missing-item & packaging disputes — build the evidence pack, contest or concede on purpose",
        scenario:
          "Today: the deduction shows up as a line in the weekly settlement statement, and the accounts person emails the ops WhatsApp group asking 'is this ours?'. Answering properly means opening the KDS history for that ticket, finding the packing photo on the shared phone in the kitchen, checking what container that item ships in, and cross-checking the rider handover time — twenty minutes, for a deduction worth a few hundred rupees. So the kitchen concedes everything by default, and after a year nobody can tell which disputes were genuinely theirs and which were transit damage they paid for anyway.",
        triggerLabel: "Webhook (dispute / deduction raised by the aggregator) / Email",
        steps: [
          {
            kind: "trigger",
            title: "Deduction or dispute lands",
            detail:
              "An inbound Webhook (or an Email from the partner settlement mailbox) carries the deduction — order, brand, claimed defect, amount. The settlement/chargeback console has no first-party connector; it comes in via Webhook and is written back via a Tool (MCP) node.",
          },
          {
            kind: "parallel",
            title: "Pull the evidence, all at once",
            detail:
              "A Parallel node runs the Tool (MCP) lookups concurrently — KDS item-completion timestamps, packing checklist and photo reference, container SKU used, rider handover time — so the twenty-minute manual dig becomes one round trip.",
          },
          {
            kind: "tool",
            title: "Accounts' own fight threshold (user-defined SpEL tool)",
            detail:
              "A user-defined SpEL expression compares the deduction to the brand's own worth-the-fight cut-off. Trivial amounts route to fast-concede; the rest get assessed. The finance lead sets that number per brand — the 'just concede it' rule finally has an owner and a value instead of being whatever the accounts person feels that week.",
          },
          {
            kind: "agent",
            title: "Assess: contest or concede",
            detail:
              "An Agent node compares the claim against the evidence pack and returns CONTEST (with the specific evidence that contradicts the claim), CONCEDE (with the internal cause tagged), or INSUFFICIENT_EVIDENCE — and drafts factual, non-accusatory dispute text when contesting.",
          },
          {
            kind: "approval",
            title: "Owner approves the response",
            detail:
              "An Approval node holds both paths. Filing a contest is a statement to a commercial partner; conceding is money. Neither is an agent's call — the recommendation and its evidence are presented, the owner clicks.",
          },
          {
            kind: "output",
            title: "File it, and tag the internal cause",
            detail:
              "On approval, a Tool (MCP) node files the contest with the evidence pack or accepts the deduction, and every CONCEDE is tagged to a cause (station, packer, container) so it feeds the same shared-kitchen pattern report rather than vanishing into settlement.",
          },
        ],
        connectors: ["Webhook", "Email", "Slack", "Tool (MCP → settlement console / KDS / rider dispatch)", "Knowledge base", "Approval / HITL"],
        outcome:
          "Every deduction gets an evidence-backed contest-or-concede recommendation the same day, and each conceded one leaves behind a tagged internal cause instead of just a smaller payout.",
        impact:
          "The mechanism is assembling the evidence pack automatically — KDS completion, packing checklist, container SKU, rider handover — so the twenty-minute dig that made blanket conceding rational stops being the deciding factor. Tagging concessions to a cause is what turns settlement leakage into an operational fix list, and the fight threshold becomes an owned SpEL number rather than a mood.",
        build: {
          agents: 1,
          agentsNote:
            "1 dispute-assessment agent; the parallel evidence gathering, the SpEL value threshold, the owner approval and the filing are workflow nodes.",
          pattern: "Parallel evidence assembly → tenant-owned SpEL value gate → single-agent assessment → human-approved filing → cause tagged back into the kitchen pattern report",
          prompt:
            "You are a settlement-dispute assistant for {{brand}} at {{outlet}}. Given a deduction {dispute_id, order_id, brand, claimed_defect (MISSING_ITEM|SPILL|WRONG_ITEM|QUALITY), amount} and an evidence pack {kds_item_completion[], packing_checklist_complete, packing_photo_present, container_sku, rider_handover_time, delivery_time}: return strict JSON {recommendation (CONTEST|CONCEDE|INSUFFICIENT_EVIDENCE), confidence, evidence_cited[], internal_cause (STATION|PACKER|CONTAINER|TRANSIT|UNKNOWN), draft_dispute_text}. RULES: (a) recommend CONTEST only when a specific piece of evidence directly contradicts the claim — cite it; a mere absence of proof of fault is INSUFFICIENT_EVIDENCE, not CONTEST; (b) when recommending CONCEDE, always tag an internal cause (UNKNOWN is allowed and preferred to a guess); (c) never assert a fact the evidence pack does not contain; (d) the draft must be factual and non-accusatory — it goes to a commercial partner and must never blame the rider or the customer; (e) you RECOMMEND — filing a contest and accepting a deduction are both human-approved.",
          agentRoles: [
            {
              role: "Dispute assessor (once per deduction above the fight threshold)",
              model:
                "Standard tier — deduction volume is modest next to customer complaints, and the task is genuine evidence reasoning where a wrong CONTEST costs partner goodwill. This is the hard step, not the volume step; the SpEL threshold keeps trivial deductions from ever reaching it.",
              prompt:
                "Compare the claim against the evidence pack and return CONTEST (with cited contradicting evidence), CONCEDE (with an internal cause tagged) or INSUFFICIENT_EVIDENCE, plus a factual, non-accusatory draft. Never assert a fact the pack does not contain.",
            },
          ],
          mcp: [
            "Settlement / chargeback console — deduction inbound via Webhook; via Tool (MCP): file the approved contest with its evidence pack, or accept the deduction (no first-party connector)",
            "Kitchen display system (KDS) — via Tool (MCP): fetch item-level completion timestamps for the disputed ticket",
            "Rider-dispatch platform — via Tool (MCP): fetch handover and delivery timestamps for the order",
            "Packing checklist / photo store — via Tool (MCP): fetch the checklist state and photo reference for the order",
          ],
          tools: [
            "knowledge_search (scoped collection: packing SOP, container spec, dispute policy)",
            "http_request (assemble the evidence pack; file the approved contest or acceptance)",
            "user-defined tool `fight_threshold` (SpEL): input.amount >= input.brandThreshold ? 'ASSESS' : 'FAST_CONCEDE' — a sandboxed expression owned by the finance lead, so the worth-the-fight cut-off differs per brand and changes without a deployment",
            "send_email (weekly conceded-cause summary into the shared-kitchen pattern report)",
          ],
          guardrails: [
            "Both outcomes are human-approved: filing a contest is a statement to a commercial partner and conceding is money — the agent only recommends, with its evidence attached",
            "CONTEST requires specifically cited contradicting evidence; missing evidence yields INSUFFICIENT_EVIDENCE rather than a speculative fight",
            "The worth-the-fight cut-off is the tenant's SpEL expression, not the model's judgement about what's worth arguing over",
            "Draft dispute text is factual and non-accusatory by instruction — it never blames the rider, the customer or the partner",
            "Every CONCEDE is tagged to an internal cause and fed into the shared-kitchen pattern report, so settlement losses become an operational signal instead of a silent cost",
            "Idempotent per dispute id — a resent deduction webhook cannot file twice or double-accept",
          ],
          cost:
            "One standard-tier call per deduction ABOVE the SpEL threshold, with no batch multiplier — and the threshold is the real cost control, because it keeps the model away from deductions not worth reasoning about at all. Deductions are a much smaller stream than customer complaints, which is why the tier can be higher here than on the complaint classifier. Most of the work is Tool (MCP) evidence assembly, which costs API calls rather than tokens; keep the evidence pack tight (timestamps and flags, not raw logs) and the token bill stays flat regardless of dispute complexity.",
          output:
            "The deduction webhook triggers Parallel evidence assembly from the KDS, packing store and dispatch platform → the `fight_threshold` SpEL expression routes trivial amounts to fast-concede → the agent recommends CONTEST with cited evidence, CONCEDE with a tagged cause, or INSUFFICIENT_EVIDENCE → the owner approves at the Approval node → a Tool (MCP) node files the contest with its evidence pack or accepts the deduction, and every concession's internal cause flows into the shared-kitchen pattern report.",
        },
      },
    ],
  },
];

import type { Industry } from "./recipes-data";
import { Milk, Fish } from "lucide-react";

// ---------------------------------------------------------------------------
// Dairy / poultry / cold-chain & Fisheries / seafood-export workflow recipes —
// same "inline copy, no i18n keys" precedent and honesty rules as
// recipes-data.tsx. Every recipe is expressed ONLY in VegaDūta's real building
// blocks (Trigger / Agent / Knowledge / Condition / Approval / Output /
// Tool(MCP) / Voice / Device / Loop / Code / Parallel / Supervisor /
// Sub-workflow), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — the milk-collection-unit (MCU)
// software, the procurement & payout ERP, cold-chain telemetry gateways, the
// lab/quality LIMS, the flock/farm-management system, the landing-centre and
// export-lot systems, the certification portals and the importing buyer's
// portal — are reached the real way, via an inbound Webhook trigger and/or a
// Tool (MCP) node, and each mcp[] entry names the ACTUAL call being made. Only
// real channels are used (Webhook, WhatsApp, SMS, Voice/Call, Email,
// ElevenLabs, Sarvam, Razorpay UPI). Telemetry-driven steps use the real
// Device Control node.
//
// User-defined tools, described honestly: a tenant-authored tool on this
// platform is a SpEL expression evaluated against the tool's JSON input.
// Sandboxed by construction — no method calls, no constructors, no bean or
// type references, and the grammar has no loop at all. So it is a
// data-transform / formula tool (field access, string building, arithmetic,
// ternary and elvis branching), NOT a scripting engine: it cannot call an API
// and cannot run arbitrary code. Each industry below uses at least one, and
// they only ever CLASSIFY or FORMAT — a tenant expression never clears a batch
// or a consignment as food-safe.
//
// Food-safety rule, applied throughout: an agent NEVER clears a batch, tank,
// flock or consignment as safe. It flags, summarises the evidence, and
// escalates. Every consequential action — condemning or rejecting a batch,
// recalling product, committing a shipment date, quoting a price, paying a
// farmer or a fisher — passes an Approval (human-in-the-loop) node. Agents
// propose, humans execute. Impact lines describe the mechanism (why it helps),
// never invented ROI numbers, and no agent count is inflated.
// ---------------------------------------------------------------------------

export const INDUSTRIES_DAIRYSEA: Industry[] = [
  // ============================================== DAIRY, POULTRY & COLD CHAIN
  {
    id: "dairy-coldchain",
    name: "Dairy, poultry & cold chain",
    icon: Milk,
    tagline:
      "Triage cold-chain excursions across a milk route, answer farmers about rates and payments in their own language by voice, and escalate flock and quality alerts — with every condemn, hold and payout human-approved.",
    recipes: [
      {
        id: "dairy-coldchain-excursion",
        title: "Cold-chain excursion triage across a milk route (device-triggered, 2 agents)",
        scenario:
          "Today: the bulk milk cooler at each village chilling centre has a dial thermometer and a temperature register, and the operator writes a reading into it twice a shift — usually both readings at once, at the end. The insulated tanker has a data logger whose chart nobody looks at until there is an argument. When the plant's chilling supervisor suspects a load ran warm, he phones the centre secretary, who reads him the register over the phone, and the question 'can we tip this tanker into the silo?' gets answered at the dock, from memory, by whoever is senior and awake. The 3am excursion is the one nobody hears about until the platform test at 7am.",
        triggerLabel: "Device (cold-chain telemetry — temperature excursion on a BMC or tanker)",
        steps: [
          { kind: "device", title: "An excursion is reported", detail: "A Device Control node watches the cold-chain telemetry stream from the bulk milk coolers and route tankers. When a unit breaches its threshold — or stops reporting — it fires with the unit id, the reading series and the route. The telemetry gateway has no first-party connector; it reaches the workflow via the device/Webhook path and is read back with a Tool (MCP) node." },
          { kind: "code", title: "Compute the exposure arithmetically", detail: "A Code node does the maths on the reading series — minutes above threshold, peak, reporting gaps — deterministically, before any model sees it. Numbers a decision rests on should be computed, not generated; the agent then reasons about figures it did not invent." },
          { kind: "loop", title: "Map: one pass per affected unit", detail: "A route-wide event can implicate several coolers and tankers at once. A Loop node fans a cheap triage agent across each affected unit — isolated context per unit, so a six-unit incident is handled exactly like a one-unit one." },
          { kind: "agent", title: "Triage agent links exposure to lots", detail: "Per unit, the agent takes the computed exposure plus the collection and dispatch timestamps and returns strict JSON: which collection lots sit inside the affected window, which centres and farmers they came from, and the severity band the SOP assigns. It assembles the evidence — it never declares the milk fit." },
          { kind: "agent", title: "Reduce: incident summary for the route", detail: "A reducer agent rolls the per-unit findings into one route incident: the implicated lots, the worst exposure, the farmers and centres involved, and the SOP's prescribed options (re-test / divert to a lower-grade use / hold pending lab / condemn) as OPTIONS, ranked with reasons." },
          { kind: "approval", title: "Quality manager decides disposition", detail: "An Approval node holds the disposition. Condemning, diverting or releasing a load is a food-safety decision with a person's name on it — the workflow presents the reconstructed evidence and the SOP options, and the quality manager chooses. Nothing is auto-cleared and nothing is auto-condemned." },
          { kind: "output", title: "Notify the route + write the record", detail: "On the decision, an Output node pushes the instruction to the route supervisor and tanker driver on WhatsApp (and voice/SMS where data is patchy), and a Tool (MCP) node writes the disposition and its evidence trail back to the quality/MCU system, keyed to the incident id." },
        ],
        connectors: ["Device Control (cold-chain telemetry)", "Webhook", "WhatsApp", "SMS/Twilio", "Tool (MCP → quality / MCU system)", "Approval / HITL"],
        outcome:
          "Every temperature excursion becomes a reconstructed, lot-linked incident summary with SOP options on the quality manager's screen within minutes of the breach — instead of a chart nobody reads and a phone call to the centre secretary hours later.",
        impact:
          "The mechanism is reconstruction plus routing: the temperature series, the collection timestamps and the SOP all exist already, but at 3am nobody assembles them, and the register is written from memory anyway. Doing that assembly automatically turns a silent alarm into a decision-ready case, while the fit-or-not-fit call stays with the person accountable for it.",
        build: {
          agents: 2,
          agentsNote:
            "1 triage agent (runs once per affected unit via the Loop node) + 1 reducer (runs once per incident). The Device trigger, the Code node's exposure maths, the Approval and the write-back are workflow nodes, not agents.",
          pattern: "Device-triggered map-reduce: Device → Code (deterministic maths) → Loop (map per unit) → reducer → quality-manager Approval",
          prompt:
            "(Triage) Assess ONE cold-chain unit's excursion. Inputs: {unit_id, unit_type (BMC|TANKER), computed_exposure {minutes_above_threshold, peak_temp_c, gap_minutes}, threshold_c, collection_events[{lot_id, ts, centre, farmer_ids[]}]}. The exposure figures were computed upstream — use them as given and never recompute or 'correct' them. Return strict JSON {unit_id, affected_lots[], affected_centres[], severity (LOW|MED|HIGH|UNKNOWN), sop_clause_ref, evidence_note}. If the unit stopped reporting, severity=UNKNOWN and say so — a gap is missing evidence, never a favourable reading. You do NOT decide whether the milk is fit and you do NOT output a disposition.",
          agentRoles: [
            {
              role: "Excursion triage (per affected unit)",
              model: "Economy tier — runs once per affected unit on every alert including the noisy false positives, so this choice dominates total spend. Configure a fallback model on a second provider: a 3am provider outage must degrade the alert, not drop it.",
              prompt:
                "Link ONE unit's computed exposure to the collection lots inside the affected window and band it against the SOP. Strict schema-valid JSON. Missing telemetry is UNKNOWN, never 'fine'. No disposition, no fitness judgement.",
            },
            {
              role: "Incident reducer (once per incident)",
              model: "Standard tier — cross-unit reasoning and SOP option framing; this is the one output a manager reads at 3am, with a fallback model configured for the same reason",
              prompt:
                "Aggregate the validated per-unit findings into one route incident: implicated lots and farmers, worst exposure, reporting gaps. Then list the SOP's disposition OPTIONS (re-test / divert / hold pending lab / condemn) with the reason each applies, ranked. Present options only — the quality manager decides. Cite lot ids and the computed readings; never estimate a temperature.",
            },
          ],
          mcp: [
            "Cold-chain telemetry gateway (BMC + tanker loggers) — via Device Control / inbound Webhook, then Tool (MCP): fetch the reading series for a unit between two timestamps and read its current state",
            "MCU / milk-collection system — via Tool (MCP): list collection lots and dispatch times for the route and window, resolve each lot to its centre and farmer ids",
            "Plant quality system — via Tool (MCP): write the approved disposition and its evidence trail against the incident id",
            "WhatsApp / SMS — route supervisor and tanker driver instructions",
          ],
          tools: [
            "knowledge_search (scoped Knowledge collection: cold-chain SOP — thresholds, exposure bands, disposition options)",
            "http_request (pull the reading series and collection lots; write the approved disposition)",
            "user-defined tool `excursion_severity` (SpEL, sandboxed formula — no API calls, no code): input.minutesAbove > 120 ? 'HOLD_LOT' : (input.minutesAbove > 30 ? 'ESCALATE' : 'LOG_ONLY') — classifies for routing only, and 'LOG_ONLY' is a routing label, never a food-safety clearance",
            "user-defined tool `excursion_sms_line` (SpEL): input.unitId + ' ' + input.minutesAbove + 'min above ' + input.thresholdC + 'C, peak ' + input.peakC + 'C — ' + (input.route ?: 'route unknown') — builds the one-line driver SMS in a fixed, scannable shape",
          ],
          guardrails: [
            "FOOD SAFETY: neither agent may clear milk as fit. They assemble exposure evidence, cite the SOP clause and present options — the fit/divert/condemn decision is the quality manager's at the Approval node, recorded with the evidence that justified it",
            "The `excursion_severity` tenant expression classifies for routing only. 'LOG_ONLY' means 'do not page the manager', it does NOT mean the milk is safe — no SpEL expression, and no agent, can release a lot",
            "A telemetry gap is severity UNKNOWN, never an implicit pass — silence is missing evidence and is escalated, not treated as a normal reading",
            "Exposure figures come from the Code node, not from a model: the agent reasons about minutes and peaks it did not generate, and is instructed never to recompute them",
            "Severity bands and disposition options come only from the cold-chain SOP in the scoped Knowledge collection — no invented thresholds, no improvised 'it's probably fine'",
            "Idempotent on the incident id — a re-fired alarm for the same excursion updates the existing case instead of opening a duplicate or re-paging the route",
          ],
          cost:
            "Cost is N × (economy-tier, hard-capped triage) + ONE standard-tier reduce, where N is affected units — and cold-chain alarms are noisy by nature, so the per-unit pass MUST sit on the economy tier; that single routing choice is the dominant lever. The reducer's one call per incident is where the reasoning budget belongs. Both roles carry a fallback model on a second provider, because an incident at 3am is exactly when you cannot absorb a provider outage. Run the map in Parallel batches for wall-clock speed on a route-wide event without changing per-unit cost.",
          output:
            "The Device node fires on the excursion → a Code node computes minutes-above, peak and gaps deterministically → the Loop maps a cheap triage agent over every affected cooler and tanker → the reducer builds one route incident with SOP options → the quality manager picks the disposition at the Approval node → an Output node instructs the supervisor and driver on WhatsApp/SMS using the tenant's own `excursion_sms_line` format, and a Tool (MCP) node writes the decision and its evidence trail into the quality system. The plant encodes its OWN excursion thresholds in `excursion_severity` — a formula edit in the tool editor, no developer and no redeploy.",
        },
      },
      {
        id: "dairy-farmer-rates",
        title: "Farmer rate & payment queries, answered by voice in the local language",
        scenario:
          "Today: the society secretary keeps the rate chart pinned above the collection window and answers the same three questions all morning from his personal phone — what rate did my milk fetch, why is it less than yesterday, when does the payment come. There is a WhatsApp group per village society where he forwards a photo of the rate board, and farmers who cannot read it call him instead. Most speak Kannada, Marathi or Telugu; the MCU screen is in English. When he is at the bank or on leave, nobody can answer at all, and the same query reaches the union office by phone a few hundred times a week.",
        triggerLabel: "WhatsApp / Voice call / SMS (farmer asks about rate or payment)",
        steps: [
          { kind: "trigger", title: "Farmer asks, in their language", detail: "A WhatsApp message or voice note, an inbound Voice call, or an SMS from the farmer's registered number starts the flow. The number identifies the farmer against the MCU register." },
          { kind: "voice", title: "Understand speech in an Indian language", detail: "A Voice node uses Sarvam for Indian-language speech-to-text and ElevenLabs for natural spoken replies, so a farmer who sends a Kannada or Marathi voice note gets a spoken answer back in the same language rather than an English wall of text." },
          { kind: "agent", title: "Resolve the question against the real record", detail: "An Agent node identifies the farmer from the calling number, then pulls their actual collection entries — quantity, fat, SNF, the applied rate slab — and the payment status from the MCU / procurement system via a Tool (MCP) node, and explains the rate in plain language." },
          { kind: "knowledge", title: "Ground the 'why' in the published rate chart", detail: "A scoped Knowledge collection holds the union's published fat/SNF rate chart and payment-cycle policy, so the explanation of why today's rate differs from yesterday's is quoted from the actual chart — the same chart pinned above the collection window — never reasoned into existence." },
          { kind: "condition", title: "Dispute or plain query?", detail: "A Condition node splits the traffic: a straightforward 'what was my rate / when is payment' is answered directly; anything that disputes the recorded quantity or test result, or asks for an advance or an off-cycle payment, is routed to a human." },
          { kind: "approval", title: "Any money movement is approved", detail: "An Approval node holds every payment action — releasing a held payment, an advance, a correction after a disputed test. The agent can explain a payment; it can never trigger one on its own." },
          { kind: "output", title: "Answer on their channel", detail: "An Output node replies on the channel the farmer used — spoken reply for a call or voice note, text on WhatsApp/SMS — and, where a payout has been approved and released by a human, the Razorpay UPI confirmation reference is included." },
        ],
        connectors: ["WhatsApp", "Voice/Call", "SMS/Twilio", "Sarvam (Indian languages)", "ElevenLabs (voice)", "Razorpay (UPI)", "Tool (MCP → MCU / procurement system)", "Knowledge base", "Approval / HITL"],
        outcome:
          "Farmers get an accurate, spoken answer about their own collection entries and payment status in their own language at 5am or on the secretary's day off — grounded in the same MCU record and the same rate chart he would have read out.",
        impact:
          "The mechanism is language plus retrieval: the rate data is already in the MCU system and the chart is already published, but both are locked behind an English screen and one man's phone. Voice-first Indian-language access removes the literacy and availability barrier; routing disputes to a person keeps trust intact exactly where it matters.",
        build: {
          agents: 1,
          agentsNote:
            "1 farmer-query agent. Speech in/out (Sarvam + ElevenLabs), the dispute branch, the payment Approval and the MCU lookup are workflow nodes, not extra agents.",
          prompt:
            "You are a dairy union's farmer-support assistant for {{union}}. The farmer is identified by their calling number against the MCU register — never answer with another farmer's data, and if the number is unregistered, ask for the member code instead of guessing. Answer ONLY from the collection entries and payment status returned by the Tool (MCP) lookup and the published fat/SNF rate chart in Knowledge: state the quantity, fat, SNF, applied slab and rate for the date asked, and explain a rate difference by quoting the chart. Never estimate a rate, never promise a payment date beyond the documented cycle, and never state that a payment has been made unless the record says so. If the farmer disputes a quantity or a test result, or asks for an advance or an off-cycle payment, do NOT attempt to resolve it — acknowledge in their language and hand off for human review. Reply in the language the farmer used.",
          agentRoles: [
            {
              role: "Farmer query agent (per conversation)",
              model: "Economy tier — the highest-volume step in the whole dairy stack (every farmer, most mornings); a small model with a tight prompt and a real data lookup is both sufficient and the only affordable choice. A fallback model on a second provider keeps the morning rush answerable during an outage.",
              prompt:
                "Identify the farmer from the calling number, retrieve their real collection and payment records, and explain the rate in their own language using the published chart. No estimates, no payment promises, no cross-farmer data. Disputes and advance requests are handed off, not resolved.",
            },
          ],
          mcp: [
            "MCU / milk-collection unit software — via Tool (MCP): look up a member's collection entries for a date (quantity, fat, SNF, applied slab, rate) by member code",
            "Procurement & payout ERP — via Tool (MCP): read the member's payment-cycle status and last settled cycle; the release call itself is only ever invoked after the Approval node",
            "WhatsApp / Voice / SMS — the farmer's own channel",
            "Razorpay UPI — payout reference returned to the farmer, only after a human releases the payment",
          ],
          tools: [
            "knowledge_search (scoped Knowledge collection: published fat/SNF rate chart + payment-cycle policy)",
            "http_request (look up the farmer's collection entries and payment status by member code)",
            "user-defined tool `rate_line_local` (SpEL, sandboxed formula — no API calls, no code): input.qtyL + ' L · FAT ' + input.fat + ' · SNF ' + input.snf + ' · ₹' + (input.qtyL * input.ratePerL) — formats the union's own rate line in one fixed shape for the SMS and the spoken reply",
            "user-defined tool `payment_status_label` (SpEL): input.paidOn != null ? 'PAID' : (input.cycleClosed ? 'IN_SETTLEMENT' : 'CYCLE_OPEN') — a status label from the record's own fields; it reports state, it never triggers a payment",
          ],
          guardrails: [
            "Identity is bound to the registered calling number — an unrecognised number is asked for a member code and never served speculative data; no farmer can be shown another farmer's entries",
            "Every payment action — release, advance, correction — passes the Approval node. The agent explains payments; it never initiates one, and the `payment_status_label` expression only labels what the record already says",
            "Rates and rate explanations are quoted from the published chart in the scoped Knowledge collection; the agent never computes a rate the chart does not support and never forecasts tomorrow's rate",
            "Disputes over recorded quantity or test results are escalated to a person by design, not argued with — a disputed fat reading is a relationship issue, not a chatbot's call",
            "The reply language mirrors the farmer's; a low-confidence transcription asks for a repeat rather than answering the wrong question confidently",
          ],
          cost:
            "This is the volume workflow of the whole dairy stack, so tier discipline is the entire game: one economy-tier agent call per conversation, plus the Sarvam and ElevenLabs speech legs, which are usage-billed per audio minute and are the real variable cost — keep spoken replies short and factual rather than chatty. No batch multiplier and no case for a premium model here; correctness comes from the MCU lookup and the rate chart, not from model size. A fallback model matters more than a bigger one, because the 5am rush is unforgiving.",
          output:
            "The farmer's voice note or call is transcribed by Sarvam → the agent identifies them by number, pulls the real collection and payment record via Tool (MCP) and explains the rate from the published chart → the Condition node routes disputes and advance requests to a person, and any payment passes the Approval node → an Output node answers in the farmer's own language, spoken back via ElevenLabs on voice channels, with the Razorpay UPI reference attached once a human has released a payout. The union owns the reply format: `rate_line_local` is a formula the secretary's office can edit when the slab structure changes, without a developer or a redeploy.",
        },
      },
      {
        id: "dairy-poultry-mortality",
        title: "Poultry shed mortality & environment alert → vet escalation",
        scenario:
          "Today: the shed supervisor walks the litter each morning, counts the dead birds into a bucket, and writes the number in a ruled notebook next to the feed and water figures. The environment is a wall thermometer and whether the ammonia stings your eyes at the door. The notebook is typed into a spreadsheet weekly, which is when a rise finally becomes visible as a shape — by then the flock is days past the point where the visiting vet's early call would have changed anything. Day one of a real problem and an ordinary bad day look identical in a notebook.",
        triggerLabel: "Device (shed environment sensors) + Webhook (daily flock/mortality record)",
        steps: [
          { kind: "trigger", title: "Daily flock record lands", detail: "A Webhook carries the day's mortality count, feed and water intake per shed from the farm-management system (no first-party connector), alongside a Device Control node reading the shed's temperature, humidity and ammonia sensors." },
          { kind: "parallel", title: "Read the two signals together", detail: "A Parallel node gathers the flock record and the environment series at the same time, so the analysis sees a mortality rise next to the shed conditions that preceded it — the comparison the notebook and the wall thermometer can never make." },
          { kind: "agent", title: "Detect a deviation worth a vet's attention", detail: "An Agent node compares the day's figures against the flock's own recent baseline and the breed/age norms in a scoped Knowledge collection, and returns a structured finding: deviation size, direction, which signals moved together, and whether the environment record offers a candidate explanation." },
          { kind: "condition", title: "Within normal variation?", detail: "A Condition node holds back ordinary day-to-day noise so the alert channel stays worth reading, and promotes anything crossing the documented deviation threshold — or any sustained multi-day drift — to escalation." },
          { kind: "agent", title: "Brief the vet", detail: "A second agent writes a short veterinary briefing: shed, flock age, the trend series, feed/water divergence, environment readings, and the specific observations a stockman should collect on the ground. It describes; it does not diagnose and it never names a treatment." },
          { kind: "approval", title: "Vet decides the response", detail: "An Approval node routes the briefing to the supervising veterinarian. Any intervention — a visit, sampling, a movement restriction, a culling decision — is the vet's call, made on the evidence, not the workflow's." },
          { kind: "output", title: "Reach the farm in its own language", detail: "An Output node sends the check-list to the farm supervisor on WhatsApp with a Sarvam-generated local-language version and, on request, an ElevenLabs voice note — because the man walking the litter is not reading an English PDF. A Tool (MCP) node logs the alert and its outcome against the flock record." },
        ],
        connectors: ["Device Control (shed sensors)", "Webhook", "WhatsApp", "Voice/Call", "Sarvam (Indian languages)", "ElevenLabs (voice)", "Tool (MCP → farm-management system)", "Approval / HITL"],
        outcome:
          "Mortality and intake deviations are compared against the flock's own baseline on the day they appear — not in next week's spreadsheet — and reach the vet as a structured briefing with the shed conditions already attached.",
        impact:
          "The mechanism is early, consistent comparison: the daily numbers are already collected but only compared properly once a week, and never against the environment record at all. Running that comparison every day, the same way, moves a veterinary decision earlier — while the diagnosis and the intervention stay entirely with the vet.",
        build: {
          agents: 2,
          agentsNote:
            "1 deviation-detection agent (per shed, per day) + 1 briefing agent (only on escalation). The Parallel gather, the noise gate, the vet Approval and the log write are workflow nodes.",
          pattern: "Parallel signal gather → detection → threshold gate → briefing → veterinary Approval",
          prompt:
            "(Detection) Compare ONE shed-day against its own recent baseline and the breed/age norms in Knowledge. Inputs: {shed_id, flock_age_days, mortality_today, mortality_baseline[], feed_kg, water_l, intake_baseline[], env_series[{ts, temp_c, humidity_pct, ammonia_ppm}]}. Return strict JSON {deviation_pct, direction, signals_moved_together[], env_candidate_factor|null, sustained_days, band (NORMAL|WATCH|ESCALATE), evidence_note}. Use only the supplied series — never fill a missing day with an assumed value; a gap makes the band WATCH with the gap named. You do NOT diagnose disease, name a pathogen, or suggest any treatment or medication. Detection and description only.",
          agentRoles: [
            {
              role: "Deviation detection (per shed, per day)",
              model: "Economy tier — one call per shed per day across the whole farm, on mostly-normal data; the volume lives here, so the cheap tier does too, with a fallback model so a provider outage does not silently skip a day",
              prompt:
                "Compare one shed-day to its own baseline and the breed/age norms, return a strict-JSON deviation finding with a band. Gaps are WATCH, never NORMAL. No diagnosis, no pathogen names, no treatment.",
            },
            {
              role: "Veterinary briefing (only when escalated)",
              model: "Standard tier — this is the one output a vet reads, and it must be clear, ordered and complete; rare enough that the better model costs almost nothing in aggregate",
              prompt:
                "Write a concise veterinary briefing from the escalated finding: shed, flock age, trend series, feed/water divergence, environment readings, and the specific on-the-ground observations a stockman should collect. Describe the evidence and list what to check. Do NOT offer a differential diagnosis, name a medication, or recommend culling — the vet decides.",
            },
          ],
          mcp: [
            "Farm / flock-management system — via Tool (MCP): pull the shed's daily mortality, feed and water series for the baseline window (inbound day record arrives by Webhook)",
            "Shed environment sensor gateway — via Device Control, then Tool (MCP): read the temperature, humidity and ammonia series for the same window",
            "Farm/flock-management system — via Tool (MCP): log the alert, its band and the vet's recorded outcome against the flock",
            "WhatsApp / Voice — farm supervisor and stockman check-list, in the local language",
          ],
          tools: [
            "knowledge_search (scoped Knowledge collection: breed/age mortality and intake norms, deviation thresholds, biosecurity SOP)",
            "http_request (pull the flock record; log the alert and its outcome)",
            "user-defined tool `mortality_per_mille` (SpEL, sandboxed formula — no API calls, no code): (input.deathsToday * 1000.0) / input.birdsAlive — one arithmetic definition of the farm's own rate, used identically by every shed so the numbers are comparable",
            "user-defined tool `shed_alert_band` (SpEL): input.perMille > input.escalatePerMille ? 'ESCALATE' : (input.perMille > input.watchPerMille ? 'WATCH' : 'NORMAL') — the farm's OWN thresholds, edited in the tool editor when the breed or the season changes; it bands for routing and never diagnoses anything",
          ],
          guardrails: [
            "ANIMAL HEALTH: no agent diagnoses. Neither agent may name a disease, suggest a medication, or recommend culling — they quantify a deviation and describe the evidence; the supervising veterinarian decides at the Approval node",
            "The `shed_alert_band` tenant expression is a routing classifier only. 'NORMAL' means 'do not page the vet today', never 'the flock is healthy'",
            "Missing or partial daily records produce a WATCH with the gap named, never a NORMAL — an unrecorded day is missing evidence, not a good day",
            "Deviation bands come from the documented breed/age norms in the scoped Knowledge collection, not from the model's own sense of what looks high",
            "The noise gate is deliberate: only documented-threshold deviations and sustained drifts escalate, so the vet's alert channel keeps its signal and stays read",
            "Alerts are idempotent per shed per day — a resent flock record updates the day's finding rather than re-paging the vet",
          ],
          cost:
            "The detection pass runs once per shed per day forever, so it belongs on the economy tier with a hard cap — on a normal day that is the entire cost of the workflow, and the per-mille arithmetic is done by a SpEL expression rather than by tokens. The standard-tier briefing agent only ever runs on the small fraction of shed-days that clear the threshold gate: rare calls, high-value output, read by a veterinarian. Both roles carry a fallback model, since a skipped day is a lost signal you cannot recover later.",
          output:
            "The Parallel node gathers the flock record and the shed's environment series → the tenant's `mortality_per_mille` and `shed_alert_band` expressions turn raw counts into the farm's own comparable rate and band → the economy-tier detector reasons about the deviation against baseline → the Condition node's noise gate suppresses ordinary variation → on escalation the briefing agent writes a structured case → the veterinarian decides at the Approval node → an Output node sends the on-the-ground check-list to the farm supervisor on WhatsApp in the local language (Sarvam, with an ElevenLabs voice note on request) and a Tool (MCP) node logs the alert and its outcome.",
        },
      },
      {
        id: "dairy-batch-hold",
        title: "Lab result → batch hold, with the release decision left to a human",
        scenario:
          "Today: the platform and lab results come back on a printed sheet hours after the milk has moved on — adulteration screen, antibiotic residue, SPC. The quality chemist walks the sheet to the production manager, and the two of them stand at a screen working backwards: which tanker, which silo, which processing run, which pack codes, which of those went out on last night's dispatch. It takes three systems, a dispatch register and several phone calls, done under time pressure, and the branch somebody forgets is the branch that reaches a distributor.",
        triggerLabel: "Webhook (lab/LIMS result posted against a batch)",
        steps: [
          { kind: "trigger", title: "A result lands against a batch", detail: "A Webhook from the lab/quality LIMS (no first-party connector) delivers the result — test type, batch or tanker id, value, and the specification limit it was measured against." },
          { kind: "condition", title: "Within specification?", detail: "A Condition node compares the value to the documented spec. In-spec results are logged and the flow ends; anything out of spec, borderline, or missing a value proceeds to the trace — a missing result is treated as out of spec, not as a pass." },
          { kind: "supervisor", title: "Supervisor coordinates the trace across three systems", detail: "The genealogy lives in the MCU, processing and dispatch systems, each with its own shape. A Supervisor node delegates to a retrieval worker per system and holds the partial picture together, so no branch is dropped because one system answered awkwardly." },
          { kind: "agent", title: "Retrieval worker walks its own system", detail: "Each worker run resolves one hop — collection lots feeding the batch, the silo and processing runs it joined, the finished pack codes and the consignments already dispatched — and reports every link it found plus every point where the chain broke." },
          { kind: "agent", title: "Assemble the hold case", detail: "A case-writer agent turns the assembled genealogy into the document a quality head decides on: what was found against which limit, the traced scope upstream and downstream, which consignments have already left the plant, the affected farmers and centres, and the SOP's options — hold, re-test, divert, recall — with the condition attached to each." },
          { kind: "approval", title: "Quality head decides hold, recall or release", detail: "An Approval node puts the decision in front of the quality head. Placing a hold, ordering a recall, and — critically — releasing a lot as fit are all human decisions. The workflow will never mark a batch safe." },
          { kind: "output", title: "Execute the approved decision only", detail: "On approval, a Tool (MCP) node writes the hold or release status to the quality and dispatch systems, an Output node notifies plant, dispatch and (where the decision requires it) the affected distributors by WhatsApp and Email, and the full evidence trail is stamped to the batch id." },
        ],
        connectors: ["Webhook", "Tool (MCP → lab/LIMS, MCU, processing & dispatch systems)", "WhatsApp", "Email", "Approval / HITL", "Knowledge base"],
        outcome:
          "An out-of-spec result becomes a fully traced hold case — upstream lots, downstream packs, consignments already on the road — in front of the quality head in minutes, instead of two people reconstructing it across three screens and a dispatch register.",
        impact:
          "The mechanism is traversal speed and completeness: the batch genealogy exists across the MCU, processing and dispatch systems, but walking it by hand is slow and misses branches under exactly the pressure that makes missing one likely. Automating the traversal widens the evidence; hard-gating the release keeps the food-safety call where regulation and common sense both put it.",
        build: {
          agents: 3,
          agentsNote:
            "1 supervisor + 1 retrieval worker (invoked once per source system by the Supervisor node) + 1 case-writer. The spec gate, the quality-head Approval and the status write-back are workflow nodes.",
          pattern: "Spec gate → Supervisor-coordinated multi-system trace → case assembly → quality-head Approval → gated execution",
          prompt:
            "(Retrieval worker) Resolve ONE hop of a batch genealogy in ONE source system. Inputs: {batch_id, system (MCU|PROCESSING|DISPATCH), known_ids[]}. Return strict JSON {system, links[{from_id, to_id, kind, timestamp, detail}], trace_gaps[{id, reason}]}. Follow only links the system actually returns — never infer that a pack 'probably' came from this batch, never omit a branch because it looks unlikely, and never merge two ids because they look similar. Anything the system cannot resolve goes to trace_gaps with the reason. You do NOT judge whether the product is safe and you do NOT propose a disposition.",
          agentRoles: [
            {
              role: "Trace supervisor (once per flagged batch)",
              model: "Standard tier — decides which system to query next and when the picture is complete; a missed branch is the entire failure mode of this workflow",
              prompt:
                "Coordinate the trace: delegate one retrieval task per source system, feed resolved ids forward as the next hop's input, and keep going until no unresolved id remains or the system reports a gap. Never fill a gap yourself and never declare the trace complete while trace_gaps is non-empty — report it as incomplete instead.",
            },
            {
              role: "Retrieval worker (once per source system)",
              model: "Standard tier — structured multi-hop retrieval where a quietly dropped link is invisible until a recall; deliberately NOT economised",
              prompt:
                "Resolve one hop in one system into strict JSON links plus an explicit trace_gaps list. No inference, no id merging, no omission, no safety judgement.",
            },
            {
              role: "Hold-case writer (once per flagged batch)",
              model: "Premium tier — this document is read under time pressure by the person who signs the decision, and it is one call per incident; clarity and completeness are worth the tier here",
              prompt:
                "Write the hold case: the finding against its limit, the traced upstream and downstream scope, consignments already dispatched, affected farmers and centres, trace gaps, and the SOP's options (hold / re-test / divert / recall) with the condition attached to each. Present options and evidence. Never state or imply that the lot is safe, and never pick the option yourself.",
            },
          ],
          mcp: [
            "Lab / quality LIMS — inbound via Webhook, then Tool (MCP): fetch the full result set and the specification limit for the batch",
            "MCU / milk-collection system — via Tool (MCP): resolve the collection lots, centres and farmer ids feeding the flagged batch",
            "Processing / plant system — via Tool (MCP): resolve the silo and processing runs the batch joined, and the finished pack codes produced",
            "Dispatch / distribution system — via Tool (MCP): list the consignments carrying those pack codes with their dispatch time and destination, and write the approved hold or release status",
            "WhatsApp / Email — plant, dispatch and distributor notification per the SOP's notification matrix",
          ],
          tools: [
            "knowledge_search (scoped Knowledge collection: specification limits, batch-hold and recall SOP, notification matrix)",
            "http_request (walk the genealogy across the three systems; write the approved hold or release status)",
            "send_email (dispatch the approved notification to distributors on the SOP's matrix)",
            "user-defined tool `spec_band` (SpEL, sandboxed formula — no API calls, no code): input.value == null ? 'NO_RESULT' : (input.value > input.limit ? 'OVER' : (input.value > input.limit * 0.9 ? 'NEAR_LIMIT' : 'WITHIN_LIMIT')) — the plant's own banding for ROUTING; 'WITHIN_LIMIT' opens no case, it does not release a lot",
          ],
          guardrails: [
            "FOOD SAFETY, absolute: no agent and no tenant expression may release a batch or describe it as safe. Hold, recall and release are human decisions taken at the Approval node, and the evidence that justified each is stamped to the batch id",
            "`spec_band` classifies a number against a limit for routing only — 'WITHIN_LIMIT' means the workflow does not open a hold case; the release of a lot is a separate, human, approved act",
            "A missing, void or borderline result is handled as out of specification and proceeds to the trace — the absence of a result is never a pass",
            "The trace reports only links the source systems actually return; every broken or ambiguous link is surfaced in trace_gaps, and the supervisor may not declare a trace complete while gaps remain — an incomplete trace is stated as incomplete",
            "Spec limits, options and the notification matrix come from the documented SOP in the scoped Knowledge collection; the agent never sets or relaxes a limit",
            "No status is written and no distributor is notified before approval — the write-back path is unreachable without the human decision, and it is idempotent per batch id",
          ],
          cost:
            "This workflow runs rarely — only on out-of-spec, borderline or missing results — and each run carries real consequence, so the retrieval roles sit on the standard tier with a generous tool-call budget and the single case-writing call is allowed the premium tier. The economy tier is deliberately absent here: the cost of a missed downstream branch dwarfs any token bill. Volume control comes from the `spec_band` routing gate in front, not from shrinking the model. Fallback models are configured on all three roles, because a provider outage during a hold decision is not an acceptable delay.",
          output:
            "The Condition node gates on specification (missing counts as out) → the Supervisor node coordinates retrieval workers across the MCU, processing and dispatch systems until no id is unresolved → the premium-tier case-writer assembles the evidence, the trace gaps and the SOP's options → the quality head decides hold, re-test, divert, recall or release at the Approval node → only then does a Tool (MCP) node write the status and send_email notify distributors per the SOP matrix, with the full trail stamped to the batch.",
        },
      },
    ],
  },

  // ================================================ FISHERIES & SEAFOOD EXPORT
  {
    id: "seafood-export",
    name: "Fisheries & seafood export",
    icon: Fish,
    tagline:
      "Turn landings into traceable export lots, catch a document gap or a rejection risk before the container closes, and talk to fishers by voice in their own language — with every price, shipment date and payout human-approved.",
    recipes: [
      {
        id: "sea-landing-trace",
        title: "Catch landing → export-lot traceability record",
        scenario:
          "Today: at the landing centre the weighing clerk calls out species and grade, someone writes the weight on a carbon-copy paper slip, and the agent sends a photo of the slip to the plant's WhatsApp group. Vessel registration, fishing area and gear are 'known' because everyone knows the boat. Days later, when an export lot has to declare catch origin, vessel, area, gear and landing date, the traceability officer reassembles it from a folder of slips and the WhatsApp scroll — and the fields nobody wrote down are gone, because the boat has sailed again.",
        triggerLabel: "WhatsApp / Webhook (landing declared at the landing centre or peeling shed)",
        steps: [
          { kind: "trigger", title: "A landing is declared", detail: "A WhatsApp message, voice note or slip photo from the harbour agent, or a Webhook from the landing-and-procurement system (no first-party connector), starts the record: vessel, trip, species, grades, weights, ice status." },
          { kind: "agent", title: "Structure the landing", detail: "An Agent node turns the free-text, voice note or photographed slip into structured fields — vessel and registration, trip dates, fishing area, gear, species with grade and weight, landing time — and flags every field it could not read rather than filling it in." },
          { kind: "condition", title: "Traceability fields complete?", detail: "A Condition node checks the fields an export lot will eventually need. An incomplete landing branches to a single, specific question back to the harbour agent — asked now, at the quay, while the crew is still standing there and the answer is still knowable." },
          { kind: "loop", title: "Roll landings into lots", detail: "A Loop node walks the day's landings and assigns each to the export lot it feeds, building the lot's catch composition as a real many-to-one record instead of a summary typed from a folder a week later." },
          { kind: "approval", title: "Lot composition confirmed", detail: "An Approval node holds the lot's composition for the plant's traceability officer — this record is what the export certificate will rest on, so a human confirms it rather than inheriting whatever was parsed from a slip photo." },
          { kind: "output", title: "Write the traceability record", detail: "A Tool (MCP) node writes the confirmed landing-to-lot record into the plant's traceability system, keyed to the lot id, and an Output node confirms back to the harbour agent on WhatsApp." },
        ],
        connectors: ["WhatsApp", "Webhook", "Sarvam (Indian languages)", "Tool (MCP → landing / traceability system)", "Approval / HITL"],
        outcome:
          "Each landing is captured as a structured, gap-checked record at the quay and rolled into its export lot's catch composition, confirmed by the traceability officer — instead of being reconstructed from a folder of carbon slips after the boat has sailed.",
        impact:
          "The mechanism is capture-at-source with a completeness gate: the traceability fields are hardest to recover days later and trivially easy to obtain in the moment. Asking the one missing question while the vessel is still alongside is the whole value; the human confirmation keeps the certificate's foundation trustworthy.",
        build: {
          agents: 1,
          agentsNote:
            "1 landing-structuring agent (runs per landing, including inside the Loop that rolls landings into lots). The completeness gate, the officer's Approval and the traceability write are workflow nodes.",
          prompt:
            "You are a landing-record assistant for {{plant}}. From a free-text message, voice note or photographed landing slip, extract {vessel_name, vessel_reg, trip_start, landing_datetime, fishing_area, gear_type, species[{name, grade, weight_kg}], ice_status, harbour_agent}. Extract ONLY what is present — set any field you cannot read to null and list it in missing[]; never infer a fishing area from the harbour, never infer gear from the vessel name, never round a weight to a tidier number, and never carry a value over from a previous landing by the same boat. Messages may be in a local Indian language; understand them as sent. Return {fields, missing[], confidence}. You do not assign a lot id and you do not confirm a lot's composition — the workflow and the traceability officer do that.",
          agentRoles: [
            {
              role: "Landing structurer (per landing)",
              model: "Economy tier — short slips, photos and voice notes at high daily volume across every landing; accuracy comes from the strict schema and the missing[] discipline, not from model size. A fallback model keeps the quay working when a provider is down and the boat will not wait.",
              prompt:
                "Extract landing fields into strict JSON with explicit nulls and a missing[] list. Local-language input is normal. No inference of area or gear, no rounded weights, no carry-over from a previous landing.",
            },
          ],
          mcp: [
            "Landing / procurement system — via Tool (MCP): validate the vessel registration against the register and resolve the boat's owner and home port",
            "Plant traceability system — via Tool (MCP): create or update the landing record and append it to the export lot's catch composition, keyed to the landing id",
            "WhatsApp — the harbour agent's channel for the slip, the one clarification question and the confirmation",
          ],
          tools: [
            "knowledge_search (scoped Knowledge collection: mandatory traceability fields, species and grade nomenclature, area and gear codes)",
            "http_request (validate the vessel registration; write the landing-to-lot record)",
            "user-defined tool `landing_ref` (SpEL, sandboxed formula — no API calls, no code): input.harbourCode + '/' + input.landingDate + '/' + (input.vesselReg ?: 'UNREG') — the plant's own landing reference format, built the same way every time; the elvis keeps an unregistered boat visible as UNREG instead of silently blank",
            "user-defined tool `trace_completeness` (SpEL): input.missingCount == 0 ? 'COMPLETE' : (input.missingCount <= input.softGapAllowance ? 'CLARIFY_AT_QUAY' : 'BLOCK_LOT_ASSIGNMENT') — the plant encodes how strict it wants to be, and changes it when a market tightens, without a developer or a redeploy",
          ],
          guardrails: [
            "Unreadable fields are null and listed in missing[] — the agent never infers a fishing area, gear type or weight, because a guessed traceability field is worse than a blank one and will fail an audit later",
            "The completeness gate asks the harbour agent one specific question at the quay rather than accepting a partial record that becomes unrecoverable once the boat sails",
            "`trace_completeness` is a routing classifier over the plant's own tolerance; 'COMPLETE' means the fields are present, never that the catch is compliant — the export lot's composition is still confirmed by the traceability officer at the Approval node",
            "Vessel registrations are validated against the register via lookup, not accepted as typed or as remembered",
            "The traceability write is idempotent per landing id, so a resent slip photo updates the record instead of duplicating a landing into the lot",
          ],
          cost:
            "One economy-tier extraction per landing, and landings are the highest-count event in the whole chain — precisely where the cheap tier belongs, with a hard cap per message. The Sarvam transcription leg for quay voice notes is usage-billed per audio minute and is the main variable cost on a busy landing day. No premium tier is justified anywhere here; correctness comes from the schema, the register lookup and the human confirmation.",
          output:
            "The landing arrives on WhatsApp or Webhook → the economy-tier agent structures it with explicit gaps → `trace_completeness` applies the plant's own strictness and the Condition node asks the one missing question at the quay → the Loop rolls the day's landings into their export lots under the tenant's `landing_ref` format → the traceability officer confirms the lot composition at the Approval node → a Tool (MCP) node writes the record keyed to the lot id and an Output node confirms to the harbour agent.",
        },
      },
      {
        id: "sea-cert-completeness",
        title: "Pre-shipment document completeness check before the container is committed",
        scenario:
          "Today: the export documentation clerk assembles the pack the night before the container closes — health certificate, catch documentation, packing list, invoice, the lab reports for the parameters that market tests, and the buyer's own annexes — printed, in a folder, checked by reading them one after another. The lot ids, net weights and production dates have to agree across all of them, and the pair nobody thought to compare is the pair that stops the container. Everyone finds out at 11pm, or at the other end of the world.",
        triggerLabel: "Webhook (consignment reaches pre-shipment stage) / Email (documents received)",
        steps: [
          { kind: "trigger", title: "Consignment enters pre-shipment", detail: "A Webhook from the export/ERP system (no first-party connector) or an Email carrying the document pack starts the check, with the destination market and the buyer's contract terms attached." },
          { kind: "knowledge", title: "Load the destination's requirement set", detail: "A scoped Knowledge collection holds the per-destination documentary requirement list and the buyer's contractual annexes, so the check runs against the market this container is actually going to, not the generic list in the clerk's head." },
          { kind: "parallel", title: "Read every document at once", detail: "A Parallel node fans the document pack out so each document is read concurrently — the pack is checked in the time the slowest single document takes, not in the time it takes to read them in sequence." },
          { kind: "agent", title: "Extract the key fields per document", detail: "A per-document agent pulls the fields that must reconcile — lot ids, species, net and gross weights, production and expiry dates, certificate numbers, establishment approval number — and flags anything unreadable instead of assuming it." },
          { kind: "agent", title: "Reconcile across the pack", detail: "A reconciliation agent cross-checks every extracted field against every other document and against the destination's requirement set, and returns a findings list: missing documents, absent fields, and — the expensive one — values that disagree between two documents." },
          { kind: "approval", title: "Export desk clears the pack", detail: "An Approval node puts the findings in front of the export documentation desk. Committing a shipment date, releasing the pack to the buyer or to a certifying authority is a human action; the agent produces the checklist, not the clearance." },
          { kind: "output", title: "Route the gaps to whoever can close them", detail: "An Output node sends each gap to its owner — plant QA, the lab, the traceability officer — on Email or WhatsApp, and files the findings against the consignment via a Tool (MCP) node." },
        ],
        connectors: ["Webhook", "Email", "WhatsApp", "Knowledge base", "Tool (MCP → export/ERP + document store)", "Approval / HITL"],
        outcome:
          "Every consignment gets a full cross-document reconciliation against its destination's requirement set before the shipment date is committed — every field against every other document, not the pairs one tired clerk thought to check at 11pm.",
        impact:
          "The mechanism is exhaustive cross-checking: a person reads a document pack sequentially and compares the pairs they think to compare, while the failure is almost always in a pair nobody thought of. Comparing every field against every document, every time, is exactly the work that is tedious for a person and cheap to automate — and the clearance stays human because a committed shipment date is a commercial promise.",
        build: {
          agents: 2,
          agentsNote:
            "1 extraction agent (runs once per document, in Parallel) + 1 reconciliation agent (once per consignment). The requirement set, the desk's Approval and the filing are workflow nodes.",
          pattern: "Parallel per-document extraction → single cross-document reconciliation → export-desk Approval",
          prompt:
            "(Extraction) Read ONE export document. Return strict JSON {doc_type, doc_number, issue_date, lot_ids[], species[], net_weight_kg, gross_weight_kg, production_date, expiry_date, establishment_approval_no, signatory, unreadable_fields[]}. Extract only what the document states — never carry a value in from another document in the pack, never normalise a weight or a date into what it 'should' be, and never fill a field from the consignment header. Anything you cannot read with confidence goes to unreadable_fields[] as null. You do not judge whether the pack is complete and you do not clear anything.",
          agentRoles: [
            {
              role: "Document extractor (per document, in Parallel)",
              model: "Economy tier — bounded per-document extraction into a fixed schema, run several times per consignment; the multiplier lives here, and a fallback model prevents a provider blip from stalling a pack the night before closing",
              prompt:
                "Extract the reconcilable fields from ONE document into strict JSON. No cross-document carry-over, no normalisation, unreadable fields explicitly null. No completeness judgement.",
            },
            {
              role: "Pack reconciler (once per consignment)",
              model: "Premium tier — the genuinely hard step: every field compared against every other document and against the destination's requirement set, where a missed mismatch is the entire failure mode. One call per consignment, so the tier costs little in aggregate.",
              prompt:
                "Cross-check all extracted documents against each other and against the destination requirement set in Knowledge. Return findings as {missing_documents[], missing_fields[], mismatches[{field, doc_a, value_a, doc_b, value_b}], unreadable[]} with a severity per finding. Report only differences you can point at with both values. Do NOT declare the pack cleared, and do NOT suggest amending a document to make it match — flag the disagreement and let a human resolve it.",
            },
          ],
          mcp: [
            "Export / ERP system — via Tool (MCP): read the consignment header, destination market, buyer contract terms and shipment stage; file the findings back against the consignment",
            "Document store — via Tool (MCP): fetch the document pack (health certificate, catch documentation, packing list, invoice, lab reports, buyer annexes) for the consignment",
            "Certification portal — via Tool (MCP): check whether the certificate reference already exists against this consignment before the desk submits anything",
            "Email / WhatsApp — document intake, and routing each gap to plant QA, the lab or the traceability officer",
          ],
          tools: [
            "knowledge_search (scoped Knowledge collection: per-destination documentary requirement list + buyer contractual annexes)",
            "http_request (fetch the document pack; file the findings against the consignment)",
            "send_email (route each gap to its owner with the document and field named)",
            "user-defined tool `weight_agreement` (SpEL, sandboxed formula — no API calls, no code): (input.netA - input.netB > input.toleranceKg || input.netB - input.netA > input.toleranceKg) ? 'WEIGHT_MISMATCH' : 'WEIGHT_OK' — the exporter's own tolerance, applied identically to every document pair (no abs() needed; the grammar has no method calls)",
            "user-defined tool `gap_owner_line` (SpEL): input.docType + ' · ' + input.field + ' → ' + (input.owner ?: 'EXPORT_DESK') — one fixed line per gap so the QA, lab and traceability inboxes all receive the same recognisable shape",
          ],
          guardrails: [
            "The agents produce a findings list, never a clearance — releasing the pack, committing the shipment date and submitting to a certifying authority all pass the export desk's Approval node",
            "Extraction never carries a value between documents: a field absent from a certificate stays absent, because inheriting it from the invoice is precisely how a mismatch gets hidden instead of found",
            "A mismatch is reported with both sides quoted and both sources named; the agent never proposes editing a document to make the pack agree",
            "`weight_agreement` compares two numbers against the exporter's own tolerance — it flags a discrepancy for a human, and 'WEIGHT_OK' never constitutes clearance of the pack or of the product",
            "Requirements are read per destination market and per buyer contract from the scoped Knowledge collection — never a generic list, and the agent never asserts a requirement the set does not contain",
            "Unreadable fields are surfaced as unreadable, so a scan-quality problem shows up as a finding instead of silently passing the reconciliation",
          ],
          cost:
            "Cost is D × (economy-tier extraction, one call per document) + ONE premium-tier reconciliation per consignment. That split is deliberate: extraction is bounded, repetitive and schema-checked, so the cheap tier handles it; reconciliation is the hard reasoning step and gets the best model exactly once, which is affordable precisely because it runs once. The numeric tolerance comparison is done by a SpEL expression rather than by tokens. Running the extractions in Parallel cuts wall-clock time to the slowest document without changing per-document cost.",
          output:
            "The Parallel node fans economy-tier extraction across every document in the pack → the exporter's `weight_agreement` tolerance is applied to every document pair → the premium-tier reconciler cross-checks all fields against each other and against the destination's requirement set → the export desk reviews and clears at the Approval node → an Output node routes each gap in the tenant's own `gap_owner_line` format to plant QA, the lab or the traceability officer, and a Tool (MCP) node files the findings against the consignment.",
        },
      },
      {
        id: "sea-rejection-risk",
        title: "Border-rejection risk review before a container ships",
        scenario:
          "Today: the export head decides to ship on the strength of 'the lab reports came back fine' — which means fine against the domestic limit on the report header, because the destination's tighter limit lives in a circular saved on someone's desktop. The plant's own rejection history is a folder of old emails. Whether that market is currently testing this species harder than usual is something you hear about from another exporter at the association meeting. All the signals exist; none of them are ever in front of one person at the same time.",
        triggerLabel: "Webhook (consignment ready to ship, pre-dispatch review)",
        steps: [
          { kind: "trigger", title: "Pre-dispatch review opens", detail: "A Webhook from the export system fires when a consignment reaches pre-dispatch, carrying destination, species, product form, lot ids and the linked lab reports." },
          { kind: "knowledge", title: "Destination rules + recent alert history", detail: "A scoped Knowledge collection holds the destination market's tested parameters and limits, the plant's own rejection and alert history, and the heightened-scrutiny notices the export desk maintains — the circular, the email folder and the association gossip, in one place the workflow can actually read." },
          { kind: "agent", title: "Compare results against the destination's limits", detail: "An Agent node checks each lab parameter against the destination's specific limits — not the domestic ones on the report header — and highlights any result that is within domestic spec but close to or over the destination's tighter limit." },
          { kind: "agent", title: "Assemble the risk picture", detail: "A second agent adds the non-lab factors: the traceability record's completeness, the species and product form's recent alert pattern for that destination, and any parameter that market is currently testing more intensively — and writes a structured risk review with each factor evidenced." },
          { kind: "condition", title: "Any elevated factor?", detail: "A Condition node separates a clean review, which goes to the desk as a short confirmation, from one with an elevated factor, which is escalated with the full evidence for a considered decision." },
          { kind: "approval", title: "Export head decides to ship, hold or re-test", detail: "An Approval node holds the decision. Shipping, holding, re-testing or re-routing a consignment to a different market is a commercial and food-safety judgement the export head makes; the workflow supplies the evidence and never clears a consignment itself." },
          { kind: "output", title: "Record the decision with its reasoning", detail: "An Output node notifies the plant and the buyer-facing team on Email or WhatsApp, and a Tool (MCP) node writes the review, the decision and its reasoning against the consignment — which is also what makes the next review's history worth reading." },
        ],
        connectors: ["Webhook", "Email", "WhatsApp", "Knowledge base", "Tool (MCP → export/ERP + LIMS)", "Approval / HITL"],
        outcome:
          "Every consignment gets a written, evidenced risk review against its actual destination's limits and current scrutiny pattern before it ships — the circular, the rejection history and the traceability state read together, once, by the person deciding.",
        impact:
          "The mechanism is assembling scattered signals into one view at the last moment where acting is still cheap: the lab results, the destination's limits, the alert history and the traceability state all exist but live in a report header, a desktop circular, an email folder and another system. Nothing here predicts a rejection — it surfaces the evidence a person would have wanted, before the container is at sea.",
        build: {
          agents: 2,
          agentsNote:
            "1 parameter-comparison agent + 1 risk-review assembler, both once per consignment. The destination rule set, the escalation branch and the export head's Approval are workflow nodes.",
          pattern: "Destination-specific parameter check → risk assembly → escalation gate → export-head Approval",
          prompt:
            "(Parameter check) Compare ONE consignment's lab results against the DESTINATION market's limits, not domestic limits. Inputs: {destination, species, product_form, results[{parameter, value, unit, method, tested_at}], destination_limits[]}. Return strict JSON {per_parameter[{parameter, value, destination_limit, margin_pct, status (WITHIN|NEAR_LIMIT|OVER|NOT_TESTED)}], untested_required_parameters[], stale_results[]}. A parameter the destination requires but the consignment has not tested is NOT_TESTED and must be listed — never treat an untested parameter as passing. Never convert units unless the conversion is exact, and state it when you do. You do not decide whether to ship.",
          agentRoles: [
            {
              role: "Destination parameter check (per consignment)",
              model: "Standard tier — unit-aware comparison against the correct market's limits, where the classic error is quietly checking the domestic limit printed on the report instead",
              prompt:
                "Compare each result against the destination's limit, band it WITHIN / NEAR_LIMIT / OVER / NOT_TESTED, and list required-but-untested and stale results explicitly. Untested is never a pass. No shipping decision.",
            },
            {
              role: "Risk review assembler (per consignment)",
              model: "Premium tier — synthesises lab findings, traceability completeness and the destination's current scrutiny pattern into the document the export head decides on; one call per consignment, and the decision it informs outweighs any token bill",
              prompt:
                "Write a structured risk review: each elevated factor with its evidence and source (lab result, traceability gap, recent alert pattern, current heightened-scrutiny notice), plus the factors checked and found clean. Present factors and their evidence — do not compute a false-precision risk score, do not predict an outcome, and do not recommend shipping. The export head decides.",
            },
          ],
          mcp: [
            "Export / ERP system — via Tool (MCP): read the consignment's destination, species, product form and lot ids at pre-dispatch, and write the review, decision and reasoning back against it",
            "Lab / LIMS — via Tool (MCP): fetch every parameter result for the consignment's lots with method and test date, so staleness is visible",
            "Plant traceability system — via Tool (MCP): read the completeness state of the lots in the consignment (open gaps from the landing workflow)",
            "Email / WhatsApp — plant and buyer-facing team notification of the recorded decision",
          ],
          tools: [
            "knowledge_search (scoped Knowledge collection: destination limits and tested parameters, plant rejection/alert history, current heightened-scrutiny notices)",
            "web_search (optional, desk-curated: check whether the destination authority has published a new notice since the Knowledge collection was last updated — a prompt to the desk to refresh it, never a substitute for the maintained rule set)",
            "http_request (pull lab results and traceability state; write the review and the decision)",
            "user-defined tool `dest_param_band` (SpEL, sandboxed formula — no API calls, no code): input.value == null ? 'NOT_TESTED' : (input.value > input.destLimit ? 'OVER' : (input.value > input.destLimit * input.nearFactor ? 'NEAR_LIMIT' : 'WITHIN')) — the exporter sets its OWN nearFactor per market and tightens it when a market gets stricter, with no developer and no redeploy; it bands a number, it never clears a consignment",
          ],
          guardrails: [
            "FOOD SAFETY and commercial: no agent and no tenant expression clears a consignment to ship. The review presents evidenced factors; ship, hold, re-test and re-route are decided by the export head at the Approval node and recorded with their reasoning",
            "A required parameter that was not tested, or whose result is stale, is reported as such and never treated as a pass — the missing test is the finding, and `dest_param_band` returns NOT_TESTED for a null rather than a comfortable default",
            "Comparisons run against the destination market's limits held in Knowledge, never the domestic limit on the report header; the agent never asserts a limit the requirement set does not contain and states any unit conversion it performs",
            "No numeric risk score or probability is produced — a fabricated percentage would look authoritative and mean nothing. Factors are listed with their evidence and their source",
            "The destination rule set and scrutiny notices are maintained by the export desk in the Knowledge collection; the agent reads them and never edits or extrapolates them, and any web_search result is surfaced as 'the desk should verify and update', never adopted as a rule",
          ],
          cost:
            "Both agents run exactly once per consignment and the decision they inform is worth more than any plausible token bill, so the parameter check sits on the standard tier and the single review-writing call is allowed the premium tier — this is the hard step the economy tier elsewhere in the stack exists to pay for. Volume is naturally bounded by consignment count, so there is no multiplier to manage; keep the Knowledge retrieval scoped to the destination in question rather than growing the tier further, and configure a fallback model so a provider outage cannot become a reason to ship unreviewed.",
          output:
            "The pre-dispatch Webhook opens the review → the parameter agent, using the exporter's own `dest_param_band` factors, compares every result against the destination's limits and names the untested ones → the premium-tier assembler adds traceability completeness, alert history and current scrutiny notices into an evidenced risk review → the Condition node escalates anything elevated → the export head decides ship, hold, re-test or re-route at the Approval node → an Output node notifies plant and the buyer-facing team and a Tool (MCP) node records the decision and its reasoning against the consignment.",
        },
      },
      {
        id: "sea-fisher-voice",
        title: "Fisher intake and payment queries by voice, in the local language",
        scenario:
          "Today: the purchase office runs on one phone. Fishers call in from the harbour wall or from the boat to declare what they are bringing, and call again days later to ask what grade it was assessed at, what it paid, and when the money lands. The clerk writes declarations on a pad, reads grades off a screen the fisher will never see, and repeats himself in Malayalam, Tamil or Konkani all day. There is a portal. Nobody uses it — the man asking is standing on a wet deck with one hand free.",
        triggerLabel: "Voice call / WhatsApp voice note (fisher declares a landing or asks about payment)",
        steps: [
          { kind: "trigger", title: "The fisher calls or sends a voice note", detail: "An inbound Voice call or a WhatsApp voice note from the fisher's registered number opens the conversation; the number identifies them against the purchase register." },
          { kind: "voice", title: "Speak their language, both ways", detail: "A Voice node uses Sarvam for Indian-language speech-to-text and ElevenLabs for the spoken reply, so a Malayalam or Tamil voice note is understood as sent and answered aloud in the same language — no portal, no typing, one hand free." },
          { kind: "agent", title: "Route the intent, then answer from the record", detail: "An Agent node separates a landing declaration from a payment query. A declaration is captured into the same structured landing schema the traceability workflow uses; a payment query is answered from the purchase and payout record pulled via a Tool (MCP) node." },
          { kind: "condition", title: "Declaration, query, or dispute?", detail: "A Condition node routes: a declaration goes to the traceability sub-workflow, a plain payment query is answered, and anything disputing a grade, a weight or a rate — or requesting an advance — goes to a person." },
          { kind: "subworkflow", title: "Hand a declaration to the landing workflow", detail: "A Sub-workflow node passes a voice-declared landing into the catch-landing traceability recipe, so a spoken declaration produces the same gap-checked, human-confirmed record as a written slip — the convenient channel does not get a second, weaker data path." },
          { kind: "approval", title: "Any rate or payout is human-approved", detail: "An Approval node holds every commitment involving money: quoting a purchase rate, releasing a payment, granting an advance, or correcting a disputed grade. The agent explains what is recorded; a person decides what changes." },
          { kind: "output", title: "Answer aloud, and confirm the payment reference", detail: "An Output node replies in the fisher's language — spoken on a call or voice note, text on WhatsApp — and, where a human has approved and released a payout, includes the Razorpay UPI reference." },
        ],
        connectors: ["Voice/Call", "WhatsApp", "SMS/Twilio", "Sarvam (Indian languages)", "ElevenLabs (voice)", "Razorpay (UPI)", "Tool (MCP → purchase / payout system)", "Approval / HITL"],
        outcome:
          "Fishers can declare a landing or ask about a grade and a payment by voice in their own language at any hour, grounded in the actual purchase record — instead of queuing for one clerk on one phone — with every rate, payout and correction still passing a person.",
        impact:
          "The mechanism is removing the channel barrier without weakening the record: voice in a local language is how this workforce actually communicates, and a portal they will not use is the same as no system at all. Feeding voice declarations into the same traceability sub-workflow avoids the usual trap, where the convenient side-channel quietly produces worse data than the main one.",
        build: {
          agents: 1,
          agentsNote:
            "1 intake-and-query agent. Speech in/out (Sarvam + ElevenLabs), the intent branch, the traceability Sub-workflow and the rate/payout Approval are workflow nodes — the landing record is structured by the sub-workflow's own agent, not duplicated here.",
          prompt:
            "You are a seafood purchase desk's fisher-support assistant for {{plant}}. The fisher is identified by their calling number against the purchase register; an unregistered number is asked for a supplier code rather than served speculative data, and no fisher is ever shown another's records. First classify the intent: LANDING_DECLARATION, PAYMENT_QUERY, GRADE_QUERY, DISPUTE or ADVANCE_REQUEST. For a declaration, collect the landing fields and hand off to the landing sub-workflow — do not assess a grade or state a value yourself. For a payment or grade query, answer ONLY from the purchase and payout record returned by the Tool (MCP) lookup: state the assessed grade, weight, applied rate and payment status as recorded, and the documented payment cycle. Never quote a rate for a future landing, never estimate what a catch 'should' fetch, and never say a payment has been made unless the record says so. DISPUTE and ADVANCE_REQUEST are acknowledged in the fisher's language and handed to a person — never negotiated. Reply in the language the fisher used.",
          agentRoles: [
            {
              role: "Fisher intake & query agent (per conversation)",
              model: "Economy tier — every fisher, most landing days, largely intent-routing and record read-back; correctness comes from the purchase-record lookup, not from model size. A fallback model on a second provider matters here, because the alternative during an outage is the one clerk and the one phone.",
              prompt:
                "Classify intent, identify the fisher by registered number, and either hand a declaration to the landing sub-workflow or answer a grade/payment question from the real purchase record in the fisher's own language. No rate quotes, no grade assessments, no payment promises. Disputes and advances are handed off.",
            },
          ],
          mcp: [
            "Purchase / payout system — via Tool (MCP): look up a supplier's landings with assessed grade, weight and applied rate, and read the payment status for a cycle",
            "Purchase / payout system — via Tool (MCP): the payout release call, invoked ONLY by the workflow after the Approval node, never by the agent mid-conversation",
            "Voice / WhatsApp / SMS — the fisher's own channel, on the boat or the harbour wall",
            "Razorpay UPI — payout reference read back to the fisher once a human has released the payment",
          ],
          tools: [
            "knowledge_search (scoped Knowledge collection: grading definitions, published purchase-rate policy, payment-cycle terms)",
            "http_request (look up the fisher's landings, grades and payment status by supplier code)",
            "user-defined tool `payout_line_spoken` (SpEL, sandboxed formula — no API calls, no code): input.grade + ' · ' + input.weightKg + ' kg × ₹' + input.ratePerKg + ' = ₹' + (input.weightKg * input.ratePerKg) — the plant's own settlement line, arithmetic done by the expression rather than by a model, and read aloud in the same shape every time",
            "user-defined tool `settlement_state` (SpEL): input.paidOn != null ? 'PAID' : (input.approvedBy != null ? 'AWAITING_RELEASE' : 'PENDING_APPROVAL') — reports where a payment stands from the record's own fields; it can describe the state, it can never advance it",
          ],
          guardrails: [
            "Identity is bound to the registered calling number; an unrecognised number is asked for a supplier code and never served another fisher's grades or payments",
            "The agent never assesses a grade and never quotes a purchase rate — grading is a physical assessment and a rate is a commercial commitment; both pass a person at the Approval node",
            "Every payout, advance and disputed-grade correction is approved and released by a human. `payout_line_spoken` formats a settlement that already exists and `settlement_state` labels it; neither can move money",
            "Voice-declared landings go through the SAME traceability sub-workflow, completeness gate and human confirmation as written slips — the convenient channel does not get a weaker record",
            "Low-confidence transcription asks the fisher to repeat rather than acting on a guessed weight or species, and the reply always mirrors the language the fisher used",
          ],
          cost:
            "One economy-tier agent call per conversation; the real variable cost is the Sarvam and ElevenLabs speech legs, billed per audio minute, so spoken replies are kept short and factual rather than conversational — and the settlement arithmetic is a SpEL expression, not tokens. No batch multiplier and no premium tier anywhere in this recipe: accuracy comes from the purchase-record lookup, the strict intent routing and the human approvals, and a bigger model would buy none of them. The fallback model is the spend that actually protects the service.",
          output:
            "Sarvam transcribes the fisher's call or voice note → the economy-tier agent classifies intent and identifies them by registered number → a declaration is handed to the landing traceability Sub-workflow (same gate, same human confirmation), a grade or payment question is answered from the real purchase record using the plant's own `payout_line_spoken` and `settlement_state` expressions, and a dispute or advance request is routed to a person → any rate, payout or correction passes the Approval node → an Output node answers aloud in the fisher's own language via ElevenLabs, with the Razorpay UPI reference included once a human has released the payment.",
        },
      },
    ],
  },
];

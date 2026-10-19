package com.agenticai.core.workflow;

/**
 * The node taxonomy from the Interactive Workflow Designer PRD, Section 4.
 * {@code supported} marks which types {@link WorkflowExecutionEngine} can
 * actually run today - Trigger/Agent/Knowledge/Condition/Approval/Output are
 * thin wrappers around services that already exist elsewhere in this
 * codebase (exactly the PRD's design principle #4: "the canvas should reuse
 * them, not replace them").
 *
 * <p>TOOL/LOOP/CODE were the palette's "Coming soon" trio and are now real,
 * executable node types too:
 * <ul>
 *   <li>{@code TOOL} ("Tool / MCP Call") - invokes one specific tool already
 *       resolvable for a chosen agent via {@code AgentToolProvider} (the same
 *       MCP/knowledge/memory/device/etc. tool catalog that agent's normal
 *       chat turns get), rather than routing through a full LLM turn just to
 *       call one known tool. See {@code WorkflowNodeExecutor#executeToolNode}.
 *   <li>{@code LOOP} ("Loop / Iterator") - "repeat the next node per item"
 *       semantics: takes a list, runs its single downstream node once per
 *       item, collects the results into a JSON array, then continues past
 *       that node. This is deliberately NOT a true sub-graph/cycle - no back
 *       edge is ever created, so {@link OrchestratorService}'s cycle
 *       detection and this engine's linear graph-walk model are untouched.
 *       See {@code WorkflowExecutionEngine#execute}'s loop-handling branch.
 *   <li>{@code CODE} ("Transform / Code") - a fixed library of built-in,
 *       safe transforms (JSON field extract, regex replace, template
 *       interpolation, upper/lowercase, JSON parse/stringify), NOT arbitrary
 *       JS execution - there is no sandboxed script engine anywhere in this
 *       codebase, and adding one was explicitly ruled out as new
 *       infrastructure/security-review surface this feature doesn't need.
 *       See {@code WorkflowNodeExecutor#executeCodeNode}.
 *   <li>{@code SUPERVISOR} ("Supervisor Routing", Master Roadmap 2026 item
 *       #12, §E) - an LLM-routed branch selection: given a small,
 *       author-configured list of named routes (a {@code handle}/{@code
 *       label} pair per route, stored in this node's own {@code routes}
 *       config, NOT read off the graph's edges), the configured agent
 *       picks exactly one, and that choice becomes this node's {@code
 *       branch} - the same mechanism {@link WorkflowExecutionEngine}
 *       already uses to resolve a Condition node's true/false ports, so
 *       zero engine changes were needed to route a Supervisor node's
 *       output; only {@code WorkflowNodeExecutor#executeSupervisorNode}
 *       is new. See that method's javadoc for why it reuses {@code
 *       AgentChatService#chat} rather than new raw-ChatClient plumbing.
 *   <li>{@code PARALLEL} ("Parallel Fan-Out", Master Roadmap 2026 item
 *       #12, §E) - unlike every type above, this genuinely IS a
 *       multi-node operation: every outgoing edge names one branch node
 *       (single node per branch - the same bounded-scope choice
 *       {@code LOOP} makes for its own body node, not a full downstream
 *       sub-chain per branch), and all branch nodes execute concurrently
 *       against the same input, each with this run's {@code
 *       TenantContext} explicitly propagated onto its own worker thread.
 *       Results merge into a JSON array and execution continues from
 *       whichever branch's own next-hop resolves last - see {@link
 *       WorkflowExecutionEngine#execute}'s parallel-handling branch for
 *       the full mechanics, and {@code OrchestratorService}'s validation
 *       note on why the "every branch converges on the same join node"
 *       assumption is a convention this feature relies on, not a
 *       save-time-enforced invariant.
 *   <li>{@code FAN_OUT} ("Dynamic Fan-Out") - the missing cell of the
 *       runtime-sized/concurrent 2x2 that {@code LOOP} and {@code PARALLEL}
 *       between them left open. {@code LOOP} is runtime-sized but strictly
 *       sequential (N comes from the list its input resolves to, one
 *       iteration after another on the run thread); {@code PARALLEL} is
 *       concurrent but design-time-sized (its branch set IS its outgoing
 *       edge list, fixed when the author draws the graph, which is exactly
 *       why {@code OrchestratorService} can enforce
 *       {@link WorkflowExecutionEngine#MAX_PARALLEL_BRANCHES} at save time).
 *       Neither covers "I do not know until the run starts how many things
 *       there are, and I want them worked on at the same time" - the
 *       classic map/fan-out shape (score 40 inbound leads, summarize 12
 *       attachments, call one enrichment agent per row of a scraped table).
 *       {@code FAN_OUT} is that: it resolves a list from its own runtime
 *       input exactly the way {@code LOOP} does (same {@code itemsField}
 *       contract, deliberately - a second, differently-spelled way to name
 *       "where is the array" would be gratuitous divergence), then runs its
 *       ONE downstream worker node once per item CONCURRENTLY, merging the
 *       per-item outputs into a JSON array in item order.
 *       <p>Graph shape is {@code LOOP}'s, not {@code PARALLEL}'s: exactly
 *       one outgoing edge, to the worker node. That is not a stylistic
 *       choice - the branch set here is runtime DATA, not edges, so a
 *       second outgoing edge would have no meaning to attach an item to,
 *       and having exactly one worker node means the post-fan-in next hop
 *       is unambiguous (resolved off the worker node, exactly like
 *       {@code LOOP}'s) instead of inheriting {@code PARALLEL}'s documented
 *       "continues from whichever branch resolved last" join defect. Like
 *       {@code LOOP}, no back edge is ever created, so
 *       {@link OrchestratorService}'s cycle detection and this engine's
 *       linear graph-walk model are untouched.
 *       <p>Bounded on three independent axes, because they bound three
 *       different resources and none implies the others: {@code maxItems}
 *       (how much of a runaway runtime list is worked at all - the token/
 *       cost bound, same 25-default/100-ceiling shape as {@code LOOP}'s
 *       {@code maxIterations}), {@code maxConcurrency} (how many worker
 *       threads run at once - the thread/DB-connection/provider-rate-limit
 *       bound, which no existing construct had: {@code PARALLEL} conflates
 *       the two only because branch count and concurrency are the same
 *       number when the fan-out is static, which is precisely the property
 *       this type removes), and the workflow's own {@code maxStepsPerRun},
 *       charged PRE-FLIGHT the way {@code PARALLEL} charges it rather than
 *       per-item after the fact the way {@code LOOP} does - see
 *       {@link WorkflowExecutionEngine}'s fan-out branch for why that
 *       difference matters enough to deviate from {@code LOOP} here.
 *   <li>{@code WORKFLOW} ("Trigger Workflow", #846) - runs a different,
 *       already-saved workflow definition synchronously (via {@code
 *       WorkflowService#run}, the same call the canvas's own "Run" button
 *       makes) and uses that sub-run's last-step summary as this node's
 *       output. Deliberately does NOT halt the parent run if the
 *       sub-workflow fails or pauses for approval - it marks this one node
 *       SKIPPED with the reason in its log and lets the parent graph decide
 *       what to do next (e.g. via a downstream Condition node). Two
 *       independent recursion guards, since this is the one node type that
 *       can reach outside its own graph and its own single-run step ceiling:
 *       {@link OrchestratorService#validate} rejects a node that targets its
 *       own containing workflow at save/activate time, and {@code
 *       WorkflowNodeExecutor} carries a runtime, thread-local call-stack
 *       check (plus a hard depth cap) that catches indirect cycles across
 *       separate workflow definitions (A triggers B triggers A) - see that
 *       class's {@code executeWorkflowTriggerNode} javadoc for why the
 *       thread-local guard is only a complete guarantee on the default
 *       synchronous engine, not the Temporal path.
 * </ul>
 */
public enum WorkflowNodeType {
    TRIGGER(true),
    AGENT(true),
    KNOWLEDGE(true),
    CONDITION(true),
    APPROVAL(true),
    OUTPUT(true),
    // Pre-existing gap found while extending this enum for TOOL/LOOP/CODE:
    // Device Control nodes (#652) have always been executable
    // (WorkflowNodeExecutor's string switch already has a "device_control"
    // case) but had NO entry here at all, so OrchestratorService#validate's
    // WorkflowNodeType.fromWire(node.type()) threw IllegalArgumentException
    // for every one of them - meaning any workflow containing a Device
    // Control node could never actually be Activated or Run, only saved as
    // a Draft. Fixed alongside this change since it's the same enum/same
    // root cause class as the TOOL/LOOP/CODE gap this change closes.
    DEVICE_CONTROL(true),
    TOOL(true),
    LOOP(true),
    CODE(true),
    WORKFLOW(true),
    SUPERVISOR(true),
    PARALLEL(true),
    // Wire value is "fan_out" (fromWire uppercases and this enum's name has
    // the underscore), matching DEVICE_CONTROL/"device_control" - the only
    // other multi-word type. Deliberately NOT "parallel_loop" or "map":
    // "parallel" already names a different, statically-shaped node in this
    // same palette and reusing the word would make two genuinely different
    // constructs read as variants of each other.
    FAN_OUT(true),
    // "HTTP Request" - the n8n/Zapier connector-catalog staple: one outbound
    // call to any public REST API/webhook (method, URL with {{input}}
    // templating, headers that may reference vault secrets, JSON/text/form
    // body), with the response (status/headers/body) as this node's output.
    // A single-node operation dispatched by WorkflowNodeExecutor#executeHttpNode,
    // backed by the same HttpRequestExecutor the http_request agent tool uses
    // (identical SSRF checks and secret redaction - see that class's javadoc).
    HTTP(true),

    // "Media" - narration, captions and video render, by calling the sidecar.
    // A single-node operation dispatched by WorkflowNodeExecutor#executeMediaNode
    // and backed by SidecarClient.
    //
    // WHY THIS IS A NODE TYPE AND NOT AN HTTP NODE POINTED AT THE SIDECAR:
    // because that is impossible, by design, from two directions at once. The
    // HTTP node delegates to WebPageFetcher#validatePublicHttpUri, which
    // requires every resolved address for the host to be PUBLIC - loopback,
    // RFC-1918, link-local and unique-local v6 all refuse - precisely because
    // an HTTP node's URL comes straight out of tenant-authored config. And the
    // sidecar is deliberately internal-only: agent-io network, loopback-only
    // host port, so CPU-expensive ML endpoints and uploaded tenant content are
    // not exposed. Both of those are correct and neither should change.
    //
    // So the only sound way for a workflow to reach /tts, /captions or /render
    // is for CORE to make the call in Java - where sidecar access is ordinary
    // and already authenticated - and for the tenant to supply no URL at all.
    // That is what this node is: the tenant picks an OPERATION, never an
    // endpoint.
    MEDIA(true),

    // "Human Input" (Tier-3) - pauses the run to collect STRUCTURED data
    // mid-run, the gap between Approval's approve/reject-only contract and
    // a full re-trigger: the node snapshots its `fields` form schema into a
    // PENDING workflow_input_request row and halts the run AWAITING_INPUT;
    // POST /api/workflows/input-requests/{id}/submit validates + resumes
    // the run at this node with {"form": {...}, "input": <upstream>} as its
    // output. Config: `message` (shown to the human), `fields` ([{key,
    // label, type, required, options?}]). A node with no fields is legal -
    // it is a pure acknowledge/pause step. Dispatched by
    // WorkflowNodeExecutor#executeHumanInputNode; resume mechanics are
    // WorkflowInputRequestService's, reusing the approval-resume machinery
    // (CAS + ApprovalReplayGrant + WorkflowExecutionEngine#resume).
    HUMAN_INPUT(true),

    // "Sub-Flow" (Tier-3) - true sub-graph iteration, the construct the
    // audit's "loops/fan-out are single-node bodies" gap names. Where LOOP
    // repeats ONE downstream node per item, SUB_FLOW carries a whole
    // embedded body graph in its own `body` config ({nodes, edges}, the
    // same DTO shapes as the outer graph) and walks that entire graph once
    // per item resolved from the same `itemsField` contract LOOP uses.
    // Multi-step bodies, body-internal Conditions and error edges, and
    // per-body-node tracing all work because the engine drives each body
    // node through the same executeNodeWithRetry/errorEdgeTarget path as a
    // top-level node. Entry is `entryNodeId` config or the body's single
    // no-incoming-edge node; the body's terminal output per item merges
    // into the familiar JSON array. Bounded by maxIterations (same
    // 25/100 clamp as LOOP) AND maxStepsPerRun charged per executed body
    // node AND per-item cancellation checks; body cycles terminate via a
    // visited-set rather than spinning. Deliberately NOT recursive:
    // workflow-graph cycle detection still never sees a back edge because
    // the body lives in config, and nested sub_flow/loop/fan_out/parallel
    // nodes inside a body pass through the executor's default branch
    // (they are not single-node-executable), which surfaces as a clear
    // trace entry rather than silent recursion. Honest limitation shared
    // with the rest of the loop family: an approval/human-input halt
    // inside the body completes the run with that status but resume
    // re-enters at the MAIN graph - the body position is not replayed.
    // Dispatched inside WorkflowExecutionEngine#execute (it is genuinely
    // multi-node, like LOOP/PARALLEL/FAN_OUT, so it cannot be an
    // executor single-node case).
    SUB_FLOW(true),

    // "Voice Menu" (V308, call-logic/DTMF fast-follow) - a dedicated IVR-menu
    // node: play/say a prompt, collect DTMF digits with a configurable
    // timeout + retry count, then branch on the digit(s) collected. Closes
    // the gap TwilioVoiceWebhookController's javadoc used to flag - DTMF
    // arrived on Conversation Relay's {@code dtmf} message and Twilio's own
    // {@code <Gather>} callback, but nothing in the workflow canvas could
    // build an actual "press 1 for sales, 2 for support" menu; voice call
    // logic was bolted onto the generic canvas with no node of its own.
    // Deliberately scoped to call LOGIC (menu structure, digit routing,
    // retry/timeout policy), NOT the audio pipeline - it reuses this
    // module's existing TTS narration (the same {@code <Say>} normalization
    // TwilioVoiceWebhookController already applies) and Twilio's own DTMF
    // detection; it does not choose an STT/TTS model or touch codec
    // negotiation (that is Conversation Relay's speech-to-speech surface -
    // VoiceRelayWebSocketHandler - which this node type does not interact
    // with at all).
    //
    // Config: {@code prompt} (spoken before gathering), {@code numDigits}
    // (optional - a null/absent value collects a variable-length run
    // terminated by {@code finishOnKey}, mirroring Twilio's own
    // {@code <Gather>} semantics), {@code finishOnKey} (default {@code "#"}),
    // {@code timeoutSeconds} (how long Twilio waits for the first digit),
    // {@code maxRetries} (how many times an empty/unmatched attempt re-plays
    // the prompt before falling through), {@code invalidPrompt} (optional,
    // spoken instead of {@code prompt} on a retry), and {@code branches}
    // ({@code [{digit, label}]} - the digit-to-outgoing-edge map, resolved
    // the same {@code branch()}/edge-{@code sourceHandle} way {@code
    // CONDITION}'s true/false ports and {@code SUPERVISOR}'s named routes
    // already are, so zero engine graph-walk changes were needed). Two
    // implicit branch labels beyond the configured digits: {@code
    // "no_match"} (digits were collected but matched no configured branch,
    // after retries are exhausted) and {@code "timeout"} (no digits were
    // ever collected, after retries are exhausted) - both optional edges,
    // same "no matching edge means this leg of the graph simply ends" default
    // every other branching node type already has.
    //
    // Halt/resume mechanics deliberately mirror {@code HUMAN_INPUT} above,
    // NOT a bespoke construct: a live phone call cannot poll, so gathering
    // DTMF genuinely means suspending the run (status {@code
    // AWAITING_VOICE_INPUT}) while Twilio's own {@code <Gather>} round-trip
    // happens, then resuming exactly where the run left off once the digits
    // (or a timeout) arrive - see {@code
    // com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequestService}
    // for the halt/resume pair, and {@code TwilioVoiceWebhookController} for
    // the webhook side that actually renders the DTMF {@code <Gather>} TwiML
    // and posts the collected digits back.
    VOICE_MENU(true),

    // "Bulk Email" (V335) - a personalized, throttled, suppression-aware send
    // to MANY recipients from ONE node. Unlike LOOP/FAN_OUT + an Output(email)
    // body node it is a single-node operation: recipients (an upstream items
    // array, a channel_contact group, or a static list) are iterated INSIDE
    // WorkflowNodeExecutor#executeBulkEmailNode / BulkEmailNodeHandler so the
    // per-recipient result rows, the throttle budget and the suppression /
    // replied checks live in one place. Each send is a MimeMessage whose
    // Message-ID is persisted to email_message so EmailReplyTracker can detect
    // replies and bounces (email.reply_received / email.bounced events).
    BULK_EMAIL(true),
    // "CRM Lead" node - upsert a lead / move its stage / add a timeline
    // activity in the built-in Leads CRM (V337). Config + semantics:
    // WorkflowCrmLeadNodeHandler. Single-node dispatch like "tool".
    CRM_LEAD(true),

    // ---- Data nodes (n8n node-pack parity): pure, deterministic, no-LLM, single-node dispatch through
    // WorkflowDataNodeHandler -> WorkflowDataOps. Wire values are the lower-cased names.
    // "Set" - assign named fields (values may be expressions), optionally keeping only those.
    SET(true),
    // "Filter" - keep the items of a JSON array that satisfy field/operator/value conditions.
    FILTER(true),
    // "Sort" - stable, type-aware ordering of a JSON array by one or more fields.
    SORT(true),
    // "Aggregate" - count/sum/avg/min/max/first/last/concat/collect over a field, optionally grouped.
    AGGREGATE(true),
    // "Limit" - keep the first or last N items.
    LIMIT(true),
    // "Remove Duplicates" - wire value "dedupe"; drop repeats by a field set (keep first or last).
    DEDUPE(true),
    // "Switch" - N named cases plus a fixed "default" port. Routes with the SAME mechanism CONDITION and
    // SUPERVISOR use: the chosen case name is the result branch, matched against each outgoing edge sourceHandle by
    // WorkflowGraphSupport#nextNodeId. The engine follows exactly one edge per node, so the FIRST matching case wins
    // (there is no "all matching cases" mode).
    SWITCH(true),
    // "Merge" - combine inputs: append, join by key, or first non-empty. The engine walks one path and runs a node
    // once, so it cannot suspend until N parallel branches arrive; it combines the node input array (the output of a
    // Parallel/Fan-out/Loop join) or the configured `sources` expressions ($node["X"].output) - see WorkflowDataOps#merge.
    MERGE(true),
    // Dify-style AI-native nodes (wave b, lane wfllm). Single-node dispatches
    // implemented by WorkflowAiNodeHandler; inert until a workflow uses one.
    // QUESTION_CLASSIFIER: a constrained, temperature-0 JSON model call that
    // picks exactly one author-defined class and routes down the matching
    // edge via the SAME branch()/sourceHandle mechanism CONDITION and
    // SUPERVISOR use. PARAMETER_EXTRACTOR: typed JSON extraction with one
    // repair retry and a _missing list. VARIABLE_AGGREGATOR: pure, no model -
    // first non-empty of N resolved sources. DOCUMENT_EXTRACTOR: file/URL to
    // text through the knowledge module's existing extractors, with caps.
    QUESTION_CLASSIFIER(true),
    PARAMETER_EXTRACTOR(true),
    VARIABLE_AGGREGATOR(true),
    DOCUMENT_EXTRACTOR(true),
    // "Wait" - a durable timer / external-resume gate. Config: {@code mode} ("duration" | "until" | "webhook"),
    // {@code duration} + {@code unit} (seconds|minutes|hours|days), {@code until} (ISO-8601 timestamp, expressions
    // allowed), and for webhook mode {@code timeout}/{@code timeoutUnit} + {@code onTimeout} ("fail" | "continue").
    // The run parks as PAUSED (the same re-entry operator Pause and approvals use) and a workflow_wait row,
    // swept by WorkflowWaitWakeJob or resolved by the signed resume URL, resumes it - nothing in memory. A
    // single-node operation from the author's side but special-cased in WorkflowExecutionEngine (it needs the
    // node that follows). Gated by app.workflow.wait-node.enabled: with the flag off, OrchestratorService#validate
    // rejects a graph containing one instead of silently skipping it. See WorkflowWaitService.
    WAIT(true),
    // "Data Table" node - read/write the tenant's Data Tables (app.data-tables.*, default OFF; the node
    // reports itself unavailable while the flag is off). Config + semantics: WorkflowDataTableNodeHandler.
    DATA_TABLE(true);

    private final boolean supported;

    WorkflowNodeType(boolean supported) {
        this.supported = supported;
    }

    public boolean isSupported() {
        return supported;
    }

    public static WorkflowNodeType fromWire(String value) {
        return WorkflowNodeType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}

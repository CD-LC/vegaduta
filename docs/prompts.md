# The prompt registry

Every piece of text the platform writes for a model, or that steers its behaviour, has a **key**, a **shipped
default** in a resource file, and optional **overrides** at three scopes. One resolver is the only way code reads
them. This page is for developers who add or migrate a prompt and for operators who change one.

Module: `core/prompts` (package `com.agenticai.core.prompts`). It depends only on `security` and `audit`, so any
module can depend on it. Configuration: `app.prompts.*` (see [configuration](configuration.md)).

## Keys

A key is two or more lower-case segments joined by dots, `<family>.<name>[.<variant>]`:

| Key | What it is |
|---|---|
| `directive.tool_trust`, `directive.tool_trust.compact` | a standing system-prompt block (full and small-model wording are two keys) |
| `nudge.continue_unfinished` | a platform note that steers the next model call |
| `toolresult.park_pending` | text a tool call returns to the model |
| `refusal.guardrail.policy` | text the user reads when a turn is stopped |
| `summarizer.compaction`, `judge.tool_call_verifier`, `router.complexity`, `planner.task` | the instruction of a secondary model call |
| `tool.description.<toolName>`, `tool.param.<toolName>.<param>`, `tool.template.<toolName>` | the description of a tool and of its parameters, as sent in the tool list; see [Tool descriptions](#tool-descriptions) |

Families in use: `directive`, `nudge`, `toolresult`, `refusal`, `message`, `summarizer`, `judge`, `router`,
`planner`, `memory`, `skills`, `delegation`, `compaction`, `guard` (detector data), `workflow`, `rag`, `learning`, `shim`, `tool.description`, `tool.param`, `tool.template`, `edge`. A new
family needs no registration; the key only has to match `PromptKeys.PATTERN` (at most 128 characters). A released
key never changes meaning: retire it (`retired: true`, empty text) and add a new one.

## Adding a prompt

1. Create `src/main/resources/prompts/en/<key>.md` in the module that owns the text. Any module may contribute
   keys; the catalog scans `classpath*:prompts/*/*.md`.

   ```
   ---
   title: Tool search
   description: When some tools are withheld to save context and reachable via search_tools.
   risk: STEERS_EVERY_TURN
   securityCritical: false
   disableable: true
   maxLength: 4000
   placeholders: date!, tz
   placeholder.date: Today's date, UTC, ISO-8601
   ---
   Text of the prompt, with {date} where the call site supplies a value.
   ```

   `title` and `risk` are mandatory. `risk` is `STEERS_EVERY_TURN`, `SECONDARY_CALL`, `USER_VISIBLE` or
   `TOOL_DESCRIPTION`. A placeholder name ending in `!` is required in every override. `disableable: true` lets an
   override switch the prompt off (standing directives; not the instruction of a secondary call that cannot run
   without one). `securityCritical: true` makes changing or switching it off need `acknowledgeRisk`.
   `strictPlaceholders: false` is for free text where braces are plain text. `minLength` / `maxLength` bound
   overrides (default `app.prompts.default-max-length`). The body is taken byte for byte, except that CRLF becomes LF
   and one final line break is dropped. Other locales are `prompts/<locale>/<key>.md` holding only the body.
2. Ask for the text where it is used:

   ```java
   @Autowired PromptResolver prompts;                       // a Spring bean from the prompts module
   ResolvedPrompt p = prompts.resolve("nudge.call_tool_now",
           PromptContext.forAgent(agentId).withVariable("tool", name));
   String text = p.textOrNull();                            // null: switched off, or unknown key
   ```

   Hand-built code (static helpers, tests) uses `PromptResolver.defaultsOnly()`: the shipped text, no database.
3. Add a golden test: the text the key resolves by default equals the literal it replaces
   (`DirectivePromptGoldenTest` in core/agents-turn is the model). If the migration is meant to change wording,
   say so in the change log.
4. A module with its own keys should assert `PromptResolver.defaultsOnly().catalog().problems()` is empty; a broken
   file is skipped and logged at startup, never fatal, and listed there.

## Resolving

`PromptResolver.resolve(key, ctx)` returns a `ResolvedPrompt { key, text, source, scope, version, locale, enabled,
known, hash, estimatedTokens }`. Order: the agent's override, the tenant's, the platform's, the shipped text. Inside
a scope an override in the context's locale wins over one in the default locale. A text that is blank means "no
override here". An override that switched the prompt off yields `text == null`, `enabled == false` (and wins over the
lower scopes: an agent-scope override can switch it back on for one agent). `resolveAll(ctx)` does every key.

It never throws: a missing key, a database error or a broken row gives the shipped text (`source = FALLBACK` for a
failed lookup) and a log line; an unknown key gives an inactive result and an ERROR log once per key. The call site
decides *whether* a prompt applies (the tool is on the list, the feature flag); the resolver decides *what text*.

Overrides are cached in process (`app.prompts.cache.ttl`, default 30 s). A write evicts the writing replica at
once; the other replicas serve their cached copy until the TTL runs out, so a change reaches all replicas within one
TTL. There is no cross-replica push and no per-lookup version query. A failed load caches "no overrides" for
`app.prompts.cache.failure-ttl`.

`PromptBudget` is the hook for token awareness: the assembler adds each block and reads back whether the standing
text is over `app.prompts.system-prompt-token-budget`. Nothing is dropped or truncated; the preview flags it.

## Overrides, versions and history

| Table | Scope |
|---|---|
| `prompt_override`, `prompt_override_history` (tenant schema, V664) | `tenant` (scope id empty) and `agent` (scope id = agent UUID) |
| `control_plane.platform_prompt_override`, `..._history` (V665) | `platform` |

`prompt_override` holds the current override of a (scope, scope id, key, locale); `version` is 1 for the first save and
+1 for every change, a revert included. `prompt_override_history` keeps every version with its author, reason, new
and previous wording; a trigger refuses UPDATE and DELETE on it. A revert removes the current row and appends a
`REVERT` row; the version counter continues. An agent-scope override is accepted only for an agent of the calling
tenant; tenant and agent rows live in the tenant's schema, so one tenant can never read or write another's.

Saving text equal to the shipped default removes the override instead (a stale copy never pins old wording), unless a lower scope (platform below a tenant; tenant or platform below an agent) overrides the key. Then the default wording is stored as a real override at the higher scope, so it opts out of the lower one.

## Admin API (`/api/prompts`)

Tenant admin only; the `platform` scope also needs a platform admin. `scope` is `platform`, `tenant` or `agent`
(agent also takes `?agentId=`); `locale` defaults to `app.prompts.default-locale`.

| Call | Does |
|---|---|
| `GET /api/prompts?agentId=&locale=&family=&q=&limit=&offset=` | every key: definition, default, the effective value with its source and version, the stored overrides. No `limit` returns the whole registry; with `limit` (clamped to `app.prompts.list-max-limit`) and `offset` the list is windowed and `X-Total-Count` / `X-Has-More` are sent |
| `GET /api/prompts/{key}` | one key |
| `GET /api/prompts/{key}/history?scope=&agentId=&locale=&limit=` | the immutable history, newest first |
| `PUT /api/prompts/{key}/overrides/{scope}` | create or update; body `{text, enabled, acknowledgeRisk, reason, expectedVersion}`; `enabled=false` switches a disableable prompt off at that scope; a stale `expectedVersion` is a 409 |
| `DELETE /api/prompts/{key}/overrides/{scope}` | revert: the next lower scope or the default applies again |
| `POST /api/prompts/{key}/overrides/{scope}/restore` | re-apply an earlier version's wording as a new version |
| `POST /api/prompts/{key}/validate` | check a draft without saving it |

Every write is audited (`PROMPT_OVERRIDE_UPDATED` / `PROMPT_OVERRIDE_REVERTED`) with the actor, scope, key, locale,
version, SHA-256 of the old and new text and the line diff of the wording.

The older `/api/prompt-directives` routes keep working as a thin layer over the tenant scope; a directive with two
wordings is written to both keys.

## Prompt preview

`POST /api/agents/{agentId}/prompt-preview` (EDITOR access to the agent) builds the real system text and tool list
of an agent exactly as a turn would, without calling a model and without storing anything. Body (all optional):
`{message, sessionId, smallModel, includeMemoryRecall}`. The answer has the `systemPrompt`, the per-turn
`memoryBlock`, every block with `{message, id, key, source, scope, version, hash, estimatedTokens, text}`, the
tools with their descriptions and schemas, token totals, and `notes` naming every input a preview cannot know
(hook notes, RL arms, the live model's small-model status). It shares `ChatSystemPromptComposer.compose` with real
turns, so the two cannot drift apart.

## Turn pipeline texts

Besides the standing directives, the text a turn writes to the model or to the person is a key. The default of each
is the resource file `core/agents-turn/src/main/resources/prompts/en/<key>.md` (the spotlight banner is in
`core/guardrails`), byte for byte the wording that was in the Java source before; `TurnTextsGoldenTest` pins that.
An override of the agent, the tenant or the platform applies on every path that sends the text (streamed, buffered,
delegation and specialist calls, the continuation after an approval); where a reply callback runs on a thread with no
tenant, the text is read in the turn's tenant.

| Key | Sent when | Placeholders |
|---|---|---|
| `planner.plan_block` | PLANNED depth: the plan, as a system message | `plan!` |
| `memory.recall.private`, `memory.recall.shared` | the line that frames the facts recalled for the user (this agent's, the team's) | `facts!` |
| `memory.notes` | the line that frames the user's own notes (persona, profile, project notes); security critical | none |
| `memory.summary_preamble` | the rolling summary replayed ahead of the history; tenant or platform wording only (the replay does not know the agent) | none |
| `skills.header`, `skills.header.compact` | the instruction at the head of the skills list (full, small-model); the `## Skills` heading is part of the text | none |
| `delegation.context_header.collaborator`, `.specialist` | the summary a delegating agent forwards to a collaborator or specialist | `context!` |
| `delegation.empty_answer.collaborator`, `.specialist` | the tool result when the delegate answered with nothing | none |
| `refusal.safety_check_unavailable`, `refusal.moderation.blocked`, `refusal.moderation.queued`, `refusal.guardrail.input_default` | what the person reads when a check could not run, moderation blocked or held the message, or an input guardrail without its own reason blocked it | none |
| `refusal.guardrail.output_withheld` | replaces an answer an output guardrail withheld; stored as the answer | `guardrail!` |
| `message.fabricated_url.not_generated`, `message.fabricated_url.correction` | replaces / corrects an answer that carried an invented file link | none |
| `message.generic_failure` | the sentence shown and stored when a reply failed and the provider gave no message | none |
| `message.finish.truncated`, `.refusal`, `.malformed_tool_call`, `.empty_reply` | what the person reads when a reply is still cut off after the continuation passes, was stopped by the provider's safety system, had an unusable tool call, or came back empty; read for the turn's agent and tenant on the streamed, buffered and delegated paths. Deprecated aliases: `app.chat.finish.truncated-notice`, `refusal-notice`, `malformed-tool-call-notice`, `empty-reply-message` (a value that differs from the shipped text seeds the platform default; none is set in `application.yml` any more) | `reason` (in `.refusal`) |
| `toolresult.skill.workspace_framing` | the line that opens a `load_skill` result for a skill written in the workspace (not a built-in): task guidance that does not override the core instructions; security critical | none |
| `message.degraded.tools_disabled` | step-event text when a turn was answered without tools after a provider bug | none |
| `message.hook.*` | what a workspace hook says: the tool result for a stopped call or withheld output (`tool_denied`, `tool_output_withheld`), the default reasons, the refusals a person reads, the note framing. A hook author's own `reason` / `message` / `note` always wins over the default wording. These results stay recognised as "the tool did not run" whatever the words | per key |
| `compaction.summary_prefix`, `compaction.restore_hint`, `compaction.restore_hint.no_restore` | the summary that replaces old turns, and the note that replaces a cleared tool result; taken once per run, so a summary is recognised by the text that wrote it | `stepId!` in the hint |
| `toolresult.untrusted_content` | the banner around untrusted tool output; the delimiters around the content are code and cannot be changed; security critical | `tool!` |
| `nudge.continue_unfinished`, `nudge.reply_cut_off`, `nudge.retry_tool_call` | see below | none |
| `guard.fabricated_url.markers`, `.file_url_pattern`, `.claim_phrases` | see below; security critical | none |

**Nudges are registry keys, not settings.** The text sent to the model after an announced-but-untaken step
(`nudge.continue_unfinished`), a reply cut off at the output limit (`nudge.reply_cut_off`) and an unusable tool call
(`nudge.retry_tool_call`) lives in the registry and nowhere else: the shipped file is the default, and the agent, the
workspace or the platform can override it like any other prompt. The turn reads only the resolver. The older properties
`app.chat.continuation.nudge.<lang>`, `app.chat.finish.continue-nudge` and `app.chat.finish.retry-tool-call-nudge` are
**deprecated aliases**: a value that differs from the shipped text is applied as the PLATFORM-scope default of its key
(below every stored override, above the shipped file) and logged at startup naming the key to edit instead; a property
left at the shipped text, or unset, changes nothing, and none of them is set in `application.yml` any more. The
continuation nudge is chosen by the language whose patterns matched the reply: that language's override, else the
default language's override, then the property entry for the language (else the default language's entry), then the
shipped file of the language (else the default language's). To add a language, ship
`prompts/<locale>/nudge.continue_unfinished.md` holding only the text, and add its `app.chat.continuation.intent.<lang>`
patterns. The same rule covers `app.chat.escalation.low-confidence-markers` (alias of
`detector.escalation.low_confidence_markers`, one marker per line). `PromptPropertyAliasTest` pins the precedence, the
warning and the language selection, and fails if a call site reads one of these properties.

**Detector data.** The guard that recognises an invented file link reads its lists from the registry:
`guard.fabricated_url.markers` (one host per line), `.file_url_pattern` (one regular expression; an invalid one is
ignored and the shipped one is used, so a broken override never leaves the guard blind) and `.claim_phrases` (one
phrase per line). Every locale the catalog ships a file for is checked: to cover another language add
`prompts/<locale>/guard.fabricated_url.claim_phrases.md` holding only the phrases. Only English ships today.

## The console

`/admin/instructions` (tenant admins) has three prompt tabs. **All prompts** lists every key with the wording in
force now, which scope supplies it (shipped default, platform, workspace or agent) and its version; it searches by
key, title or description, filters by risk class and shows only customised prompts. Open a prompt to edit it at the
scope chosen at the top (workspace, one agent, or the whole platform for a platform admin): the draft is checked
by `POST /api/prompts/{key}/validate` as you type and problems (an unknown or missing `{placeholder}`, a length
over the limit, a prompt that cannot be switched off) are listed under the text, and saving is blocked until they
are fixed. A save carries the version the editor started from, so a change made meanwhile is a conflict, not an
overwrite. A security-critical prompt also needs the risk confirmation. **History** lists every version with
author, time and reason, the line diff against the version before, and one click to restore an earlier wording
(saved as a new version) or revert the override. **Preview final prompt** calls `POST /api/agents/{id}/prompt-preview`
and shows the blocks of the agent's system text, each tagged with its key, scope and version and a token estimate.

## Edge (in-browser) turns

Turns that run on the person's device never reach the server's prompt code, so their wording is registry keys too
(family `edge`): `edge.default_system_prompt` (used when an agent has no system prompt), `edge.reasoning_nudge`
(appended to every edge system prompt; the only one that may be switched off), the on-device guardrail refusals
`edge.refusal.jailbreak` and `edge.refusal.pii_*`, `edge.localcode_ask.system` (codebase Q&A) and `edge.ask_ai.system` (the "Ask AI" form-field writer, also read by the server's `AssistService`, so one override covers the hosted and the on-device draft). The web app reads
the effective values from `GET /api/edge/prompts?agentId=` (any signed-in user; resolved in their tenant, with the
agent's override when `agentId` is an agent of that tenant; cached by the browser for `app.prompts.cache.ttl`) and
keeps the bundled defaults in `web/app/lib/edge/edgePrompts.defaults.json` for everything it cannot reach: offline,
signed out (the anonymous try page), a server without the endpoint, a timeout or a malformed answer. A web test keeps
those defaults byte-equal to the shipped `edge.*.md` files. Only wording comes from the server: whether a message is
blocked is still decided by the patterns in `edgeGuardrails.ts`, and a refusal can never be switched off (a blank or
switched-off value for a key that is not `disableable` is ignored).

## Request inspector

The request inspector (Request X-ray: `GET /api/agents/{agentId}/sessions/{sessionId}/llm-requests`) shows what each
model call was sent. With `app.llm.request-snapshot-store.mode=redis` (default) every redacted copy is also written to
Redis with the same time-to-live (`app.llm.request-snapshot-store.ttl`, 6 h) and per-session depth, so another replica
answers for a turn this one ran and the copies survive a restart; `memory` keeps them in this JVM only. The write is
queued off the model-call path. Each system message lists the registry blocks it is made of (key, scope, version,
wording hash, token estimate), recorded by the turn's prompt assembler (`PromptBlockProvenance`) and matched to the
message by content; text that does not match carries no label.

## Tool descriptions

Every built-in tool's description, and the description of each parameter in its input schema, is a key. The shipped
text is a resource file in the module that owns the tool (`core/agents-tools`, `agents-browser`, `agents-interop`,
`agents-jobs`, `agents-studio`, ...), byte for byte the string the tool's code builds, so the tool list the model
sees is unchanged until someone overrides a key.

| Key | Holds |
|---|---|
| `tool.description.<id>` | the tool's description as sent in the tool list. `<id>` is the tool's name (`generate_document`); a second implementation that shares a name has an id `<name>.<variant>` (`http_request.safe`, `local_shell.unattended`) |
| `tool.param.<id>.<path>` | the description of one parameter. `<path>` is the property names from the schema root joined by dots (an array's `items` level adds nothing), each in snake case: `maxChars` is `max_chars`, the `agent` of `tasks[]` is `tasks.agent`. A variant without parameter keys of its own uses its plain tool's |
| `tool.template.<id>` | the description of a tool that fills run time values in itself (`delegate_parallel`, `delegate_to_specialist`, `delegate_to_agent`); the placeholders are listed in the key |

**One place applies them.** `ToolDescriptionResolver` (core/agents-tools) runs inside `ToolResolver` for every
list a turn is given, so streamed and buffered turns, delegation and specialist hand-offs, agent jobs, the
continuation after an approval and the prompt preview all see an override, for the agent the list is built for. A
tool with no override is returned as the same object. A tool with one is wrapped: same name, same calls, new
description and/or schema texts. MCP tools, user-defined tools (custom code, OpenAPI, scripts) and any tool without a
key pass through. **An override can change words and nothing else:** the tool name is never touched, only the
`description` text of schema properties that already have one is replaced, and the result must equal the built schema
once those texts are ignored, or the override is dropped and logged.

**Templates.** Where the code fills a value into the description (the shell's environment note, whether each call
needs approval) the key is a template and the value a placeholder: `local_shell` / `local_shell.unattended` /
`local_shell_read` take `{environment}`, the actions of `local_desktop_*` that need approval end in
`{approval_note}` (the sentence the platform appends when calls need approval; leave the placeholder out and the
sentence is dropped), `delegate_parallel` takes `{roster}`, `delegate_to_specialist` `{roster}`, `{max_depth}`,
`{hop}`, `delegate_to_agent` `{name}` and `{specialty}`. For `tool.description` templates the values are recovered
from the text the code built, which must equal the shipped template filled in; `tool.template` keys are rendered by
the tool itself when it is built (`ToolPromptTexts`, bound around the resolution of the list), so a tool built
outside a resolution, such as the quick-task delegation tools, uses the shipped text.

**Risk.** All of them have risk class `TOOL_DESCRIPTION` and cannot be switched off. Tools that act on the
outside world or handle credentials (`http_request*`, `local_shell*`, `local_desktop_*`, `run_code`, the device
readers, payments, `channel_send_message`, `forward_attachment`, `data_table_write`, `schedule_task`, `request_human`,
`browser_totp`, `run_browser_recipe`, ...) are `securityCritical`: changing one needs `acknowledgeRisk`, because a
weaker description can make the model skip an approval or paste a secret.

**Adding a tool.** Create the keys (the description, then one per described parameter) in the tool's module and add
the class to that module's golden test: it builds the tool without running its constructor
(`ToolPromptGolden.probe`) and fails if a shipped text differs from what the code builds, if a key is missing, or if a
`tool.param.<id>.*` key has no parameter. Dynamic parts of a schema (`maxItems`) are never keys.

**The feature-module tools (second half).** The memory (`remember_fact`, `remember_shared_fact`), skill
(`load_skill`, `read_skill_file`, `run_skill_script`, `propose_skill`, `improve_skill`), `restore_tool_result`,
workspace file (`workspace_list|read|search|write|edit`), code-intelligence (`search_code`, `code_callers`,
`code_callees`, `code_impact`, `code_graph_stats`, `create_code_workspace`, `apply_code_changes`,
`run_in_code_workspace`, `diff_code_workspace`, `delete_code_workspace`), Google (`google_calendar`,
`google_calendar_find_free_slots`, `google_calendar_move_event`, `google_calendar_cancel_event`, `google_gmail`,
`google_drive`, `google_sheets`, `youtube_content_performance`, `upload_video_to_youtube`), graph-memory recall,
`generate_image|video|music`, knowledge (`search_knowledge_base`, `add_to_knowledge_base`,
`list_knowledge_collections`), mandate payment (`create_payment_link.mandate`, `check_payment_status.mandate`,
`request_upi_collect`), Sarvam, and `create_workflow` tools are keys in the module that owns them. The two payment
tools share their names with the commerce tools of `core/agents-tools`, so they are the `.mandate` variants. The
workspace file tools fill the label of the projects folder into their description, so theirs are templates with the
placeholder `{root}`, which an override must keep. The tools that send, write, move money, run code or carry an
approval notice in their wording (`apply_code_changes`, `run_in_code_workspace`, `delete_code_workspace`, the
write/send Google tools, `upload_video_to_youtube`, `create_payment_link.mandate`, `request_upi_collect`,
`run_skill_script`, the workspace file tools) are `securityCritical`.

**Tools that are not in the registry, and why.** The eight tools the platform's own MCP server offers to external
clients (`list_my_agents`, `call_agent`, `list_my_workflows`, `call_workflow`, `search_knowledge`,
`start_agent_task`, `get_task`, `cancel_task`) are built once at start-up for every tenant and caller at once, so
there is no agent or tenant to resolve an override for; their wording is part of the MCP interface. The pseudo tools
of the provider shims and structured output (`final_answer`, the forced structured-result tool) are built by the
chat module's shims, outside any agent's tool list, and their one-line descriptions are still code (the `shim.*` keys
below cover the shims' instructions, not these two descriptions). The placeholder definition of an unavailable tool is
never listed to the model, and the description of a client-supplied tool is the client's own text.

## Guardrail, tool-loop and swarm text

These keys steer what the model does after a block, a hold or a stop. They are `securityCritical` (changing one needs
`acknowledgeRisk`) and none can be switched off, because the call site cannot work without text.

| Keys | Where the text is used |
|---|---|
| `toolresult.guard.dispatch_denied` (+ `.default_reason`), `toolresult.guard.dispatch_check_failed`, `toolresult.guard.verifier_rejected` (+ `verifier_default_reason`), `toolresult.guard.procedure_blocked`, `toolresult.guard.output_withheld`, `toolresult.guard.arg_policy_violation` | the tool result `GuardedToolCallback` and `ToolArgPolicyCallback` return when they stop a call or withhold an output |
| `toolresult.guard.tenant_rule_matched`, `.pattern_injection_marker`, `.semantic_default_reason`, `.semantic_unavailable` | the reason sentence inside `toolresult.guard.output_withheld` |
| `toolresult.park.awaiting`, `.queued`, `.queue_full`, `.record_failed` | a call held for human approval; compatibility aliases `app.guardrails.approval-park.*-message` |
| `toolresult.eval_sandbox.simulated` | the tool result of a call an offline eval turn answers instead of running the tool, because the tool could change data or reach outside the platform; it tells the model nothing was changed or sent and to carry on as after a success (`GuardedToolCallback`; see "Evals ... on the real pipeline" in `configuration.md`) |
| `toolresult.tool_budget.calls`, `toolresult.tool_budget.rounds`, `toolresult.tool_budget.tokens`, `message.tool_budget.hard_stop` | the tool-call ceiling, the tool-round cap, the cumulative prompt-token budget and the closing reply; aliases `app.guardrails.step-limit.exhausted-message`, `app.chat.tools.budget-exhausted-message`, `app.chat.tools.hard-stop-reply` |
| `toolresult.unavailable.*` | a tool that exists but is not switched on for the workspace, or a name the model invented. `toolresult.unavailable.generate_image` is the chat module's answer to a never-offered `generate_image` (OpenAI wording); the tool's own answer when no image provider is active is `toolresult.image_generation.not_configured` (names OpenAI and Google Vertex AI), a separate key so each keeps its shipped wording |
| `toolresult.tool_breaker.disabled`, `.last_error`, `.wait.one`, `.wait.many` | the result of a call to a tool whose circuit breaker is open (`LoggingToolCallback`); `last_error` and the wait are fragments filled into `{last_error}` and `{wait}` of the first |
| `toolresult.invalid_arguments.hint`, `.unknown_keys`, `.problem.not_json`, `.problem.not_object`, `.problem.missing_required` | the "NOT executed" feedback when a tool call's arguments do not match its input schema (`ToolArgumentValidator`); the machine-readable `error` and the schema in the same result are code |
| `toolresult.image_generation.not_configured`, `.failed`, `.quota_suffix`, `.success_instructions` | the text `generate_image` returns for a failed, quota-limited or successful generation (`{image_url}` in the last) |
| `procedures.header`, `procedures.marker.*` | the "Operating procedures" block of the system prompt (the heading and numbering are layout, not text) |
| `judge.tool_verifier` (+ `judge.tool_verifier.context`, the line in front of the conversation tail), `judge.injection`, `judge.policy_pack` (+ `judge.policy_pack.<category>`), `judge.toxicity` | the instruction of each guardrail judge; the answer format they ask for (`APPROVED` / `REJECTED: ...`, `SAFE` / `UNSAFE: ...`, `SCORE: n`, a bare number) is parsed by code, so an override must keep it |
| `refusal.guardrail.*` | what the user reads when a content guardrail blocks a message |
| `shim.router.format`, `shim.router.required`, `shim.structured.schema_instruction` | the tool-routing and structured-output shims for local models |
| `delegation.swarm_worker`, `delegation.swarm_worker.role` | the system addendum of every `run_subtasks` worker |

**Compatibility aliases.** A text that was already a property keeps working: an override of the key at any scope wins,
then the property when an operator changed it (it differs from the shipped wording), then the shipped wording. With
nothing customised the result is the shipped text, byte for byte.

**Whose override applies.** The tenant is the one on the calling thread. The agent is the agent of the guarded call
(`GuardedToolCallback`), of the turn (the tool loop: the turn's `ToolLoopTranscript.forPrompts(agent, tenantScope)` carries
both onto the loop's threads) or of the swarm (`run_subtasks`). The judges receive the agent through
`GuardrailPromptScope.open(agentId)`, opened by `GuardedToolCallback` around the call. The content gate and the
moderation layer do the same: `ContentGuardrailEngine.evaluate` and `ModerationService.moderate(content, context, chatClient)`
open the scope for `context.agentId()` (and close it again, also when a guardrail throws), so an agent override of a
`refusal.guardrail.*` text, `judge.toxicity` or `judge.policy_pack` applies on the inbound and outbound gates of a chat
turn, whose refusal is persisted into the history. A caller that invokes a single `ContentGuardrail` directly, without the
engine, sees the tenant's override only unless it opens a `GuardrailPromptScope` itself.

**Known limit: no platform-only scope.** A tenant admin who passes `acknowledgeRisk` can override `judge.injection`,
`judge.tool_verifier` and the `toolresult.guard.*` texts for their tenant. The registry has no per-key "platform scope
only" flag yet; until it does, treat the `acknowledgeRisk` audit entry as the control and review those overrides in the
override history.

### Detector pattern sets

The regular expressions the pattern guardrails match are resource files, not Java literals: `guardrails/patterns/<locale>/<set>.txt`
(`jailbreak`, `injection-markers`, `policy-child-safety`, `policy-self-harm`, `policy-violence`, `policy-illegal-activity`,
`policy-hate-speech`) and `guardrails/patterns/redaction.txt` for the secret formats that are redacted from tool output (not
language dependent). One Java regex per line; `#` starts a comment; a leading `(?i)` / `(?im)` becomes the pattern's flags;
`{GAP}` in `jailbreak.txt` is the bounded gap between keywords (the ReDoS guard, `[^\n]{0,40}?`); a redaction line is
`label :: regex`. The English file of each set is mandatory (a missing or empty one fails fast, because a detector with no
patterns would stop detecting silently). To cover another language, ship `guardrails/patterns/<locale>/<set>.txt` in any
module's resources: the patterns of every locale on the classpath are checked together, English first, because an attacker
chooses the language; a file never replaces the English one, and a broken line in another locale is logged and skipped. The
shipped English files hold exactly the patterns that used to be compiled in the classes (a golden test pins them). The sets are
read at startup and are not editable through the admin API: they are the platform's floor, and a tenant adds to it with its
own guardrail rules.

## Secondary model calls and detector lists

The instruction of every cheap helper call the platform makes besides the answer itself is a key with risk class
`SECONDARY_CALL`. The default of each is a resource file in the module that owns the text (`prompts/en/<key>.md`),
byte for byte the wording that was a Java literal; a golden test in each module pins that. None of them can be switched
off (the call cannot run without an instruction). Where a call knows its agent, an agent override applies; where it does
not, the tenant's (or the platform's) does. Keep the reply format a text asks for (`SOLVED` / `INCOMPLETE:`, `OK` /
`FLAG:`, a JSON shape, `SCORE:`): the code that reads the reply depends on it, and a reply it cannot read fails open.

| Key | Used by | Placeholders | Agent scope |
|---|---|---|---|
| `summarizer.compaction`, `summarizer.session` | context compaction; the rolling session summary | none | yes |
| `quality.reflexion.critique`, `.revise`; `quality.consistency.check`, `.revise` | the RIGOROUS and CAREFUL depths' second look at an answer | none | yes |
| `planner.task`, `planner.task.replan`; `planner.task.tools_none`, `.tools` | the PLANNED depth's plan, the re-plan, and the sentence naming the agent's tools | `tools` (in `.tools`) | yes |
| `judge.quality` | the sampled quality judge behind the Issues portal | none | yes |
| `router.complexity` | the YES/NO question that lets a cheaper model answer | none | yes |
| `router.agent_select`, `router.agent_additional` | choosing an agent for a task and finding extra specialists | `agent` (in `_additional`) | no (tenant) |
| `skills.distilled.purpose`, `.when_to_use`, `.procedure`, `.footer`, `.description` | the text of a skill distilled from repeated tool sequences (staged disabled) | `runs`, `sessions`, `sequence` | yes |
| `swarm.verifier`; `swarm.brief`, `swarm.brief.objective` | the verification step of a multi-agent team (security critical); the brief each worker receives | `objective`, `subtask`, `goal` (in the brief) | no (tenant) |
| `memory.entity_extraction`, `memory.arbitration` | graph-memory triples and the add / update / discard decision | none | yes |
| `learning.lesson`, `learning.skill_draft`, `learning.skill_self_check` | lessons and skill drafts learned from finished turns, and the check of a draft; the first two are security critical (they carry the rule against copying personal data) | none | yes |
| `research.system` | the seed prompt of a new deep-research agent (an existing agent keeps the prompt stored on it); security critical (it says passage text is data) | none | no (tenant) |
| `research.goal.untrusted_notice`, `research.goal.tail` | the sentence after the fenced question in the goal of every research run (the fenced text is data), and its closing instructions (call `deep_research` first, no placeholder sources); the first is security critical | `fence_open!`, `fence_close!` (in the notice) | yes (the research agent) |
| `workflow.generator.system` (+ `.condition_expression_hint`, `.per_item_send_hint`, `.expression_section`), `workflow.generator.ref_mode`, `workflow.generator.direct_addendum` (+ `.approval_rule`), `workflow.generator.edit_suffix` | the AI workflow generator's meta-prompt: the graph contract and node types, the expression-language lesson (the function list is built from the live registry), the Brief planner's reference mode, the schedule contract and the change-request suffix. The reply must stay the JSON the designer parses | `agent_list!`, `knowledge_list!`, `functions!`, `current_graph!`, hints | no (tenant) |
| `edge.ask_ai.system` | the "Ask AI" form-field writer: one key for the server (`AssistService`) and the console's on-device draft (served in the edge bundle) | none | no (tenant) |
| `diagnostics.failure_triage`, `diagnostics.technical_explanation` | the background failure triage and the novel-failure explanation; the replies are parsed by their `CATEGORY:` / `EXPLANATION:` and `TECHNICAL:` / `EXPERT:` prefixes | none | no (tenant) |
| `studioapp.default_system` | the persona of a studio app's AI when the app has no system prompt of its own | none | no (tenant) |
| `rag.multi_query`, `rag.hyde` | retrieval query expansion | none | no (tenant) |
| `eval.judge.helpfulness`, `.relevance`, `.safety`, `.tone`, `.faithfulness`, `.accuracy` (+ `.accuracy.reference`, `.accuracy.reference_check`) | the LLM judges of the quality evaluators | `question`, `response`, `system_prompt`, `context`, `answer`, `reference`, `reference_block`, `reference_check` | yes |
| `eval.judge.case` | the golden-case grader of the command-line eval runner; that runner has no tenant, so it reads the shipped file | none | no |
| `lab.judge.score`, `lab.judge.same` | the Agent Lab graders | none | yes |
| `workflow.ai_classifier`, `workflow.ai_extractor`, `workflow.ai_extractor.repair` | the Question Classifier and Parameter Extractor nodes (the first two are security critical: they say the text is data) | `issues` (in `.repair`) | no (tenant) |
| `workflow.supervisor.route`, `workflow.supervisor.instructions` | the Supervisor node's routing request | `routes`, `instructions`, `input` | yes (the node's agent) |
| `workflow.params_inference`, `workflow.edit_redaction_note` | the execution-limits helper; the note that asks the generator to copy `[[REDACTED_n]]` back (security critical) | none | no (tenant) |

Not in the registry yet: the small framing lines around the data of the two summarizers, the labels of the research goal
(`Depth (pass as effort):`, `Seed URLs ...`), and the hand-built explanation templates of `LlmBackedTechnicalStrategy`.

**Word lists are keys too.** The tool-name lists that decide whether a turn may be replayed (a cheap answer escalated to
the primary model) and the phrases that mark a reply as low confidence were Java sets. They are now one entry per line
(`#` starts a comment, blank lines are skipped) in `detector.replay.read_only_prefixes`, `detector.replay.mutating_words`,
`detector.replay.never_replay_tools` and `detector.escalation.low_confidence_markers`. The first three are security
critical, so a change needs `acknowledgeRisk`. They are a floor in one direction: the shipped mutating words and
never-replay tools are always applied in addition to an override (an override can add, never remove, so a tenant edit
cannot make a mutating tool replayable); an override of the read-only prefixes replaces the list (removing a prefix only
makes the check stricter, adding one needs the acknowledgement). `app.chat.escalation.low-confidence-markers`, when set,
is a deprecated alias that seeds the platform default of the registry list (an override of the list beats it). The failover gate (`FailoverReplayGate`) is built by hand and reads the
shipped lists only. English ships today; for another language add `prompts/<locale>/<key>.md` holding only the entries: the shipped entries
of every locale are applied together with the default locale's (which a tenant or agent override replaces).

## Migrating a call site

For each hardcoded prompt: move the text to a resource file under the key from the audit, replace the literal with
a lookup, keep the *gating* in code, and add a golden test. Keep any `static final String` that tests use as a
resource-backed constant (`PromptResolver.defaultsOnly().catalog().defaultText(key)`); never send a constant from a
turn path, resolve it so an override applies. Directives built per turn from tool lists choose the key in code
(`toolTrustKey(tools, compact)`) and resolve it through `ResolvedDirectives`.

# Taint entry points: who authored the input of each turn

A chat turn starts where a `TurnContext` is built (`forEntry`, `atEntry`, `fromCurrentThread`, or its
constructor). If the author of the turn's input is not the agent's owner or a member of the owning tenant, the
entry must mark `turnContext.taint()` before calling `AgentChatService.chat`, so the taint gate (see
`app.agents.taint.mode` in [owasp-mapping.md](owasp-mapping.md)) treats the turn as having read untrusted content
from the first tool call. Unknown is untrusted: `SenderTrust.ofOwnerOrMember(null)` is `UNTRUSTED`.

The rule is enforced by `TurnEntryTrustDeclaredTest` (`core/app/src/test/java/com/agenticai/core/archrules/`): every
file that builds a `TurnContext` must seed the taint at each creation site or carry a
`// taint-entry: trusted - <reason>` comment, and must be named in the table below. A new entry that does neither
fails the build. The mode itself is unchanged: `observe` is still the base default, so until a posture sets
`enforce` the marks are recorded and audited, not blocking.

Mark labels are `inbound:<channel>`. They appear in the taint audit trail.

## Entries

Paths are under `core/<module>/src/main/java/com/agenticai/core/`. Lines are from the commit that introduced this
page and drift; the test, not the line numbers, is the contract.

| Entry (class) | Trust | Evidence |
|---|---|---|
| `AgentChatStreamResponder` (console chat, `POST /api/agents/{id}/chat`) | trusted | `agents-turn/agents/turn/AgentChatStreamResponder.java:160`; reached only after `requireAccess(RUNNER)` for an authenticated tenant member |
| `QuickTaskService` | trusted | `app/common/quicktask/QuickTaskService.java:453`; a signed-in member's own task text |
| `ApprovalContinuationLauncher` (turn after a human decided) | trusted | `agents-turn/agents/turn/approval/ApprovalContinuationLauncher.java:173`; the prompt is platform-written. The conversation's persisted taint still applies (`ConversationTaint`) |
| `ReplayTurnRunner` (admin replay of a recorded turn) | trusted | `agents-turn/agents/turn/replay/ReplayTurnRunner.java:165`; tools are simulated or held by the sandbox grant, never run for real |
| `AgentJobTurns`, job origin `API` or `SCHEDULE` | trusted | `agents-jobs/agents/job/AgentJobTurns.java:364,414` via `trustOf`; a member submitted the job, or the owner's own schedule fired |
| `AgentJobTurns`, job origin `EVENT` (event/webhook triggers, `AgentEventTriggerFiring`), `APPROVAL` (sentinel proposals), or unknown | untrusted (`inbound:job_<origin>`) | same lines; `AgentEventTriggerGoal` renders an outsider-authored payload into the goal; a sentinel proposal is derived from observed content |
| `A2aTaskService` (A2A peers) | untrusted (`inbound:a2a`) | `agents-interop/agents/a2a/A2aTaskService.java:179` |
| `OpenAiCompatCalls` (OpenAI-compatible endpoint) | untrusted (`inbound:openai_compat`) | `agents-interop/agents/openai/OpenAiCompatCalls.java:199`; the key is routinely embedded in an app used by end users |
| `AgUiRunController` (AG-UI) | untrusted (`inbound:ag_ui`) | `agents-interop/agents/agui/AgUiRunController.java:167` |
| `AgentNodeHandler`, `SupervisorNodeHandler`, `ChatStructuredCall` (workflow agent, supervisor and structured nodes) | untrusted (`inbound:workflow`) | `workflow/workflow/AgentNodeHandler.java:230,251`, `SupervisorNodeHandler.java:138`, `agents-turn/agents/turn/ChatStructuredCall.java:94`; the node input is whatever the trigger or an upstream node produced. Not split by trigger kind (a console draft run is also untrusted: an HTTP node upstream can fetch anything) |
| `LegacyChatEntryPoints` (deprecated `chat(ChatTurnRequest)`) | untrusted (`inbound:legacy`) | `agents-turn/agents/turn/LegacyChatEntryPoints.java:36`; caller unknown, no production caller |
| `DemoChatController`, `DemoShareController`, `DemoMagicService`, `DemoStudioService` (public demo sandbox) | untrusted (`inbound:demo_*`) | `agents-studio/agents/demo/DemoChatController.java:234`, `DemoShareController.java:239`, `magic/DemoMagicService.java:488`, `studio/DemoStudioService.java:650`; anonymous visitors |
| `AgentShareController` (public share page) | untrusted (`inbound:share`) | `agents-turn/agents/share/AgentShareController.java:286,403` (package B) |
| Omnichannel inbound: `TelegramUpdateService`, `MetaWhatsAppReplyFlow`, `WhatsAppQrInboundService`, `TwilioWebhookController`, `TwilioSmsWebhookController`, `VoiceRelayWebSocketHandler`, `SignalInboundService`, `MatrixEventService`, `EmailInboundPollingJob`, `MetaSocialWebhookController`, `DemoWhatsAppWebhookController`, `DemoTelegramWebhookController` | untrusted (`inbound:<channel>`) | `omnichannel/omnichannel/**` (package B) |
| Social: `SlackWebhookController`, `DiscordWebhookController`, `TeamsWebhookController`, `XDmPollingJob`, `ImessageUpdateService` | untrusted (`inbound:<channel>`) | `omnichannel-social/omnichannel/**` (package B) |
| `McpExposedToolInvoker`, `McpCallAgentToolCallback`, `DeveloperApiController` (MCP server, developer API) | untrusted (`inbound:mcp`, `inbound:developer_api`) | `mcpserver/mcpserver/**` (package B) |
| `VoiceTurnStream` (voice) | untrusted (`inbound:voice`) | `voice/voice/VoiceTurnStream.java:139` (package B) |
| `StudioAppInvokeService` (published studio apps) | untrusted (`inbound:studio_app`) | `app/common/studioapp/StudioAppInvokeService.java:164` (package B) |
| `SubAgentTurn` (delegated or specialist sub-turn) | internal, **open gap** | `agents-turn/agents/turn/SubAgentTurn.java:101`; not an external entry, but it builds a fresh `TurnTaint`, so a tainted delegating turn does not make its delegate's sink calls park. Needs the parent's taint plumbed through `ChatDelegateCall` |

## Not turn entries

`EdgeToolExecutionController` executes a tool for an edge client and does not start a turn. Tool results, retrieval
blocks and recalled memory taint a turn from inside the pipeline, not at the entry.

## Adding an entry

1. Decide trust. Trusted only if the author is the agent's owner or a member of the tenant, authenticated for this
   request, or an internal schedule running the owner's own prompt. Anything else, and anything you cannot establish,
   is untrusted.
2. Untrusted: `SenderTrust.UNTRUSTED.seed(turnContext.taint(), "inbound:<x>")` right after building the context.
3. Trusted: a `// taint-entry: trusted - <reason>` comment at the site.
4. Add a row here. `TurnEntryTrustDeclaredTest` checks 2 or 3 and 4.

# agents decomposition: chat import/export (`agents-chatport`)

Part of splitting `core/agents` (over 1,100 files) into modules of at most about 300 files each.
This page records the cut that moved chat import/export out of `agents`.

## What moved

| | Before | After |
|---|---|---|
| Module | `core/agents` | `core/agents-chatport` (artifactId `agents-chatport`) |
| Package | `com.agenticai.core.agents.chatport` | unchanged |
| Main classes | 13 | 21 (13 moved, `ChatImportParser` split into 9) |
| Tests | 8 | 8 moved, plus the test helper `InMemorySessionMemory` |

Nothing was renamed that is visible from outside the JVM: the REST paths
(`/api/chat-import/**`, `/api/agents/{agentId}/sessions/{sessionId}/export`,
`/api/agents/{agentId}/sessions/{sessionId}/transcript`), the Spring bean names
(`chatImportService`, `chatExportService`, `chatTranscriptService`, the three controllers),
the JPA entity `ImportedChat`, its table `imported_chat` and every `app.chat-import.*` /
`app.chat-export.*` property stay as they were.

The test helper `com.agenticai.core.agents.memory.InMemorySessionMemory` (a map-backed
`SessionMemoryService`) moved from the `agents` test tree to the `agents-chatport` test tree,
because the chatport tests were its only callers. Its package did not change.

## Dependency direction

`agents-chatport` depends on `agents`. It uses:

- `Agent`, `AgentService` (resolve the agent and check the caller's access),
- `memory.SessionMemoryService`, `CallerSessionIds`, `ChatTurn`, `MessageRole`, `TranscriptView`
  (read a session for export and the transcript view),
- `memory.importer.MemoryImportService`, `MemoryImportParser`, `MemoryImportDtos` (turn imported
  chats into memory candidates).

Nothing in `agents`, or anywhere else in `core/` outside `core/app` tests, references
`com.agenticai.core.agents.chatport` (checked with `grep -rn agents.chatport core`), so there was
no reference to invert. The only outside mention is the JSONB mapping allowlist entry in
`core/app/src/test/java/com/agenticai/core/jsonb/JsonbRoundTripTest.java`, which names the class
by string and resolves on the `app` classpath.

Its other direct dependencies are leaf or kernel modules `agents` already depends on: `json`,
`security`, `audit`, `sharing`, `tenancy`.

## Shapes

Only the API shape (`core/app`) depends on `agents-chatport`. The module is three REST
controllers plus the services behind them. It has no `@Scheduled`, `@EventListener` or `@Async`
work, and the jobs (`app-jobs`) and worker (`app-worker`) shapes exclude controllers from their
component scan. Before the move the services were also created, unused, in those two shapes.
The same choice was made for `brief`.

## `ChatImportParser` split

`ChatImportParser` was 993 lines. It is now an entry point with the public result types, plus one
class per concern:

| Class | Lines | Role |
|---|---|---|
| `ChatImportParser` | ~170 | entry point `parse(...)`, `Limits`, `ParsedChat`, `ParsedMessage`, `ParseResult`, constants |
| `ChatImportReader` | ~370 | zip container (entry, inflated-size and ratio caps), streamed JSON, the per-conversation tree budget, the `vegaduta.chat-export` envelope |
| `ChatImportCollector` | ~105 | per-parse state: format dispatch, de-duplication by key, selection, conversation and per-import character caps, warnings |
| `ChatImportFormat` | ~45 | interface plus the ordered registry `REGISTERED` |
| `ChatGptImportFormat` | ~210 | ChatGPT `mapping` / `current_node` tree walk |
| `ClaudeImportFormat` | ~110 | Claude `chat_messages` |
| `VegaDutaImportFormat` | ~95 | this platform's own export (`ChatExportRenderer` JSON) |
| `ChatImportMessageBuilder` | ~80 | per-chat message, character and truncation caps |
| `ChatImportFields` | ~85 | field reading and cleaning: ids, titles, attachment names, timestamps |

The format order is part of the contract and is unchanged. A conversation object with `mapping`
is ChatGPT. Otherwise, one with `chat_messages` is Claude. Otherwise, one with a `messages` array
is a VegaDuta export. To add a format, implement `ChatImportFormat` and add it to `REGISTERED` in
the right position.

The split is meant to preserve behaviour, and the existing `ChatImportParserTest` (32 cases)
passes unchanged. `ChatImportParser.epochSeconds` and `parseIso` remain as package-private
delegates because that test calls them.

No other moved file is over 665 lines. The largest is `ChatImportService`, at 483 lines.

## Verifying

```
cd core
mvn -o -pl agents-chatport -am -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='com.agenticai.core.agents.chatport.*Test' package
bash ../scripts/ci/dockerfile-poms.sh
bash ../scripts/ci/bean-name-collision.sh
```

Per AGENTS.md rule 1, run one Maven build at a time.

# Error Prone baseline and NullAway pilot (core/)

Status as of 2026-09-29. Owner: whoever next touches `core/pom.xml`'s
`static-analysis` profile.

## How to run it

```bash
cd core
mvn -P static-analysis -DskipTests compile        # add -o offline, -T 1C for speed
```

- **Error Prone 2.50.0** runs as a javac plugin. Every check is demoted to a
  warning (`-XepAllErrorsAsWarnings`), generated code is skipped
  (`-XepDisableWarningsInGeneratedCode`, `-XepExcludedPaths` for
  `target/generated-*sources`). General findings never fail the build.
- **NullAway 0.14.2** with **JSpecify 1.0** annotations runs with
  `-XepOpt:NullAway:OnlyNullMarked=true` at **ERROR** severity. It checks only
  packages carrying `@NullMarked` in `package-info.java`; unmarked code is not
  checked at all. A nullness error in a marked package fails the compile.
- Test sources get Error Prone but not NullAway (test classes share the pilot
  packages' names and would otherwise inherit `@NullMarked`).
- The default build (no profile) is unchanged. The only default-build changes
  are the `org.jspecify:jspecify` compile dependency on crypto, storage and
  providers, and `combine.children="append"` on `core/app`'s
  `annotationProcessorPaths` (so the profile's processors are not replaced by
  app's Lombok entry).
- Needs a JDK 21+ to run (Error Prone's minimum since 2.43).
  `-XDaddTypeAnnotationsToSymbol=true` is passed because Error Prone requires
  it on JDK 21 (a no-op on 22+). Verified locally on JDK 24 (whole reactor)
  and on Temurin 21.0.12 (crypto, storage, providers, voice and their
  dependencies, in the `maven:3.9-eclipse-temurin-21` image).
- The compiler is forked (`<fork>true</fork>`) with the `-J--add-exports` /
  `-J--add-opens` flags from the Error Prone install docs, so no
  `.mvn/jvm.config` is needed.
- CI: the `core` job's "static analysis" step runs the command above and
  writes a per-check warning count to the job summary. It blocks only on the
  NullAway pilot.

## Baseline (before fixes)

Full reactor, clean compile of `src/main` only, JDK 24: **654 unique warnings
from 38 checks in 400 files** (2,539 source files compiled, 40 modules). Build
time 6m18s wall clock with `-T 1C` and a forked compiler.

After the fixes below: **639 warnings, 37 checks** (5m29s).

### By check

| Count | Check | Nature |
|---:|---|---|
| 237 | MissingSummary | Javadoc style |
| 75 | InvalidLink | Javadoc `{@link}` to nothing (stale docs) |
| 55 | StringCaseLocaleUsage | `toLowerCase()`/`toUpperCase()` without `Locale` (Turkish-i class of bug) |
| 54 | UnusedVariable | dead fields/params/locals; see triage |
| 52 | NotJavadoc | `/**` comment in a non-Javadoc position |
| 43 | StringSplitter | `String.split` trailing-empty-string surprise |
| 20 | FutureReturnValueIgnored | see triage; 6 fixed |
| 17 | ReferenceEquality | see triage; 3 fixed, 14 intentional identity checks |
| 13 | ArrayRecordComponent | array in a record (equals/hashCode are identity) |
| 12 | EscapedEntity | Javadoc |
| 11 | InvalidParam | Javadoc `@param` naming a missing parameter |
| 9 | LongDoubleConversion | implicit long to double |
| 6 | LoopOverCharArray | style |
| 6 | EnumOrdinal | ordering by `ordinal()` (TrustRung, ChatTtftRecorder, AgentGuardrailService) |
| 5 | RefactorSwitch | style |
| 5 | JavaDurationGetSecondsToToSeconds | style; 2 fixed in passing |
| 3 | OperatorPrecedence | `a && b \|\| c` without parentheses; reviewed, all do what they intend |
| 3 | BooleanLiteral | style |
| 3 | CanonicalDuration | style |
| 3 | BoxedPrimitiveEquality | `Boolean == Boolean.FALSE`; all 3 fixed |
| 2 | removal | `JacksonMcpJsonMapper`, Spring Security `permissionsPolicy(...)` are marked for removal |
| 2 each | InlineFormatString, InvalidInlineTag, JavaUtilDate | |
| 1 each | StatementSwitchToExpressionSwitch, AvoidCommonTypeNames, TypeParameterUnusedInFormals, UnusedMethod, ModifiedButNotUsed, UnrecognisedJavadocTag, IdentityHashMapUsage, ModifyCollectionInEnhancedForLoop, ReturnFromVoid, InvalidBlockTag, UnnecessaryParentheses, ExposedPrivateType, AddressSelection, DuplicateBranches | |

About 60% of the findings are Javadoc hygiene (MissingSummary, InvalidLink,
NotJavadoc, EscapedEntity, InvalidParam and the other Javadoc checks).

### By module

agents 240, omnichannel 46, workflow 39, chat 29, tenancy 28, app 27, brief 24,
imagegen 23, providers 20, quality 17, knowledge 16, platform 15, mcp 13,
voice 13, guardrails 11, billing 10, speech 8, google 8, security 7, secrets 6,
sdlc 6, documentgen 6, sarvam 6, payments 5, eval 4, elevenlabs 4, admin 3,
eventbus 3, rl 3, mcpserver 3, approval 2, codeintel 2, app-config 1, crypto 1,
storage 1, sharing 1, devices 1, graphmemory 1, templates 1.

## Triage of the high-severity checks

### Real bugs, fixed

1. **Realtime voice dropped microphone audio without logging it**
   (FutureReturnValueIgnored x5, `voice/realtime/OpenAiRealtimeProvider`,
   `GeminiLiveRealtimeProvider`). `java.net.http.WebSocket` allows one
   outstanding send. A `sendText` issued before the previous send completes
   returns a future that fails with `IllegalStateException`. Each mic chunk
   was sent that way and the future was ignored, so any chunk that arrived
   while the previous one was still being written was lost with no log line.
   `interrupt()` racing an audio send was lost the same way. Fix: a new
   `WebSocketSendQueue` chains each send behind the previous one, logs
   failures, and drops frames past a bound of 64 if the upstream socket
   stalls. Covered by `WebSocketSendQueueTest`.
2. **Boxed Boolean compared by reference** (BoxedPrimitiveEquality x3):
   `AgentRelevanceSelector:229` and `QueryComplexityClassifier:168`
   (`... .orElse(null) == Boolean.FALSE`), and `StudioAppService:118`
   (`req.aiEnabled() != Boolean.TRUE`). These only work while every `Boolean`
   comes from `Boolean.valueOf`, which is true today and nothing enforces.
   Replaced with `Boolean.FALSE.equals(...)` / `!Boolean.TRUE.equals(...)`.
   Behaviour is unchanged for canonical values.
3. **Browser-use reaper failures disappeared without a log**
   (`BrowserUseSessionManager.sweepExpiredSessions`). The sweep was
   `executor.submit(...)` with the Future dropped, so an exception in the
   sweep went nowhere while expired Chromium sessions piled up. It is now
   `execute(...)` with a catch-and-warn.
4. **Dead code** removed in passing: an unused `model` local in
   `GeminiLiveRealtimeProvider.open`. The setup frame computes the model
   itself.

### Reviewed and left alone (false positives or intentional)

- **ReferenceEquality (14 remaining)**: `cause == current` cause-chain loop
  guards (AgentChatService, AgentController, McpConnectionDiagnoser,
  LlmMetricsAdvisor, LlmModelSupport, ProviderErrorTranslator x5), sentinel
  objects (`STREAM_DONE` in A2aJsonRpcController, `ByteRange.UNSATISFIABLE`
  in VideoGenerationController), and "is this still the live client"
  identity (DynamicMcpConnectionManager). All mean identity on purpose.
- **FutureReturnValueIgnored (14 remaining)**: fire-and-forget
  `CompletableFuture.runAsync` bodies that already catch and log
  (LlmQualityJudgeService, SessionSummaryService, Meta/Twilio webhook
  controllers, WorkflowTriggerPortImpl, HostedChainFailover), and
  `whenComplete` callbacks whose returned future is irrelevant (A2A task
  service/controller, AwsBedrockChatModel, FimCompletionService).
  AgentJobExecutor's `scheduleWithFixedDelay` heartbeat catches per job, so
  one bad job cannot kill the schedule. BrowserUse `@PreDestroy` is followed
  by `awaitTermination`.
- **ModifyCollectionInEnhancedForLoop** (A2aTaskStore): the map is a
  `ConcurrentHashMap`, where removal during iteration is safe.
- **UnusedVariable, security-looking hits**: `McpRemoteUrlGuard` and
  `McpStdioCommandGuard` `exact` params only disambiguate a private
  constructor. `OpenApiToolSourceService.resolvePrefix(excludingId)` is
  documented as reserved.

### Worth a human decision (not changed here)

- `tenancy/demo/DemoSessionController` injects `DemoSessionRateLimiter` and
  never calls it. `DemoSessionService` has an unread
  `defaultMaxLiveSessionsPerIp`. `DemoSessionService.create` says the per-IP
  limits were removed upstream on purpose (commit d489b60e), but the
  controller's class Javadoc still lists "per-IP creation rate" and "a per-IP
  live-sandbox cap" as the controls on this unauthenticated endpoint. Either
  restore the limits or correct the Javadoc and drop the dead injection.
- `StringCaseLocaleUsage` (55): a mechanical `Locale.ROOT` sweep is cheap and
  prevents the Turkish-locale casing bug in header, enum and ID comparisons.
- `EnumOrdinal` in `brief/TrustRung`: ordering by `ordinal()` breaks silently
  if a constant is reordered. An explicit rank field is safer.
- `ArrayRecordComponent` (13), including `crypto/WrappedDataKey`: record
  `equals`/`hashCode` compare these arrays by identity.
- `removal` (2): `JacksonMcpJsonMapper` (MCP SDK) and Spring Security
  `permissionsPolicy(...)` will break on the next major upgrade.

## NullAway pilot

Marked `@NullMarked` (enforced at ERROR):

| Package | Files | Findings fixed |
|---|---:|---|
| `com.agenticai.core.crypto` | 5 | `EncryptionService.previousKeySpec` is null outside a key-rotation window, now `@Nullable`; the `@Value` constructor inputs are `@Nullable` (`StringUtils.hasText` already guards them). |
| `com.agenticai.core.storage` | 6 | `FileStorageException(String, @Nullable Throwable)` (6 call sites pass `null`); `closeQuietly(@Nullable FileChannel)`. |
| `com.agenticai.core.providers.modality` (the TTS/STT/video SPI) | 32 | SPI contract made explicit: `getUnavailabilityReason()` is `@Nullable` (null means available), and the optional `model` / `languageCode` / `voiceOrSpeaker` overrides are `@Nullable`. `SidecarWhisperProvider`'s optional health `RestClient` and health timestamp are `@Nullable`, and the probe takes the client as a checked local. `OpenAIWhisperVideoProvider` no longer passes a possibly-null reason into an exception message. `TextModelProviderFactory.toProviderName` is `@Nullable`. |

Vendor modules that implement the modality SPI (sarvam, elevenlabs) are not
marked, so NullAway does not check them. They compile unchanged.

**Adding a package:** create `package-info.java` with `@NullMarked`, run the
profile, and fix what it reports. Prefer small leaf packages whose API other
modules consume (security, speech, secrets), then the rest of `providers`.
When most of core is marked, drop `OnlyNullMarked` for
`AnnotatedPackages=com.agenticai.core`.

## Suggested next steps

1. Promote a few zero-count, high-signal checks to ERROR in the profile, for
   example `-Xep:BoxedPrimitiveEquality:ERROR` and
   `-Xep:ModifyCollectionInEnhancedForLoop:ERROR`. Promote others, such as
   `FutureReturnValueIgnored`, after suppressing the reviewed intentional
   cases with `@SuppressWarnings` and a reason.
2. Turn off the Javadoc checks in this profile
   (`-Xep:MissingSummary:OFF`, ...) or clean them per module, so real
   findings are not buried.
3. Run `-XepPatchChecks:StringCaseLocaleUsage` once, review the diff, and
   commit it.

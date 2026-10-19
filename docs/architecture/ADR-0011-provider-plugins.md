# ADR-0011: Vendor providers as build-time Spring Boot starters behind a small core SPI

| | |
|---|---|
| **Status** | Proposed. Phases 0–2 done, and boundary rules (a), (b) and (c) fully enforced as of 2026-10-10. On 2026-10-16 the vendor agent tools moved out of `core/agents` into their vendor modules (the phase 4 tool-callback step), leaving 0 kernel to vendor Maven edges. The same day the `agentspi` ports became their own module (`core/agentspi`), and four of the five vendor modules (google, payments, imagegen, elevenlabs) now depend on it instead of `agents`; only `sarvam` still needs `agents` (one class). Phase 3 and the rest of phases 4–7 not started. See sections 12 to 15. |
| **Date** | 2026-09-29 |
| **Deciders** | Platform Engineering (maintainers) |
| **Related** | [ADR-0001 Modular monolith](../platform-documentation/decisions/adr-0001-modular-monolith.html), [ADR-0003 Spring AI dynamic clients](../platform-documentation/decisions/adr-0003-spring-ai-dynamic-clients.html), [ADR-0009 Untrusted code sandboxing](../platform-documentation/decisions/adr-0009-untrusted-code-sandboxing.html), `docs/architecture/GENERIC-PROVIDER-CAPABILITY-REGISTRY-2026-08-09.md`, `docs/architecture/MULTI-PROVIDER-MODALITY-ARCHITECTURE.md` |

> Numbering: ADR-0001 to ADR-0010 are published as HTML under
> `docs/platform-documentation/decisions/`. This is the next free number. It is
> Markdown until it is accepted and moved into the HTML docs set.

## 1. Question

A maintainer asked: *"Can we separate core modules by provider, so that
dependent services are built separately and injected into core as JARs?"* In
practice that means LLM providers, speech and voice vendors, channels,
payments, storage and embeddings become separate artifacts. Core would pick
them up either at build time or from a drop-in directory at runtime.

Short answer: **yes, at build time, in stages.** Runtime drop-in JARs are
possible later for third-party providers only. The blocker is not Maven. It is
a set of closed Java enums and a few inverted dependencies in the kernel, and
those have to be opened up first. Section 3 gives the measurements.

## 2. Context: what the code looks like today

All numbers come from the `core/*/pom.xml` files and `core/*/src/main` as of
2026-09-29. They were gathered by parsing the files, without running Maven.

### 2.1 Module graph, with vendor adapters marked

The reactor has 41 Maven modules. `mcp-data` and `mcp-stdio-proxy` are not
Maven modules. All of them share the group `com.agenticai` and the Java
package root `com.agenticai.core`. The graph below lists compile-scope edges
between sibling modules. Vendor modules are in **bold**.

| Module | Main src files | Depends on (sibling modules) |
|---|---:|---|
| security, audit, crypto, speech, eval, app-config | 27 / 24 / 5 / 3 / 7 / 4 | (none) |
| storage | 6 | security |
| billing (contains **Razorpay** and **PhonePe** gateways) | 61 | security |
| secrets | 13 | crypto, audit |
| admin | 33 | audit, security, crypto |
| tenancy | 169 | admin, billing, security, storage |
| **elevenlabs** | 29 | crypto, security, tenancy, storage, speech |
| **sarvam** | 27 | crypto, security, tenancy, storage, speech |
| **google** (Workspace OAuth) | 18 | crypto, security, tenancy |
| knowledge (contains the embeddings, OCR and sidecar clients) | 92 | tenancy, crypto, storage |
| **providers** (the kernel registry) | 122 | crypto, **sarvam**, knowledge, tenancy, **elevenlabs** |
| chat (contains 16 LLM `*ChatClientFactory` classes) | 94 | providers, security, audit, tenancy |
| **imagegen** | 31 | providers, storage, tenancy |
| **payments** (UPI mandates) | 30 | security, crypto, billing, approval, eventbus, tenancy, audit |
| agents | 619 | 24 modules, including **google, sarvam, imagegen, elevenlabs, payments** |
| voice | 31 | security, tenancy, agents, **sarvam, elevenlabs**, speech, storage, providers, crypto |
| omnichannel (9 channels: WhatsApp, Messenger, Instagram, Slack, Discord, X, Telegram, Email, Teams, plus Twilio) | 228 | agents, approval, chat, audit, guardrails, crypto, eventbus, security, tenancy, devices, speech |
| workflow | 117 | 15 modules, including voice, google, omnichannel |
| app / app-worker / app-jobs | 102 / 1 / 1 | app has 35 siblings. app-worker and app-jobs already leave out `payments` and `brief`. |

Two observations matter most.

1. **The dependency arrow points the wrong way in one kernel module.**
   `providers` is the generic registry, and it depends on the vendors `sarvam`
   and `elevenlabs`. The reason is that three vendor adapters live inside the
   kernel:
   - `ElevenLabsTextToSpeechProvider`
   - `SarvamTextToSpeechProvider`
   - `SarvamSpeechToTextProvider`

   All three are in `providers/.../modality/`. `TextToSpeechProviderConfiguration`
   wires them in by constructor, by name:
   `factory.register(elevenLabsProvider); factory.register(sarvamProvider);`.
   `ProviderCredentialResolver` also has a hard `if (providerName == ProviderName.SARVAM)`
   branch that goes to `SarvamCredentialService`.
2. **Composition by POM already works.** `app-worker` and `app-jobs` are
   separate deployables that include a different set of modules (no `payments`,
   no `brief`). That is exactly the "distribution" idea proposed here. It is
   just not formalised yet.

### 2.2 Seams that already exist, and how open each one is

| Capability | SPI today | How implementations are found | Closed set? | Verdict |
|---|---|---|---|---|
| LLM chat | `chat.AgentChatClientFactory` (`supportedProvider(): ProviderName`, `create(...)`) | Spring `List<AgentChatClientFactory>` goes into a `ChatClientRegistry` `Map<ProviderName, ...>` | **Yes.** `enum ProviderName` has 15 values. There are 209 `ProviderName.X` references across 78 main files (agents 15, chat 27, providers 20, imagegen 6, platform 4, other modules 6). `enum TextModelProvider` has 11 values plus a mapping switch in `agents/ChatClientResolver` (12 arms). `LiveProviderModelFetcher` has a per-vendor switch. | Good bean pattern, but the key is closed |
| LLM validation | `providers.validation.ProviderValidator`, with 13 validators | Spring list | Yes (`ProviderName`) | Same as above |
| TTS / STT / video-to-text | `providers.modality.TextToSpeechProvider` / `SpeechToTextProvider` / `VideoToTextProvider` | **Manual** `register()` calls from a `@Configuration` that names each adapter class | Yes. `TtsModelProvider` has 2 values, `VoiceModelProvider` 3, `VideoModelProvider` 5 | Adapters sit in the kernel. Needs inversion. |
| Generic capability credentials | `providers.capability.ProviderCredentialValidator` (`providerKey(): String`, `capabilities(): List<String>`) | Spring list | **No.** String keys. One implementation so far (`agents/decision/JevCredentialValidator`). | **This is the right model.** Generalise it. |
| File storage | `storage.FileStorageService` | `@ConditionalOnProperty(app.storage.type = s3 (default) \| local)` | No | **Already the target pattern** |
| Embeddings | `knowledge.embedding.EmbeddingClient` | `@ConditionalOnProperty(app.embeddings.provider = openai-compatible)`, with sidecar as the fallback | No | Nearly the target pattern |
| Billing gateway | `billing.gateway.PaymentGatewayClient` (`gatewayName(): String`) | Two `@Component`s (Razorpay, PhonePe), both always loaded | No | Easy to extract |
| Channel outbound media | `omnichannel.media.ChannelMediaSender`, with 6 implementations | Spring list | **Yes.** `enum ChannelType` has 9 values, plus about 15 `case CHANNEL ->` arms inside omnichannel. | Split per channel only after the enum is opened |
| Agent tools that call a vendor | `agents/tools/{audio,sarvam,google,imagegen,payments,musicgen,videogen}` | Component scan | n/a | **The vendor tool callbacks live in `agents`** (34 files import vendor packages). They must move out with their vendor. |

### 2.3 Where else the vendor code reaches

- **Who imports each vendor from outside its own module** (main sources only):
  - `elevenlabs` is imported by agents (10 files), voice (1), providers (1) and app (1).
  - `sarvam` is imported by agents (13), providers (5), voice (1) and app (1).
  - `google` is imported by agents (8), workflow (1) and app (1).
  - `imagegen` is imported by agents (8) and app (1).
  - `payments` is imported by agents (5).

  Most kernel callers use concrete service classes, not interfaces. For
  `elevenlabs` those are `ElevenLabsClient` (12 imports),
  `ElevenLabsGeneratedAudioService` (9), `ElevenLabsVoiceService` (6) and
  `ElevenLabsApiException` (6).
- **Database.** All 290 Flyway scripts live in `core/app/src/main/resources/db/migration/{controlplane (100), tenant (191)}`.
  Version numbers run up to V365, in **one namespace shared by both directories**.
  Tables that belong to a single vendor include:
  - Sarvam: V60, V61, V62, V63, V65
  - ElevenLabs: V89
  - Google: V28, V215
  - Channels: Twilio V4 and V29, Meta V25 and V30, Slack V31, Discord V32, X V33, Telegram V46, Email V47, Teams V240
  - Payments: V345, V346

  Vendor modules also own JPA entities: elevenlabs 4, sarvam 3, google 1, imagegen 2, payments 4, omnichannel 22.

  Every enum above is stored with `@Enumerated(EnumType.STRING)`. No database
  CHECK constraint lists provider or channel names; the only CHECK found is
  `V253__tenant_sso_scim`. So **switching a column from enum to string is a
  change in Java only, with no schema change.**
- **Spring wiring.** `CoreApplication`, `WorkerApplication` and `JobsApplication`
  all sit in `com.agenticai.core` and use `@SpringBootApplication` (or an
  explicit `@ComponentScan("com.agenticai.core")`). That means **every module is
  component-scanned**, and no module declares `AutoConfiguration.imports`.
  There are 42 `@ConditionalOn*` annotations, spread thinly across modules.
  JPA entities and repositories are found through the default
  auto-configuration package. There is no `@EntityScan`, `packagesToScan` or
  `@EnableJpaRepositories`.
- **Web UI.**
  - Provider lists are hard-coded in `web/app/components/ProvidersPanel.tsx`, `web/app/ai-models/page.tsx` and `web/app/platform-admin/llm-providers/page.tsx`.
  - Vendor names appear in many files under `web/app`: whatsapp 185, sarvam 83, elevenlabs 58, razorpay 42.
  - A backend-only plugin would therefore get no UI unless the UI is driven by metadata the plugin serves.

## 3. Findings

1. **Can a vendor move into its own JAR today? Yes for four of them.**
   `elevenlabs`, `sarvam`, `google` and `imagegen` are already separate
   artifacts. Each depends only on kernel utilities (crypto, security, tenancy,
   storage, speech), and no other vendor depends on them.
2. **Can a vendor be left out of the build today? No, for any of them.** Three
   things require them all:
   - `providers` compiles against sarvam and elevenlabs.
   - `agents` compiles against all five vendor modules.
   - `TextToSpeechProviderConfiguration` fails at startup unless both TTS adapters exist.
3. **The 16 LLM chat factories cannot be moved as they are.** They sit in
   `chat` next to the machinery every call needs:
   - `LlmModelSupport`, `LlmHttpClientFactory`, `ResilientToolCallingManagerFactory`
   - `LlmCallMetricsService`, `LlmCallLogService`, `LlmPricingCatalog`
   - 7 `*ProviderCachingStrategy` classes

   Together that is about 4,550 lines. They are keyed by the closed
   `ProviderName`, and `TextModelProvider` duplicates that set. Most of them
   (OpenAI, OpenRouter, Grok, Mistral, DeepSeek, Groq, Meta, Ollama, Sarvam,
   Azure) are thin OpenAI-compatible configurations. Moving the closed enums to
   string keys is the main refactor, and it is **the one that cannot be
   avoided**.
4. **Channels are one 228-file module.** It contains shared session, CRM,
   handoff and trigger code next to per-channel sub-packages. Splitting it
   works, but only after `ChannelType` is opened and the cross-channel
   dispatchers (`WhatsAppDispatchService`, `SmsDispatchService`,
   `VoiceCallService`) are behind a port. It is the most expensive split and
   gives the least value early on.
5. **Storage and embeddings already follow the proposed pattern.** Billing
   gateways are one refactor away from it.

## 4. Decision drivers

- **D1. Chat hot-path latency.** An LLM call must stay an in-process call. No
  extra network hop, no extra serialisation.
- **D2. OSS adoptability.** A self-hoster must be able to build or run a slim
  distribution, for example without the Indian-market vendors (Sarvam,
  Razorpay, PhonePe, UPI), and add their own provider without forking.
- **D3. Nothing breaks.** Today's full set stays the default image. No database
  migration is needed for the refactor. No config key changes. No API changes.
- **D4. Operational safety.** Keep to the rules already in force:
  - one Flyway namespace
  - single-revision deploys (ADR-0008)
  - `APP_ENCRYPTION_KEY` parity
  - no arbitrary third-party code inside the process that holds tenant keys
- **D5. Small team.** Every phase must ship value on its own and be
  reversible.

## 5. Options considered

### A. Keep the status quo: one reactor, component-scan everything

- **Pros:** costs nothing.
- **Cons:** a vendor cannot be dropped. Adding a provider touches 5 to 8 files
  across `providers`, `chat`, `agents` and `web`. The boundary rot shown in
  2.1 grows.
- **Rejected:** it fails D2.

### B. Build-time composition: Spring Boot auto-configured "provider starters"

Each vendor becomes a module containing:
- a `@AutoConfiguration` class
- a `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` file ([Spring Boot: creating your own auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html))
- a gate using `@ConditionalOnProperty` and/or `@ConditionalOnClass`

A *distribution* is then a POM (or Maven profile) that lists which starters go
on the classpath.

This is the model Spring AI itself adopted for 1.0. It moved from one
monolithic auto-configure artifact to `spring-ai-autoconfigure-model-{model}`
plus `spring-ai-starter-model-{model}` per vendor, to "minimize the impact of
different versions of dependent libraries conflicting"
([Spring AI 1.0 snapshot changes](https://spring.io/blog/2025/03/25/spring-ai-update-to-snapshots/)).

- **Pros:**
  - Zero runtime cost and plain in-process calls (D1).
  - Standard Spring tooling.
  - Works with AOT, AppCDS and GraalVM native image, because the classpath is
    fixed at build time ([Spring Boot CDS](https://docs.spring.io/spring-boot/reference/packaging/class-data-sharing.html)).
  - Vendor dependency trees (the AWS SDK for Bedrock, google-genai, Twilio)
    leave slim builds.
- **Cons:**
  - Changing the vendor set needs a rebuild.
  - The kernel enums still have to be opened.
  - The component-scan overlap has to be solved (see Risk R2).

### C. Runtime drop-in directory

- **C1. `PropertiesLauncher` with `loader.path`.** This is still supported in
  Boot 3.2 and later: `Main-Class: org.springframework.boot.loader.launch.PropertiesLauncher`,
  configured via `LOADER_PATH`, `-Dloader.path` or `Loader-Path`, with
  directories scanned for jars
  ([Boot docs: PropertiesLauncher](https://docs.spring.io/spring-boot/specification/executable-jar/property-launcher.html)).
  Jars in `providers/` go onto the **same** classloader, so their
  `AutoConfiguration.imports` are picked up exactly as in Option B.
  - Pros: B-starters work unchanged.
  - Cons: no isolation. Dependency conflicts surface as `NoSuchMethodError`.
    The plugin can read every bean and secret. AOT and native images are
    impossible.
- **C2. Java `ServiceLoader` SPI plus a `providers/` directory, Keycloak-style.**
  Keycloak's `Provider`/`ProviderFactory`/`Spi` model is the reference
  ([Keycloak Server Developer Guide: Service Provider Interfaces](https://www.keycloak.org/docs/latest/server_development/#_providers)).
  Keycloak still loads providers into the server classpath, with a
  build/augment step (`kc.sh build`). Its providers are trusted code.
- **C3. Isolated classloader per plugin.**
  - Kafka Connect `plugin.path`: a child-first `PluginClassLoader` per plugin,
    the thread-context classloader switched during plugin calls, plus known
    ServiceLoader leak pitfalls ([KIP-146](https://cwiki.apache.org/confluence/display/KAFKA/KIP-146+-+Classloading+Isolation+in+Connect), [KAFKA-18211](https://issues.apache.org/jira/browse/KAFKA-18211)).
  - Trino: SPI plugins, each in its own classloader, with only the `trino-spi` package shared ([Trino SPI overview](https://trino.io/docs/current/develop/spi-overview.html)).
  - Elasticsearch: similar ([plugin authors guide](https://www.elastic.co/guide/en/elasticsearch/plugins/current/plugin-authors.html)).

  - Pros: dependency isolation.
  - Cons: this is how you build a platform product like Trino. The SPI must be
    tiny and free of Spring. Spring beans cannot cross the boundary naturally.
    Classloaders leak on unload.
- **C4. PF4J with pf4j-spring.** Mature and easy to use ([pf4j](https://pf4j.org/), [pf4j-spring](https://github.com/pf4j/pf4j-spring)).
  But each plugin gets its own `ApplicationContext`, and host beans are not
  injectable without a hand-wired parent context
  ([pf4j#244](https://github.com/pf4j/pf4j/issues/244)). Our adapters need
  `tenancy`, `crypto`, `storage`, `LlmModelSupport` and metrics, so every
  plugin would need a hand-wired parent context. Web controllers inside
  plugins are also awkward ([pf4j-spring#34](https://github.com/pf4j/pf4j-spring/issues/34)).
- **C5. OSGi.** Real modularity and hot swap, but a second component model next
  to Spring. Spring dropped first-class OSGi support years ago, and Spring AI
  and Boot are not OSGi bundles. **Not viable.**
- **C6. JPMS `ModuleLayer`.** A child layer per plugin is technically clean
  ([ModuleLayer javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ModuleLayer.html)).
  But the whole core runs on the classpath (unnamed module), and so do Spring
  Boot fat jars. It is **not viable** without modularising everything.

**Shared cons of all C options:**
- **Security.** A runtime jar is arbitrary code inside the process that holds
  decrypted BYOK keys, `APP_ENCRYPTION_KEY` and tenant database connections.
  The Security Manager is gone: it was deprecated in JDK 17 by JEP 411 and
  permanently disabled in JDK 24 by [JEP 486](https://openjdk.org/jeps/486).
  In-process sandboxing is therefore not possible. The only controls are
  signature or checksum allow-lists and code review.
- **Performance tooling.** Runtime-loaded jars defeat GraalVM's closed-world
  native image ([Native Image basics](https://www.graalvm.org/latest/reference-manual/native-image/basics/))
  and Spring AOT ([Boot AOT](https://docs.spring.io/spring-boot/reference/packaging/aot.html)).
- **Versioning.** They make SPI compatibility a hard public contract.

### D. Out-of-process providers (HTTP or gRPC microservice per vendor)

- **Pros:** full isolation. The provider can be written in any language.
- **Cons:**
  - Every LLM token stream and tool-call round trip would cross an extra hop.
    Serialisation, a second timeout and retry layer, and a second failure
    domain all sit on the chat hot path, which runs many round trips per turn
    in the tool loop (fails D1).
  - Streaming and back-pressure would have to be re-implemented.
  - Per-vendor services multiply the deploy surface that ADR-0001 rejected.
- **Where D is right** is where we already use it, for heavy or untrusted
  runtimes:
  - `services/` sidecar (Whisper, OCR, embeddings)
  - the tool sandbox (ADR-0009)
  - `mcp-stdio-proxy` / MCP runners

  Keep D for that class of workload only.

### E. Enforce boundaries only (Spring Modulith or ArchUnit), without changing packaging

- Spring Modulith treats each direct sub-package of the `@SpringBootApplication`
  package as an application module. It verifies that modules form no cycles,
  that nothing outside a module touches its `internal` packages, and that
  declared `allowedDependencies` are respected. It supports `@NamedInterface`
  for exposing an SPI package ([Modulith fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html), [verification](https://docs.spring.io/spring-modulith/reference/verification.html)).
  Our layout (`com.agenticai.core.<module>`) fits this with no moves.
- ArchUnit expresses the same rules as plain JUnit tests with no runtime
  dependency ([ArchUnit user guide](https://www.archunit.org/userguide/html/000_Index.html)).
- **Pros:** cheap. Stops regressions immediately.
- **Cons:** does not by itself let anyone ship a slimmer build.
- **Adopted as Phase 1, not as the whole answer.**

## 6. Decision

Adopt **E, then B, then optionally C1**, and keep **D** for the heavy and
untrusted runtimes we already run out of process.

1. **Enforce the boundaries** with ArchUnit rules in the existing test
   phase. Spring Modulith's `verify()` is an optional later step; use the
   1.3.x line, which matches Boot 3.4, and check the version map before
   adding it.
2. **Extract a small `core-spi` module.** It contains only interfaces, records,
   exceptions and string-key registries. It depends on nothing except
   `spring-ai-model` types where unavoidable (`ChatClient`/`ChatModel`), plus
   JDK types.
3. **Make each vendor a provider starter.** Each one is auto-configured through
   `AutoConfiguration.imports` and gated by `@ConditionalOnProperty(prefix = "app.providers.<id>", name = "enabled", matchIfMissing = true)`
   and, where relevant, `@ConditionalOnClass`.
   A **distribution** is an `app` variant POM:
   - `app` stays the "full" distribution and keeps today's module set.
   - `app-oss-slim` is an example of a smaller set.
4. **Optional, later: a `providers/` directory** loaded via
   `PropertiesLauncher` / `LOADER_PATH` for **operator-trusted** third-party
   starters. It is off by default. It needs a SHA-256 allow-list and is
   documented as "runs with full platform privileges". It will never offer a
   marketplace of unreviewed jars.
5. **Do not do per-vendor microservices** for LLM, speech or channel adapters.

### 6.1 Target shape of `core-spi` (package `com.agenticai.spi`)

| SPI | Replaces or generalises | Key type |
|---|---|---|
| `ChatModelProvider` (`id()`, `displayName()`, `capabilities()`, `create(CredentialRef, ChatModelSpec, Map<String,Object> opts): ChatClient`) | `AgentChatClientFactory` plus `ProviderValidator` plus `LiveProviderModelFetcher` switch arms | `String providerId` (`"openai"`, `"sarvam"`, …) |
| `LlmCallSupport` (a port offered *to* providers: http client, retries, metrics, logging, pricing, tool-calling manager) | the concrete `chat` helpers each factory injects today | n/a (provided by the kernel) |
| `SpeechSynthesisProvider`, `SpeechRecognitionProvider`, `VideoTranscriptionProvider` | `TextToSpeechProvider` etc. | `String` |
| `EmbeddingProvider` | `knowledge.embedding.EmbeddingClient` | `String` |
| `FileStorage` | `storage.FileStorageService` (already correct; re-export it) | property `app.storage.type` |
| `PaymentGateway` | `billing.gateway.PaymentGatewayClient` (already keyed by `gatewayName()`) | `String` |
| `ChannelAdapter` (inbound verify and parse, outbound text and media, credential status) | `ChannelMediaSender` plus the per-channel services | `String channelId` |
| `CredentialValidator` | `providers.capability.ProviderCredentialValidator` (already String-keyed; this becomes the single pattern) | `String` |
| `AgentToolContributor` | vendor tool callbacks now in `agents/tools/{sarvam,google,audio,imagegen,payments}` | n/a |
| `ProviderDescriptor` (id, display name, capabilities, credential fields schema, icon, docs URL) | hard-coded `PROVIDERS` arrays in `web` | `String` |

Rules for the SPI:
- Semantic versioning with its own `spi.version`.
- `@since` on every type.
- Additive changes only within a major version, using default methods (the
  pattern `AgentChatClientFactory.create(..., providerOptions)` already follows).
- A `core-spi-tck` test-jar that every provider module runs as a contract test.

### 6.2 Opening the closed enums (the one large refactor)

- Replace uses of `ProviderName`, `TextModelProvider`, `TtsModelProvider`,
  `VoiceModelProvider`, `VideoModelProvider` and `ChannelType` in routing
  paths with `String` ids, resolved through registries built from
  `List<…Provider>` beans.
- Keep the enums for one release as `@Deprecated` **constant holders**
  (`ProviderName.OPENAI.id()`). They also back a JPA `AttributeConverter<String>`,
  so the stored values are **byte-identical**. That works because every
  column is `EnumType.STRING` and has no CHECK constraint, so no migration is
  needed.
- `PLATFORM_HOSTED` becomes a kernel-reserved id. The special cases stay in
  the kernel:
  - `ProviderCredentialResolver`'s Sarvam branch becomes a
    `CredentialSource` the Sarvam starter contributes.
  - `ChatClientResolver`'s mapping switch disappears.
- An unknown id loaded from the database, for example a provider missing from
  a slim distribution, must degrade to "provider not installed on this
  deployment" rather than throw. `TextToSpeechProviderFactory.getUnavailabilityReason`
  already has exactly this wording.

## 7. Consequences

**Positive**
- Adding a provider becomes one new module plus one line in a distribution
  POM. Today it takes 5 to 8 files across 4 modules plus the web UI.
- Slim OSS distributions become possible. For example, without Sarvam,
  ElevenLabs, Razorpay, PhonePe and UPI payments, or without the AWS or Google
  SDKs.
- Vendor dependency trees stop leaking into kernel modules.
- The chat hot path is unchanged: in-process, same objects, same pooling.

**Negative / costs**
- A large but mechanical refactor of enum call sites: about 209 `ProviderName`
  references across 78 files, plus the modality and channel enums.
- An SPI that is published has to be kept stable.
- The UI has to become metadata-driven (`GET /api/providers/descriptors`) for
  slim builds to look right. Until then, the UI offers providers that are
  missing and shows "not installed on this deployment".

## 8. Risks and mitigations

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Flyway: one global version namespace** across controlplane and tenant, up to V365. If vendor starters ship their own `db/migration/tenant/Vxxx`, versions will collide. If a slim distribution leaves out a vendor whose migrations were already applied, Flyway validation fails with "applied migration not resolved locally". | **Phase 1–3: vendor migrations stay in `app`.** Vendor tables are created even when the vendor is absent. They are empty and harmless, which keeps the schema identical for every distribution. For future third-party providers only: a separate Flyway instance per provider, with its own `table = flyway_history_<providerId>`, its own location `db/provider/<providerId>/tenant`, and run by `TenantSchemaMigrator` per tenant schema. That gives no shared namespace. **Never move an existing V-script**, because that changes checksums and location. |
| R2 | **Double registration.** Every class under `com.agenticai.core` is component-scanned. A starter in that package *and* listed in `AutoConfiguration.imports` would register twice, and conditions on scanned `@Component`s are evaluated in the wrong order. | Starters live in `com.agenticai.provider.<id>`, outside the scan root. The auto-config class `@Import`s its beans explicitly, or uses `@ComponentScan` on its own package. As a transitional alternative, add an `excludeFilters` pattern for `com.agenticai.core.provider..*`. An ArchUnit rule forbids `@Component` in starter packages. |
| R3 | **JPA entity and repository scanning.** Entities are found only under the auto-configuration package (`com.agenticai.core`). Starter entities in `com.agenticai.provider.*` would be invisible. `@EntityScan` *replaces* the defaults. | The starter auto-config uses `@AutoConfigurationPackage(basePackages = "com.agenticai.provider.<id>")`. That adds the package to `AutoConfigurationPackages`, which both entity scanning and Spring Data repository scanning read, without replacing anything. Order it `before = HibernateJpaAutoConfiguration`. Add a boot test per distribution. |
| R4 | **Bean-name collisions** between starters (two `restClient` beans, two `*Properties`). | Prefix every `@Bean` name with the provider id. Configuration properties go under `app.providers.<id>.*`. Existing keys (`app.storage.type`, `app.embeddings.provider`, current vendor keys) stay valid through `@DeprecatedConfigurationProperty` aliases for one release. |
| R5 | **The `agents` module keeps vendor tool callbacks** (34 files). | These move with the vendor as `AgentToolContributor` beans. The tool resolvers (`AgentMediaToolResolver`, `AgentIntegrationToolResolver`) iterate contributors instead of naming vendors. |
| R6 | A starter is missing but a tenant row references it (for example `agent.tts_provider = 'ELEVENLABS'`). | Section 6.2 degradation path, plus a startup report listing any referenced but uninstalled provider ids per tenant (read-only, logged). |
| R7 | Runtime `providers/` jars: arbitrary code with full privileges; classloader and dependency conflicts; AOT/CDS disabled. | Off by default. SHA-256 allow-list (`app.providers.allowed-sha256`). Refuse unsigned jars. Documented as trusted-operator-only. Official images never enable it. Anything untrusted goes out of process (D). |
| R8 | ADR-0008 single-revision deploys and `APP_ENCRYPTION_KEY` parity. | Unaffected. A distribution is still one image. Starters read secrets through the kernel `crypto`/`secrets` ports and never through their own key. |
| R9 | Hidden coupling found late, such as vendor classes used in `platform` or `workflow` or passed in DTOs. | Phase 1 ArchUnit rules make every remaining edge visible and failing (via a baseline file that only shrinks) before any code moves. |

## 9. Migration plan (non-breaking; default distribution = today's full set)

Effort is in engineer-days for one engineer who knows the codebase. It
includes tests, but not staging or production soak time.

| Phase | Scope | Exit criterion | Effort |
|---|---|---|---|
| **0. Baseline** | Record today's inter-module graph (this ADR). Add a CI script that diffs sibling `<dependency>` edges in `core/*/pom.xml`. | Graph checked in; CI prints the diff | 0.5 d |
| **1. Enforce boundaries** | ArchUnit test module (`core/architecture-tests`) with three rules: (a) kernel modules must not import `elevenlabs`, `sarvam`, `google`, `imagegen`, `payments`, `billing.gateway.Razorpay*`/`PhonePe*` or `omnichannel.<channel>`; (b) no cycles between `com.agenticai.core.*` slices; (c) a `FreezingArchRule` baseline of today's violations. Optional: Spring Modulith `verify()` with modules declared OPEN initially. | Baseline frozen; new violations fail CI | 2–3 d |
| **2. Invert the obvious edges** | Move `ElevenLabs/Sarvam*SpeechProvider` from `providers` into their vendor modules. Replace `TextToSpeechProviderConfiguration`'s named constructor wiring with `List<TextToSpeechProvider>` (same for STT and video). Replace the `ProviderCredentialResolver` Sarvam branch with a `CredentialSource` bean. Drop `providers → sarvam, elevenlabs` from the POM. Note: `providers → knowledge` (for `SidecarClient`) stays until `EmbeddingProvider` exists. | `providers` has no vendor dependency | 3–4 d |
| **3. `core-spi` plus opening the enums** | New `core/spi` (and `core/spi-tck`). Introduce String-keyed registries. Deprecated enum constant holders plus a JPA converter. Rewrite about 78 files that reference `ProviderName`, the `ChatClientResolver` mapping, the `LiveProviderModelFetcher` switch, and the modality enums. `LlmCallSupport` port extracted from `chat` helpers. | All kernel routing uses String ids; stored values unchanged; full test suite green; the boot-the-jar check passes | 10–15 d |
| **4. First starters** (ordered cleanest first) | See the per-module table below. Each: package `com.agenticai.provider.<id>`, `@AutoConfiguration`, imports file, `app.providers.<id>.enabled`, TCK test, and its tool callbacks moved out of `agents`. `app` depends on all starters, so it stays the full distribution. | Each starter can be removed from a scratch POM and `app` still boots | see table (about 20–30 d total) |
| **5. Distributions** | `core/distributions/{full (= current app), oss-slim}`, or Maven profiles. CI boots each against `deploy-postgres` (pgvector + AGE). Docker build arg selects the distribution. The official image = full. | Two images build and boot; full image byte-for-byte the same bean set as before (compare the actuator `/beans` diff) | 3–4 d |
| **6. Metadata-driven UI** | `GET /api/providers/descriptors` from `ProviderDescriptor` beans. `web` renders provider and channel pickers from it, and keep today's static list as a fallback. | Slim build hides absent providers | 5–8 d (web + web-admin) |
| **7. Optional runtime drop-in** | `PropertiesLauncher` main class in a `-pluggable` image variant, `LOADER_PATH=/opt/vegaduta/providers`, SHA-256 allow-list check in an `EnvironmentPostProcessor` (the existing `app-config/spring.factories` hook), docs, and a sample third-party starter in `examples/`. | Sample provider loads from the directory; unknown hash is refused at boot | 4–6 d |

### 9.1 Per-module extraction table

| Module / vendor | Kind | Blockers today | Target starter | Effort |
|---|---|---|---|---|
| storage (S3, Local) | FileStorage | none; already `@ConditionalOnProperty` | `provider-storage-s3`, `provider-storage-local` (optional; could stay in the kernel) | 1 d |
| knowledge embeddings (OpenAI-compatible; sidecar) | EmbeddingProvider | `providers → knowledge` edge; `SidecarClient` shared with OCR | `provider-embeddings-openai-compatible`; sidecar stays in the kernel (it is our own service) | 2 d |
| billing gateways (Razorpay, PhonePe) | PaymentGateway | both unconditional `@Component`s; webhook controller routing | `provider-billing-razorpay`, `provider-billing-phonepe` | 2–3 d |
| **elevenlabs** | Speech plus music | kernel adapter in `providers`; 10 agent tool files; 1 voice import | `provider-elevenlabs` | 3–4 d |
| **sarvam** | LLM plus speech plus translate | `ProviderName.SARVAM` branch in the credential resolver; chat factory in `chat`; 13 agent tool files; 5 `providers` imports | `provider-sarvam` | 4–5 d |
| **google** (Workspace) | Tools/integration | 8 agent tool files; `workflow` import | `provider-google-workspace` | 2–3 d |
| **imagegen** | Image and video generation | depends on `providers` internals (26 imports); `ProviderName` branches | `provider-imagegen-*` after Phase 3 (split per backend: Vertex, OpenAI, …) | 3–4 d |
| payments (UPI mandates) | Agent payments | agent tool callbacks; approval, eventbus, billing wiring | `provider-payments-upi` | 2–3 d |
| LLM chat factories (16) | ChatModelProvider | Phase 3 (`LlmCallSupport`, open ids). One generic `provider-llm-openai-compatible` can cover about 10 of them as configuration-only presets. | `provider-llm-{openai-compatible, anthropic, gemini, vertex, bedrock}` | 5–7 d after Phase 3 |
| omnichannel channels (9 plus Twilio) | ChannelAdapter | `ChannelType` enum; shared session, CRM and handoff; dispatch services; 22 entities; per-channel web pages | `provider-channel-<x>`. **Deferred**: do this last, one channel at a time, starting with Discord or Telegram, which have the fewest cross-links | 15–25 d total; not scheduled |
| voice, workflow, agents, chat, tenancy, … | Kernel | n/a | stay in the kernel | n/a |

## 10. Worked example: extracting ElevenLabs

ElevenLabs is the cleanest vendor. Its dependencies are kernel utilities
only, and its callers are one kernel adapter, 10 tool files in `agents` and one
import in `voice`.

### 10.1 Layout

```
core/
  spi/                                   # new; artifactId core-spi
    src/main/java/com/agenticai/spi/speech/SpeechSynthesisProvider.java
    src/main/java/com/agenticai/spi/ProviderDescriptor.java
    src/main/java/com/agenticai/spi/tools/AgentToolContributor.java
  spi-tck/                               # test-jar with abstract contract tests
  providers-elevenlabs/                  # renamed from core/elevenlabs; artifactId provider-elevenlabs
    pom.xml                              # depends on core-spi, crypto, security, tenancy, storage, speech (NOT agents/providers)
    src/main/java/com/agenticai/provider/elevenlabs/
      ElevenLabsAutoConfiguration.java
      ElevenLabsProperties.java
      ElevenLabsSpeechSynthesisProvider.java   # moved from providers/modality/ElevenLabsTextToSpeechProvider
      tools/ElevenLabsSpeakToolContributor.java # moved from agents/tools/audio/*ElevenLabs*
      internal/ElevenLabsClient.java, ...Service.java, ...Credential.java (entities) ...
    src/main/resources/META-INF/spring/
      org.springframework.boot.autoconfigure.AutoConfiguration.imports
    src/test/java/.../ElevenLabsAutoConfigurationTest.java
      ElevenLabsSpeechSynthesisContractTest.java  # extends spi-tck
  app/pom.xml                            # keeps <dependency>provider-elevenlabs</dependency> => full distribution unchanged
  app/src/main/resources/db/migration/tenant/V89__elevenlabs.sql   # STAYS HERE (R1)
```

### 10.2 SPI

```java
package com.agenticai.spi.speech;

/** @since spi 1.0 */
public interface SpeechSynthesisProvider {
    /** Stable id stored in agent.tts_provider, e.g. "ELEVENLABS" (kept upper-case for DB compatibility). */
    String id();
    byte[] synthesize(SynthesisRequest request) throws SpeechProviderException;
    Availability availability(TenantRef tenant);   // replaces isAvailable()/getUnavailabilityReason()
    default ProviderDescriptor descriptor() { return ProviderDescriptor.of(id(), id()); }
}
```

The kernel keeps a `SpeechSynthesisRegistry(List<SpeechSynthesisProvider>)`.
That replaces `TextToSpeechProviderFactory` plus the named
`TextToSpeechProviderConfiguration`.

### 10.3 Auto-configuration

```java
package com.agenticai.provider.elevenlabs;

@AutoConfiguration(before = HibernateJpaAutoConfiguration.class)
@AutoConfigurationPackage(basePackages = "com.agenticai.provider.elevenlabs") // entities + repositories, additive (R3)
@ConditionalOnProperty(prefix = "app.providers.elevenlabs", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ElevenLabsProperties.class)            // prefix app.providers.elevenlabs
@Import({ ElevenLabsClient.class, ElevenLabsCredentialService.class, ElevenLabsVoiceService.class,
          ElevenLabsGeneratedAudioService.class, GeneratedMusicService.class, VoiceQuotaService.class,
          ElevenLabsController.class, ElevenLabsVoiceController.class,
          ElevenLabsGeneratedAudioController.class, GeneratedMusicController.class })
public class ElevenLabsAutoConfiguration {

    @Bean("elevenlabsSpeechSynthesisProvider")
    SpeechSynthesisProvider elevenlabsSpeechSynthesisProvider(ElevenLabsClient client,
            ElevenLabsCredentialService creds, ElevenLabsVoiceService voices) {
        return new ElevenLabsSpeechSynthesisProvider(client, creds, voices);
    }

    @Bean("elevenlabsSpeakTools")
    AgentToolContributor elevenlabsSpeakTools(ElevenLabsSpeechSynthesisProvider tts, VoiceQuotaService quota) {
        return new ElevenLabsSpeakToolContributor(tts, quota);
    }
}
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.agenticai.provider.elevenlabs.ElevenLabsAutoConfiguration
```

Properties, with the old keys kept as deprecated aliases for one release:

```yaml
app:
  providers:
    elevenlabs:
      enabled: true          # default true => today's behaviour
      base-url: https://api.elevenlabs.io
      timeout: 30s
```

### 10.4 Tests

```java
class ElevenLabsAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ElevenLabsAutoConfiguration.class))
        .withBean(ElevenLabsCredentialRepository.class, () -> mock(ElevenLabsCredentialRepository.class))
        /* ...other kernel ports mocked... */;

    @Test void registersProviderByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(SpeechSynthesisProvider.class)
              .getBean(SpeechSynthesisProvider.class).extracting(SpeechSynthesisProvider::id).isEqualTo("ELEVENLABS"));
    }

    @Test void backsOffWhenDisabled() {
        runner.withPropertyValues("app.providers.elevenlabs.enabled=false")
              .run(ctx -> assertThat(ctx).doesNotHaveBean(SpeechSynthesisProvider.class));
    }
}

class ElevenLabsSpeechSynthesisContractTest extends SpeechSynthesisProviderTck { /* WireMock-backed */ }
```

Plus one **distribution boot test** in `app`: a `@SpringBootTest` against
`deploy-postgres` asserts the bean set is unchanged. A second test in
`oss-slim` asserts that
`agent.tts_provider = 'ELEVENLABS'` gives "not installed on this deployment"
and not a startup failure.

### 10.5 What stays hard for ElevenLabs

- `ElevenLabsGeneratedAudioService` and `GeneratedMusic` are used directly by
  `agents` demo code (`DemoMusicController`, `DemoMagicService`) and by
  `GeneratedAttachmentResolver`. Those need a generic
  `GeneratedMediaStore` port in the kernel, which is
  about a day of extra work (included in the 3–4 d estimate).
- The `elevenlabs` tables (V89) are created in every distribution. That is
  intentional (R1).

## 11. Summary

- Package vendors as auto-configured starters, and choose them per
  distribution at build time. Keep everything in-process.
- Reach that point in this order: enforce the boundaries first (Phase 1),
  invert the three misplaced adapters (Phase 2), then do the one unavoidable
  refactor, opening the closed provider and channel enums (Phase 3).
- A runtime `providers/` directory is an optional, trusted-operator feature.
  It is not an isolation boundary.
- Anything untrusted or heavy stays out of process, as it does today.

## 12. Status: phases 0–2 implemented

Written 2026-09-29 against the working tree. It reached the repository in the
initial public release (2026-10-02). The boundary work that took rules (a), (b)
and (c) from 728 violations to 0 landed on 2026-10-10 (see the table below and
section 13). The changes are non-breaking: same beans, same provider ids, same
config keys, no Flyway change, chat hot path untouched.

### Phase 0: module graph

- `core/tools/module-graph/gen_module_graph.py` (stdlib Python) parses
  `core/pom.xml` `<modules>` and every module POM, and writes
  [`core-module-graph.md`](core-module-graph.md): a table, a Mermaid graph and
  the kernel / vendor / distribution classification. Output is deterministic;
  `--check` exits 1 when the committed doc is stale (for CI).
- Before phase 2: 42 modules, 281 sibling edges, **10** kernel → vendor module
  edges, 0 vendor → vendor. After phase 2: **8** kernel → vendor edges
  (`providers → sarvam` and `providers → elevenlabs` are gone).

### Phase 1: boundary enforcement

- `com.tngtech.archunit:archunit-junit5` **1.4.1**, version pinned in
  `core/pom.xml` `dependencyManagement` (`archunit.version`), test scope in
  `core/app`.
- `core/app/src/test/java/com/agenticai/core/archrules/ModuleBoundaryRulesTest.java`
  imports all `com.agenticai.core` production classes on app's classpath and
  checks three `FreezingArchRule`s:
  - **(a)** kernel code must not depend on vendor adapter code. Vendor code is
    the `elevenlabs`, `sarvam`, `google`, `imagegen` and `payments` packages,
    `billing.gateway.Razorpay*` / `PhonePe*`, and
    `omnichannel.{discord,email,meta,slack,teams,telegram,twilio,x}`. Only
    classes directly in `com.agenticai.core` (the `@SpringBootApplication`
    classes and app-config's post-processors) are exempt.
  - **(b)** vendor units must not depend on each other.
  - **(c)** top-level `com.agenticai.core.*` packages are free of cycles.
  - Plus one strict (not frozen) rule: `providers..` must not depend on any
    vendor code (the phase 2 exit criterion).
- Baseline store: `core/app/src/test/resources/archunit_store`, configured in
  `core/app/src/test/resources/archunit.properties`
  (`allowStoreCreation=false`, `allowStoreUpdate=true`). New violations fail
  the build. Fixed ones drop out of the store on the next run, so the store
  only shrinks.
- Updating the store:
  - After fixing violations, run the test and commit the smaller store.
  - To accept a new violation deliberately (needs review), add
    `-Darchunit.freeze.refreeze=true`.
  - For a new or renamed rule (the rule description is the store key), add
    `-Darchunit.freeze.store.default.allowStoreCreation=true` once.
  - Always run through the reactor (`mvn -o -pl app -am test -Dtest=ModuleBoundaryRulesTest -Dsurefire.failIfNoSpecifiedTests=false`)
    so sibling modules are read from `target/classes` and not from stale jars.
  - `core/tools/module-graph/count_frozen_violations.py` prints the counts per rule.

| Rule | Baseline (before phase 2) | After phase 2 | After the 2026-10 boundary work |
|---|---:|---:|---:|
| (a) kernel → vendor | 766 | **718** | **0** |
| (b) vendor ↔ vendor (slice pairs; all were `channel-* → channel-twilio`) | 7 | 7 | **0** |
| (c) package cycles (`agents` / `tools` / `skills`) | 3 | 3 | **0** |
| **Total** | **776** | **728** | **0** |
| strict: `providers` → vendor | 48 (fails) | **0** (passes) | **0** (passes) |

All three stores are empty: any kernel → vendor or vendor → vendor dependency,
or any new cycle between top-level packages, now fails the build. Rule (c) was
cleared by moving `tools` and `skills` under `agents` (`agents.tools`,
`agents.skills`): the three packages already shared one Maven module because
of their mutual dependency, so the package tree now matches the module.

The kernel-owned SPIs that cleared rules (a) and (b):

- `agentspi` (agents module until 2026-10-16, now its own `agentspi` module, see
  section 15): agent tool sources, media/speak/music tools,
  generated artifacts, the agent wallet ledger, drive upload, image and music
  generation ports. Adapters live in `<vendor>.agenttools`.
- `speech.voicenote` / `speech.call` (speech module): WhatsApp voice notes and
  live-call TTS/STT plus the shared voice quota.
- `omnichannel.outbound`, `omnichannel.whatsapp`, `omnichannel.status`: per-channel
  text sends, WhatsApp providers, channel status/health, bulk-email ledger.
- `security.http.HttpStatusMapped`: vendor exceptions declare their own HTTP
  mapping for `GlobalExceptionHandler`.

### Phase 2: inverted adapters

- Moved `ElevenLabsTextToSpeechProvider` to `core/elevenlabs`
  (`com.agenticai.core.elevenlabs`). Moved `SarvamTextToSpeechProvider` and
  `SarvamSpeechToTextProvider` to `core/sarvam` (`com.agenticai.core.sarvam`).
  Their code is unchanged and they still implement the `providers.modality`
  SPIs. Their simple class names, and therefore their default bean names, are
  unchanged.
- `TextToSpeechProviderConfiguration` and `SpeechToTextProviderConfiguration`
  now inject `List<…Provider>` and register each bean under its own
  `getProvider()` id. `ModalityProviderRegistration` fails startup if two
  beans claim the same id. When a vendor is missing, lookups report the
  existing "not available on this deployment" reason instead of failing at
  startup.
- New SPI `providers.ExternalProviderCredentialSource`
  (`providerName()`, `resolveActiveApiKey()`, `currentCredential()`), with the
  implementation `sarvam.SarvamProviderCredentialSource`. It replaces:
  - the `if (providerName == ProviderName.SARVAM)` branch in
    `ProviderCredentialResolver`;
  - two Sarvam couplings that section 2.1 missed: the Sarvam merge in
    `ProviderService.listAll()`, and `ProviderResponse.fromSarvam`, whose
    mapping moved verbatim into the Sarvam source.
- POMs: removed `providers → sarvam` and `providers → elevenlabs`, and added
  `sarvam → providers` and `elevenlabs → providers`. `app`, `app-worker` and
  `app-jobs` already declare both vendor modules, so every distribution has
  the same beans as before.
- Tests:
  - `ModalityProviderConfigurationTest` (providers)
  - `SarvamProviderPluginsTest` (sarvam)
  - `ElevenLabsTextToSpeechProviderTest` (elevenlabs)
  - updated `ProviderCredentialResolverTest` and `ProviderServiceTest`
  - `ProviderPluginDiscoveryTest` (app): a classpath scan asserting the full
    distribution discovers exactly the same TTS {ElevenLabs, Sarvam}, STT
    {SidecarWhisper, Sarvam}, video and credential-source components.

### Verification

- `mvn -o -q -T 1C test-compile` passes for the whole reactor.
- `mvn -o -pl providers,elevenlabs,sarvam,voice,speech,agents,app -am test`
  ran 5,755 tests across 38 modules. The only failure was a wrong expectation
  in the new `ProviderPluginDiscoveryTest`: `OpenAIWhisperVideoProvider` is
  `@ConditionalOnProperty` and so absent from a condition-evaluating scan.
  After fixing that expectation, the `archrules` tests pass (10/10).
- **Not done (no record that it was run for this change; see section 13, task 1):** the jar boot check against `deploy/postgres`. It was skipped
  because the C: drive had 9.8 GB free, below the 12 GB threshold. Before
  shipping a core image, run the boot-the-jar check and diff actuator
  `/beans` for `*TextToSpeechProvider`, `*SpeechToTextProvider` and
  `sarvamProviderCredentialSource`.

## 13. Progress against the migration plan (as of 2026-10-14)

Checked against the repository on 2026-10-14: the module graph, the ArchUnit
stores, and a search for the artifacts each phase promises. Nothing here was
re-run; the build and the architecture tests were not executed for this update.

| Phase | State | Evidence |
|---|---|---|
| 0. Baseline | Done | `core/tools/module-graph/gen_module_graph.py`; generated `core-module-graph.md` (45 modules, 315 sibling edges, 8 kernel to vendor module edges, 0 vendor to vendor) |
| 1. Enforce boundaries | Done, beyond the plan | `ModuleBoundaryRulesTest` with three frozen rules and one strict rule. All three stores under `core/app/src/test/resources/archunit_store` are empty, so any new violation fails the build |
| 2. Invert the obvious edges | Done | `providers` has no vendor dependency; ElevenLabs and Sarvam speech adapters live in their own modules |
| (unplanned) Kernel-owned ports | Done 2026-10-10 | `agentspi`, `speech.voicenote`, `speech.call`, `omnichannel.outbound`, `omnichannel.whatsapp`, `omnichannel.status`, `security.http.HttpStatusMapped`; `tools` and `skills` moved under `agents` to clear the cycles |
| 3. `core-spi` and open enums | Not started | No `core/spi` or `core/spi-tck`; `ProviderName` and `ChannelType` are still closed enums |
| 4. Provider starters | Partly started (2026-10-16) | Vendor agent tool callbacks moved out of `agents` into their vendor modules (section 14); `agentspi` is its own module and only `sarvam` still depends on `agents` (section 15). Still no `AutoConfiguration.imports` files and no `app.providers.<id>.enabled` switches |
| 5. Distributions | Not started | `app`, `app-jobs` and `app-worker` each list every vendor module; no slim variant |
| 6. Metadata-driven UI | Not started | No provider descriptor endpoint |
| 7. Runtime drop-in | Not started | Optional by design |

### What "8 kernel to vendor module edges" meant (superseded by section 14: now 0)

On 2026-10-14 the module graph still listed eight Maven edges from kernel modules to vendor
modules: `agents` to `elevenlabs`, `google`, `imagegen`, `payments` and
`sarvam`; `voice` to `elevenlabs` and `sarvam`; `workflow` to `google`. At the
code level the kernel no longer depends on vendor classes (rule (a) is at 0).
The edges remain because the vendor adapters are still wired in at build time.
They go away when phases 3 to 5 make each vendor an optional starter. Removing
them earlier would only move code around without making any vendor removable.

### Next tasks, in order

1. **Close the phase 2 verification gap.** Run `scripts/ci/boot-check.sh`, which
   already boots the real jar against the project Postgres image, then diff
   actuator `/beans` for `*TextToSpeechProvider`, `*SpeechToTextProvider` and
   `sarvamProviderCredentialSource` against the previous build. The script is not
   yet wired into the Makefile or CI. About half a day. Do this before the next
   core image ships.
2. **Decide whether to accept this ADR.** It is still "Proposed". Phase 3 costs
   about 10 to 15 engineer-days and touches about 78 files, and no vendor is
   removable until it is done. Accept it, or stop at the boundary enforcement
   already in place, which delivers most of the protection for none of the risk.
3. **Phase 3, if accepted.** Add `core/spi` and `core/spi-tck`. Move the
   closed enums to string-keyed registries, keep the stored values unchanged,
   and extract the `LlmCallSupport` port. Benchmark at 32 users on the kind
   stage afterwards, because provider routing is on the chat turn path.
4. **Phase 4, cleanest first.** Storage, embeddings, then the billing
   gateways (Razorpay, PhonePe), then ElevenLabs. Each starter must be
   removable from a scratch POM with `app` still booting. Channels stay last
   and are not scheduled.
5. **Phase 5.** Add the `oss-slim` distribution only once at least three
   starters exist. Compare the full image's bean set with the current one.
6. **Keep the docs honest.** Re-run `gen_module_graph.py --check` in CI and
   update this section when a phase moves.

## 14. Status update (2026-10-16): vendor agent tools out of `agents`

This is the "tool callbacks moved out of `agents`" part of phase 4 (risk R5),
done ahead of phase 3 because it needed no new SPI: the kernel ports already
existed in `agentspi` (section 12). It is behaviour-preserving: same packages,
same class names and therefore the same default bean names, same config keys,
no Flyway change, chat hot path untouched.

### What moved

Each vendor's `com.agenticai.core.<vendor>.agenttools` package moved, with its
tests, from `core/agents` into the vendor's own module. One commit per vendor.

| Vendor module | Main files moved | Tests moved |
|---|---:|---|
| `elevenlabs` | 13 (speak and music capability providers, `GenerateMusicToolCallback`, quota services) | 3 `agenttools` tests, `agents.demo.DemoMusicControllerTest` |
| `sarvam` | 12 (translate, transliterate, transcribe, language-id tools; speak capability providers; `AudioGenerationQuotaService`) | `SarvamLanguageToolSourceTest` |
| `google` | 10 (Drive, Gmail, Calendar, Sheets, YouTube tools; `GoogleWorkspaceToolSource`; `GoogleDriveFileUploader`) | 2 `agenttools` tests |
| `imagegen` | 8 (image and video tools and tool sources, `OrchestratorImageGenerator`, `GeminiHostedImageModel`, `ImageGenGeneratedVideoReader`) | 3 `agenttools` tests, `agents.appbuilder.AppBuilderImageControllerTest` |
| `payments` | 6 (`Mandate*` tools, `PaymentMandateToolSource`, `PaymentsAgentWalletLedger`) | 2 `agenttools` tests, `agents.tools.MandatePaymentToolVisibilityTest` |

`core/agents` has no `com.agenticai.core.<vendor>` package left (main files:
1,165 before, 1,116 after).

### POM changes

- Each of the five vendor modules now depends on `agents` (vendor -> kernel
  SPI). `agents` no longer depends on any vendor module.
- `voice -> sarvam`, `voice -> elevenlabs` and `workflow -> google` are now
  test scope: their main code already used only kernel ports
  (`speech.call`, `agentspi.DriveFileUploader`); only
  `VoiceCallControllerTest` and `GoogleDriveOutputServiceTest` need the real
  adapters.
- `omnichannel` gained a test-scope `sarvam` dependency
  (`WhatsAppVoiceNoteServiceTest`), which it used to get through `agents`.
- `app-jobs` and `app-worker` now declare `payments` directly; they used to get
  it only through `agents`. `app` already did. All three distributions keep the
  same module set and therefore the same beans.
- Module graph: **0** kernel -> vendor compile edges (was 8). The vendor modules
  are still always on the distribution classpath, so no vendor is removable yet.

### Verification done

- Full reactor `-DskipTests package` (main and test sources compiled) from `core/`.
- Moved tests, run in their new modules, plus `VoiceCallControllerTest`,
  `GoogleDriveOutputServiceTest` and `WhatsAppVoiceNoteServiceTest`: all pass.
- All `app` `archrules` tests (`ModuleBoundaryRulesTest`, `StructureRulesTest`,
  `ProviderPluginDiscoveryTest`, `UniqueBeanNamesTest`, ...): pass, ArchUnit
  store unchanged.
- `scripts/ci/bean-name-collision.sh`, `dockerfile-poms.sh`,
  `entity-name-lint.sh`, `spring-constructor-lint.sh`,
  `gen_module_graph.py --check`: pass.
- Full test suites of the five vendor modules (376 tests): pass. The `agents`
  suite: 4,702 tests, one failure that is already present on the base commit and
  unrelated to this move (`AgentEntityMappingTest.repositoryHqlStillResolvesAgainstTheMetamodel`
  expects 3 `@Query` methods on `AgentRepository`, which has 4).
- **Not done:** the boot-the-jar check (`scripts/ci/boot-check.sh`) with an actuator `/beans` diff.
  The bean set should be identical because only module membership changed, but
  that has not been observed on a running jar.

### What remains

1. **A smaller SPI for vendors.** (Done for four of five vendors, see section 15.)
   The vendor modules now depend on all of
   `agents` to reach `agentspi`. The target in section 6.1 is that a starter
   depends on `core-spi` only. `agentspi` itself is almost free of `agents`
   types (it needs only Spring AI `ToolCallback` and
   `security.http.HttpStatusMapped`) and can become its own module. Four adapter
   classes still use `agents` internals (`agents.Agent`,
   `agents.capability.*`, and `guardrails.ToolConfirmationDescriber`) and need
   ports first.
2. **Starters (rest of phase 4).** `com.agenticai.provider.<id>` packages,
   `AutoConfiguration.imports`, `app.providers.<id>.enabled`, TCK tests. The
   agent tool beans are still `@Component`s in the scan root.
3. **Phase 3** (open the enums, `core-spi`, `spi-tck`) and **phases 5–7** are
   unchanged from section 13.

## 15. Status update (2026-10-16): `agentspi` is its own module

Follow-up to section 14, item 1. Behaviour-preserving: the 15 files of package
`com.agenticai.core.agentspi` moved unchanged from `core/agents` to a new module
`core/agentspi` (same package, same types; they are interfaces, records and
exceptions, so no bean, entity, table, REST path or config key changed).

- `agentspi` depends on `security` (for `security.http.HttpStatusMapped`,
  implemented by `AgentWalletLedger.WalletLedgerException`) and on Spring AI's
  `spring-ai-model` (for `ToolCallback`). Nothing else. It never depends on
  `agents`.
- `agents` depends on `agentspi`.
- `google`, `payments`, `imagegen` and `elevenlabs` now depend on `agentspi`
  for their main code; `agents` is test scope only in each (the moved tests
  `GoogleBookingToolsTest`, `MandatePaymentToolVisibilityTest`,
  `AppBuilderImageControllerTest` and `DemoMusicControllerTest` exercise kernel
  classes). `google` also declares `guardrails` directly
  (`GoogleCalendarChangeEventToolCallback` implements
  `guardrails.ToolConfirmationDescriber`); it used to arrive through `agents`.
- `sarvam` still depends on `agents` at compile scope. The remaining coupling
  is one class, `sarvam.agenttools.SarvamToolsCapability`, which implements
  `agents.capability.AgentCapability`, returns
  `agents.capability.CapabilityAvailability` and reads and writes
  `agents.Agent#sarvamEnabled`. Moving `AgentCapability` behind a port (it takes
  the JPA entity `Agent`) is the next step for that edge.
- `workflow` keeps its `agents` dependency: it is a kernel module that uses the
  agent kernel widely (`AgentChatService`, `AgentService`, versioning, importer,
  tool-call log and others), not only `agentspi.DriveFileUploader`.
- The distributions (`app`, `app-jobs`, `app-worker`) declare `agents`
  directly and get `agentspi` through it, so their module set and bean set are
  unchanged apart from the new jar.

Module graph: 49 modules, vendor -> `agents` compile edges went from 5 to 1
(`sarvam`). Verified: full reactor `-DskipTests package`, the unit tests of
`agentspi`, `google`, `payments`, `imagegen`, `elevenlabs`, `sarvam`, `workflow`
and `agents`, and the `app` architecture tests (ArchUnit store unchanged). Not
verified on a booted jar.

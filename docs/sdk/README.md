# SDKs and dependencies

Four ways to use VegaDūta from your own code. They solve different problems; pick by what
your code is.

| You are writing | Use | Lives in | Status |
|---|---|---|---|
| A Java application with its own agents or tools (Spring AI, or any framework) and you want governance: tool risk classification, human approval, budgets, idempotent effects, guardrails, hooks, audit, telemetry | **[Java Agent SDK](java-agent-sdk.md)** (`io.github.cd-lc:vegaduta-agent-*`) | `sdk/java/` | `0.1.1`, first Maven Central release (tag `sdk-v0.1.1`); `sdk-v0.1.0` exists as a GitHub Release only - its Central upload failed and the coordinate is retired |
| A Python application with its own agents or tools (LangChain, Pydantic AI, a hand-rolled loop) and you want the same governance layer | **[Python Agent SDK](python-agent-sdk.md)** (`vegaduta-agent`) | `sdk/python-agent/` | `0.1.0`, not yet released; zero runtime dependencies, stdlib only; PyPI publication pending |
| A TypeScript or Node program that calls a running VegaDūta platform (create agents, chat, run workflows) | **[TypeScript client](clients.md)** (`vegaduta-sdk`) | `sdk/typescript/` | generated from the OpenAPI document; `0.1.0`, not published (the name is not yet reserved on npm) |
| A Python program that calls a running VegaDūta platform | **[Python client](clients.md)** (`vegaduta_sdk`) | `sdk/python/` | generated from the OpenAPI document; standard library only; `0.1.0`, not published (the name is not yet reserved on PyPI) |

The platform itself is not a dependency: you run it (see [Installation](../installation.md)) and
talk to it over its API, MCP server, A2A or AG-UI endpoints. The Agent SDKs are the
artifacts designed to be embedded *inside* someone else's application.

## What is in each Agent SDK

- **Java** (`sdk/java`, its own Maven reactor): `agent-api` (framework-free governance contracts),
  `agent-core`, `agent-jdbc`, `agent-testkit`, `agent-scanner` (risk-map scanner CLI),
  `agent-spring-ai`, `agent-spring-boot-starter` and `agent-bom`. Java 17.
- **Python** (`sdk/python-agent`, package `vegaduta_agent`): the same contract surface as the Java
  SDK — `@governed`, risk labels, the dispatch gate, approval (four-eyes, signed receipts), budget,
  effect journal, guardrails, hooks (webhook transport is an injected port), audit and observers —
  plus the Java `@Experimental` set: kill switch (`HaltSwitch`), tiered autonomy, narrowing mandate
  chains, tamper-evident signed audit with evidence export, SQLite stores, tool-shape pinning,
  governed memory, cost attribution, and policy replay/shadow. Async tools and LangChain/OpenAI
  adapters included. Python 3.10+, no runtime dependencies.

## How the pieces relate

```
your Java app ──(Spring AI tools)──► vegaduta-agent-spring-boot-starter ──► vegaduta-agent-core/api
your Python app ──(@governed tools)─► vegaduta-agent (vegaduta_agent.Governance)
                                     (both govern tools in-process; no platform required)

your TS/Python app ──(HTTPS)──► VegaDūta platform API  (sdk/typescript, sdk/python)

IDE / browser / phone ──► plugins and apps (see Plugins, apps and mobile) ──► VegaDūta platform
```

## Versioning and support

- The Java Agent SDK follows semantic versioning from `1.0.0`; binary compatibility of
  `vegaduta-agent-api` and `-core` is meant to be enforced by japicmp, which is configured but
  **skipped** (`japicmp.skip=true` in `sdk/java/pom.xml`) until a released baseline exists on Maven
  Central. Pre-1.0 releases may change anything marked `@Experimental`.
- The generated clients track the platform's OpenAPI document; regenerate them with
  `node sdk/generate.mjs` after an API change (CI fails when they are stale).
- All four are Apache-2.0 (`license` in each package manifest).

## Runnable examples

- [Refund agent, with and without the SDK](refund-agent-example.md) —
  `examples/sdk/refund-agent/`, Python and Java. Two programs replay the same
  scripted support-agent turns over a fake shop; no model or API keys needed.
  The fastest way to see what governance does: a prompt-injected refund waits
  for a human, a retried credit replays once, a runaway loop hits the budget,
  and every decision lands in the audit trail.
- [Java Agent SDK example](java-agent-sdk-example.md) —
  `examples/sdk/governed-tools/`, a live Spring Boot + Spring AI (Ollama) app.

import { INTEGRATIONS } from "../integrations/integrations-data";
import { AI_TOPICS } from "../ai/ai-data";
import { PLATFORM_TOPICS } from "../platform/platform-data";
import { TUTORIALS } from "../tutorials/tutorials-data";
import { COMPETITORS } from "../compare/compare-data";
import { GLOSSARY } from "../glossary/glossary-data";
import { FACTS_VERIFIED_ON, PLATFORM_FACTS as F } from "../lib/platform-facts";
import type { HubPage } from "../lib/seo-hub";
import { siteUrl } from "../lib/site-url";
import { academyUrl, hasAcademy } from "../lib/academyUrl";
import { isOssSite } from "../lib/siteMode";
import { CAPABILITIES, STACK, STANDARDS, TIERS, LICENSE_NAME } from "../components/oss-landing/ossSite";
import { REPO_URL, docPageHref, docsHref, hasDocsSite } from "../lib/siteLinks";

// ---------------------------------------------------------------------------
// /llms.txt — generated, not hand-written (GEO pass, 2026-09-19).
//
// This replaces public/llms.txt. The hand-written file had drifted into
// contradicting both the product and itself: "14 providers" and "13 providers"
// thirty lines apart, "13 node types" (there are 14), "35+ tools" (the ledger
// says 40+), "WhatsApp is on paid plans" (free since 2026-09-12), "workflow
// automation on Temporal" in the present tense (opt-in, off by default), and
// one integration page missing from the list entirely. None of those was a
// decision; each was a number nobody remembered to change in a second place.
//
// So: every count comes from lib/platform-facts.ts, and every page list is
// built from the same data modules the pages and sitemap.ts render from. A new
// hub page, comparison or glossary term appears here without touching this
// file, and a recounted fact changes here when it changes there.
//
// Keep expectations honest: as of 2026 there is no evidence any major AI
// engine reads llms.txt at answer time (Google has said it does not, and in
// log studies well under 1% of AI-bot requests touch it). It costs nothing and
// some agent tooling does fetch it, so it is kept ACCURATE — it is not where
// GEO effort should go. See docs/plans/GEO-STRATEGY-2026-09-19.md.
//
// Rendered per request (SE-05/SE-67): the origin and the site mode come from the
// pod environment, so a prebuilt image cannot bake localhost into it. In the
// default oss site mode an open-source variant is served (no plans, prices or
// sales pages); NEXT_PUBLIC_SITE_MODE=classic serves the commercial text below.
// ---------------------------------------------------------------------------

export const dynamic = "force-dynamic";

function hubLines(BASE: string, base: string, pages: HubPage[]): string {
  return pages.map((p) => `- [${p.name}](${BASE}${base}/${p.slug}): ${p.cardBlurb}`).join("\n");
}

function buildOss(BASE: string): string {
  const link = (href: string) => (href.startsWith("/") ? `${BASE}${href}` : href);
  return `# VegaDūta

> VegaDūta is an open-source (${LICENSE_NAME}) multi-tenant agentic AI platform you run yourself: agents, tools, workflows with human approval steps, retrieval, channels, guardrails and audit logs. Source: ${REPO_URL}

## What it is

${CAPABILITIES.map((c) => `- ${c.title}: ${c.body}`).join("\n")}

## Stack

${STACK.map((x) => `- ${x.name}: ${x.detail}`).join("\n")}

## Standards

${STANDARDS.map((x) => `- ${x.name}: ${x.detail}`).join("\n")}

## Install

${TIERS.map((t) => `- ${t.name}: ${t.body} \`${t.cmd}\``).join("\n")}

## Developers

- [Documentation](${link(hasDocsSite() ? docsHref() : "/docs")}): install, configure, extend and operate the platform
- [Java Agent SDK](${docPageHref("javaSdk")}): embeddable governance for AI agents in Java applications; 0.1.0-SNAPSHOT, built from source
- [SDKs and dependencies](${docPageHref("sdkOverview")}): the Java SDK and the generated TypeScript and Python API clients
- [Plugins, apps and mobile](${docPageHref("pluginsAndApps")}): editor, browser and mobile surfaces with their real status
- [API reference](${BASE}/api-reference): the REST surface for integrators
- [Changelog](${BASE}/changelog): what changed, newest first
- [Source code](${REPO_URL}): ${LICENSE_NAME}, on GitHub
${hasAcademy() ? `- [Academy](${academyUrl()}/): free structured curriculum\n` : ""}
## Reference

- [Integrations index](${BASE}/integrations): channels and services agents work with
${hubLines(BASE, "/integrations", INTEGRATIONS)}
- [AI capabilities index](${BASE}/ai)
${hubLines(BASE, "/ai", AI_TOPICS)}
- [Platform index](${BASE}/platform)
${hubLines(BASE, "/platform", PLATFORM_TOPICS)}
- [Glossary](${BASE}/glossary): ${GLOSSARY.length} agentic AI terms with primary sources
- [About](${BASE}/about)

## Legal

- [Privacy policy](${BASE}/privacy)
- [Terms of service](${BASE}/terms)
`;
}

function build(BASE: string): string {
  const plans = F.plans.map((p) => `${p.name} $${p.priceUsd}/month`).join(", ");
  return `# VegaDūta

> VegaDūta (वेगदूत, "swift messenger") is a multi-tenant agentic AI platform built for India. Teams build AI agents that talk to their customers over WhatsApp, Telegram, voice and other channels, governed by human approval steps, guardrails, per-user spend budgets and audit logs — and pay in rupees over UPI.

Facts on this page were counted against the platform's source code on ${FACTS_VERIFIED_ON}.

Key facts:

- ${F.channels} messaging channels: WhatsApp (Meta Cloud API or Twilio), Telegram, Slack, Discord, Instagram, Facebook Messenger, X, email, and voice calls
- ${F.freeChannels.join(", ")} are available on the Free plan; the other channels need your own third-party credentials, not a paid plan
- The interface ships in ${F.interfaceLocales} languages, including all ${F.indianLanguages} scheduled Indian languages
- Model Context Protocol (MCP) in both directions: connect STDIO/SSE/streamable-HTTP MCP servers to agents, or call your agents and workflows as MCP tools (${F.mcpServerTools} tools) via scoped API keys
- Bring-your-own-key support for ${F.byokProviders} LLM providers (Anthropic, OpenAI, Azure OpenAI, Google Vertex, Gemini, AWS Bedrock, Meta, Mistral, DeepSeek, Groq, Grok, OpenRouter, Ollama, Sarvam), plus a platform-hosted model pool
- ${F.builtInToolsFloor}+ built-in agent tools
- Visual workflow builder with ${F.workflowNodeTypes} node types, 11 trigger channels and human-approval nodes; a durable-execution engine (Temporal) can be switched on per deployment
- ${F.guardrailTypes} content guardrail types, LLM-as-judge quality scoring, and an audit log
- Agent Lab: replay a run as a graph, re-run it from any step, remove prompt sections to see which matter, and check answer consistency
- Agent Skills: ${F.builtInSkills} built-in SKILL.md skills, loaded by an agent only when a task needs them; agent-drafted skills stay off until an admin approves them
- Agent teams: a lead agent delegates to specialist agents in parallel, one level deep, under one shared per-run budget
- Agent and workflow versions with Staging and Production labels, restore, and eval-gated promotion
- App Studio: describe an app and get a multi-page app with live preview, wired to your agents and workflows
- A Memory page where each user can read, edit and delete what agents remember about them
- Agent-to-Agent (A2A) v1.0 and AG-UI protocol support
- Plans: ${plans}. Free plan, no card and no trial clock: 5 agents, 3 workflows, 2 seats, a hosted model included (30 messages/day), 1 knowledge collection
- ~90 industry workflow-recipe guides with paste-ready build specs
- Free Academy with 280+ lessons
- Anonymous 5-day sandbox: a real provisioned tenant, no signup, no card
- Schema-per-tenant Postgres isolation; Keycloak (OAuth2/OIDC) authentication; Spring Boot / Java 21 backend
- Per-user daily dollar budgets on model spend
- Billing at Indian price points, payable by UPI
- Current state, honestly: single-region deployment; no SOC 2 or ISO certification yet; no outbound calling; Microsoft Teams channel is in beta

## Start here

- [Home](${BASE}/): what the platform is and who it is for
- [Try the sandbox](${BASE}/try): open a real tenant and build an agent without creating an account
- [Glossary](${BASE}/glossary): ${GLOSSARY.length} agentic AI terms defined in plain language, each with primary sources
- [Features](${BASE}/features): the full capability list, feature by feature
- [Hire AI staff](${BASE}/hire): the employment-dossier view of an agent — CV, work trial, salary slip
- [Community templates](${BASE}/templates): public gallery of shareable agent and workflow templates
- [IDE and browser plugins](${BASE}/plugins): first-party VS Code, JetBrains and Chrome plugins for controlling agents and workflows
- [Pricing](${BASE}/billing): current plans, tier features and UPI payment
- [WhatsApp cost calculator](${BASE}/whatsapp-cost-calculator): estimate your monthly Meta WhatsApp API bill vs. an AI agent that resolves in fewer messages
- [About](${BASE}/about): what the platform does and the registered legal entity behind it

## Glossary

${GLOSSARY.map((t) => `- [${t.term}](${BASE}/glossary#${t.id}): ${t.definition}`).join("\n")}

## Integrations

- [Integrations index](${BASE}/integrations): every channel and service VegaDūta agents work with
${hubLines(BASE, "/integrations", INTEGRATIONS)}

## AI capabilities

- [AI capabilities index](${BASE}/ai): the stack behind every VegaDūta agent
${hubLines(BASE, "/ai", AI_TOPICS)}

## Platform

- [Platform index](${BASE}/platform): the platform layer under the agents
${hubLines(BASE, "/platform", PLATFORM_TOPICS)}

## Tutorials

- [Tutorials index](${BASE}/tutorials): step-by-step guides from one sentence to a working agent
${hubLines(BASE, "/tutorials", TUTORIALS)}

## Learn

${hasAcademy() ? `- [Academy](${academyUrl()}/): free structured curriculum from first agent (L0) to building agents in code (L21) and beyond\n` : ""}- [Workflow recipes](${BASE}/help/workflow-recipes): ~90 industries of ready-to-build agent workflows, each mapped to the platform's real building blocks
- [Documentation](${BASE}/docs): product documentation for building and operating agents
- [API reference](${BASE}/api-reference): the REST surface for integrators - auth, agents, workflows, channels, MCP, billing
- [Status](${BASE}/status): live component health, refreshed every 30 seconds
- [Changelog](${BASE}/changelog): what changed on the platform, newest first
- [Earn with agents](${BASE}/earn): the automation-entrepreneur playbook — learn agentic AI, automate workflows for businesses, price on outcomes

## Compare

- [Comparisons index](${BASE}/compare): honest side-by-side comparisons, with competitor strengths stated first
${COMPETITORS.map((c) => `- [VegaDūta vs ${c.name}](${BASE}/compare/${c.slug}): ${c.metaDescription}`).join("\n")}

## Architecture

- [Why VegaDūta](${BASE}/why-vegaduta): why the platform runs on Spring Boot/Java 21, schema-per-tenant Postgres and Keycloak, and what it has not built yet

## Developers

- [Documentation](${hasDocsSite() ? docsHref() : `${BASE}/docs`}): install, configure, extend and operate the platform
- [Java Agent SDK](${docPageHref("javaSdk")}): embeddable governance for AI agents in Java applications (tool risk classification, human approval, budgets, audit); 0.1.0-SNAPSHOT, built from source
- [SDKs and dependencies](${docPageHref("sdkOverview")}): the Java SDK and the generated TypeScript and Python API clients
- [Plugins, apps and mobile](${docPageHref("pluginsAndApps")}): editor, browser and mobile surfaces with their real status
- [Source code](${REPO_URL}): Apache-2.0, on GitHub

## Account

- [Sign up](${BASE}/signup): create a tenant
- [Log in](${BASE}/login): existing accounts

## Legal

- [Privacy policy](${BASE}/privacy)
- [Terms of service](${BASE}/terms)
`;
}

export function GET(): Response {
  const base = siteUrl();
  return new Response(isOssSite() ? buildOss(base) : build(base), {
    headers: {
      "content-type": "text/plain; charset=utf-8",
      // Same budget next.config.js gave the static file it replaces.
      "cache-control": "public, max-age=3600, s-maxage=86400, stale-while-revalidate=86400",
    },
  });
}

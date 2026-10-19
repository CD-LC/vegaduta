import type { Metadata } from "next";
import HubIndex from "../components/marketing/HubIndex";
import { AI_TOPICS } from "./ai-data";
import { PLATFORM_FACTS } from "../lib/platform-facts";

// ---------------------------------------------------------------------------
// SEO/AEO hub — /ai index (2026-08-14). One card per AI-capability page in
// ai-data.ts; see lib/seo-hub.ts for the architecture. NOTE: adding "/ai" to
// PUBLIC_ROUTES is what forced the segment-aware matcher in lib/auth.ts —
// don't rename this route without rechecking "/ai-models" stays private.
// ---------------------------------------------------------------------------

export const metadata: Metadata = {
  title: "AI Capabilities — Agents, MCP, RAG, Guardrails | VegaDūta",
  description:
    `How VegaDūta does agentic AI: AI agents with ${PLATFORM_FACTS.builtInToolsFloor}+ tools, Model Context Protocol in both directions, ${PLATFORM_FACTS.byokProviders} LLM providers, RAG, guardrails, graph memory and voice.`,
  alternates: { canonical: "/ai" },
  openGraph: {
    title: "AI Capabilities — Agents, MCP, RAG, Guardrails | VegaDūta",
    description:
      `AI agents with ${PLATFORM_FACTS.builtInToolsFloor}+ tools, Model Context Protocol in both directions, ${PLATFORM_FACTS.byokProviders} LLM providers, RAG, guardrails, graph memory and voice.`,
    url: "/ai",
    type: "website",
    images: ["/og-image.png"],
  },
};

export default function AiIndexPage() {
  return (
    <HubIndex
      pages={AI_TOPICS}
      basePath="/ai"
      label="AI capabilities"
      h1="The AI stack behind every VegaDūta agent"
      intro={[
        `VegaDūta is an agentic-AI platform: you describe a job in one sentence, and an agent takes it on with real tools — across WhatsApp, voice and ${PLATFORM_FACTS.channels - 2} other channels (${PLATFORM_FACTS.channels} in all), with a console in ${PLATFORM_FACTS.indianLanguages} Indian languages, and guardrails that make it ask before doing anything risky.`,
        "These pages explain each layer of that stack — agents, Model Context Protocol, LLM connectors, retrieval, guardrails, memory, voice, and on-device models — as it is actually built.",
      ]}
    />
  );
}

import type { Metadata } from "next";
import HubIndex from "../components/marketing/HubIndex";
import { TUTORIALS } from "./tutorials-data";

// ---------------------------------------------------------------------------
// SEO/AEO hub — /tutorials index (2026-08-14). One card per tutorial in
// tutorials-data.ts (each renders numbered steps + HowTo JSON-LD); see
// lib/seo-hub.ts for the architecture.
// ---------------------------------------------------------------------------

export const metadata: Metadata = {
  title: "Tutorials — Build AI Agents Step by Step | VegaDūta",
  description:
    "Step-by-step guides: build a WhatsApp AI agent, set up a Telegram bot, connect an MCP server, create automated workflows, bring your own LLM and add voice.",
  alternates: { canonical: "/tutorials" },
  openGraph: {
    title: "Tutorials — Build AI Agents Step by Step | VegaDūta",
    description:
      "Build a WhatsApp AI agent, set up a Telegram bot, connect an MCP server, create automated workflows, bring your own LLM and add voice.",
    url: "/tutorials",
    type: "website",
    images: ["/og-image.png"],
  },
};

export default function TutorialsIndexPage() {
  return (
    <HubIndex
      pages={TUTORIALS}
      basePath="/tutorials"
      label="Tutorials"
      h1="From one sentence to a working AI agent"
      intro={[
        "Every tutorial here starts from something you can do right now — most of them in the no-signup sandbox — and ends with an agent or workflow doing a real job on a real channel.",
        "For deeper study, the VegaDūta Academy adds structured courses and hands-on labs on top of these quick-start guides.",
      ]}
    />
  );
}

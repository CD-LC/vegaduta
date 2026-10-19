import type { Metadata } from "next";
import HubIndex from "../components/marketing/HubIndex";
import { INTEGRATIONS } from "./integrations-data";

// ---------------------------------------------------------------------------
// SEO/AEO hub — /integrations index (2026-08-14). One card per integration
// page in integrations-data.ts; see lib/seo-hub.ts for the architecture and
// components/marketing/HubIndex.tsx for the shared renderer. Public route
// (lib/auth.ts PUBLIC_ROUTES) + sitemap.ts entries.
// ---------------------------------------------------------------------------

export const metadata: Metadata = {
  title: "Integrations — WhatsApp, Telegram, Slack & More | VegaDūta",
  description:
    "Connect AI agents to WhatsApp, Telegram, Slack, Discord, Instagram, email, SMS, Google Workspace, Sarvam AI, ElevenLabs and more — every VegaDūta integration.",
  alternates: { canonical: "/integrations" },
  openGraph: {
    title: "Integrations — WhatsApp, Telegram, Slack & More | VegaDūta",
    description:
      "Connect AI agents to WhatsApp, Telegram, Slack, Discord, Instagram, email, SMS, Google Workspace, Sarvam AI, ElevenLabs and more.",
    url: "/integrations",
    type: "website",
    images: ["/og-image.png"],
  },
};

export default function IntegrationsIndexPage() {
  return (
    <HubIndex
      pages={INTEGRATIONS}
      basePath="/integrations"
      label="Integrations"
      h1="Every channel and service your AI agents can work with"
      intro={[
        "VegaDūta agents meet your customers where they already are: WhatsApp (Meta Cloud API or Twilio), Telegram, Slack, Discord, Instagram, Facebook Messenger, X, email, SMS and voice calls.",
        "Beyond messaging, agents plug into Google Workspace, Indian-language AI from Sarvam, ElevenLabs voices, sandboxed code execution with E2B — and anything that speaks Model Context Protocol.",
      ]}
    />
  );
}

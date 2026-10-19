"use client";

import { Plug } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { StatusDot, type StatusDotStatus } from "../ui/status-dot";
import BrandText from "../BrandText";

// "Twilio and other platform health if it's connected" - reuses the exact
// same per-channel credential GET endpoints ChannelsPanel/SocialChannelsPanel
// already call (each returns null when nothing's been activated yet, a real
// object when it has - see api.js) rather than inventing a new backend
// summary endpoint. `platforms` is built by page.js's refresh() from those
// calls; this component only renders whatever it's handed.

// Design-system contract (docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md §3): a
// STATE is a circle (.state-dot) that animates when live, never a bare
// coloured dot. Routed through the shared <StatusDot system="state" .../>
// (ui/status-dot.tsx) rather than hand-rolled .state-dot markup - that
// component exists to be the one status-dot implementation, not five (now
// potentially six). Its default up/down/unknown -> success/error/idle
// mapping already matches connected=true/false/null, so no stateColor
// override needed.
// One row of the `platforms` array both admin pages build from
// PLATFORM_CHECKS in their refresh(): `connected` is a tri-state on purpose
// - null means "probe hasn't answered yet" (the initial useState seed),
// which renders as the distinct "checking" state, not as disconnected.
export interface ConnectedPlatform {
  key: string;
  label: string;
  connected: boolean | null;
}

function PlatformDot({ connected }: { connected: boolean | null }) {
  const status: StatusDotStatus = connected === null ? "unknown" : connected ? "up" : "down";
  return <StatusDot status={status} system="state" live={connected === true} />;
}

export default function ConnectedPlatformsPanel({ platforms }: { platforms: ConnectedPlatform[] }) {
  const t = useTranslations("connectedPlatformsPanel");
  return (
    <div className="mc-platform-panel mc-hud-panel">
      <svg
        className="mc-bento-corners mc-status-corners"
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
        fill="none"
        aria-hidden="true"
      >
        <path d="M4 20 V6 a2 2 0 0 1 2-2 H20" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
        <path d="M80 4 H94 a2 2 0 0 1 2 2 V20" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
        <path d="M96 80 V94 a2 2 0 0 1-2 2 H80" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
        <path d="M20 96 H6 a2 2 0 0 1-2-2 V80" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
      </svg>

      <div className="mc-status-header">
        <h3>
          <span className="cat-chip cat-chip-sm" style={{ ["--cat" as string]: "var(--cat-comms)" }} aria-hidden="true">
            <Plug size={13} aria-hidden="true" />
          </span>
          <BrandText text={t("title")} />{" "}
          <span className="mc-status-header-id">
            {platforms.filter((p) => p.connected).length}/{platforms.length}
          </span>
        </h3>
      </div>

      <div className="mc-platform-list">
        {platforms.map((p) => (
          <div className="mc-platform-item" key={p.key}>
            <PlatformDot connected={p.connected} />
            <span className="mc-platform-name">{p.label}</span>
            <span className="mc-platform-state">
              {p.connected === null ? t("checking") : p.connected ? t("connected") : t("notConnected")}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

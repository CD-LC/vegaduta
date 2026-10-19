"use client";

import Link from "next/link";
import { Gauge, ChevronRight } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote } from "../components/sections/common";
import { ToolCircuitBreakersSection } from "../components/sections/circuit-breakers";
import { PlatformSettingsSection, RATE_LIMIT_KEY_PATTERN } from "../components/sections/platform-settings";

// Abuse & Rate Control §3.6 / §7 - rate limits, flood protection, circuit
// breakers. The live controls today: the rate-limit subset of the platform
// settings table (SignupRateLimiter and friends, admin-editable, apply on the
// next request) below, and the live detector (scores / anomalies / throttles)
// on Fraud & Anomaly - linked rather than duplicated so each signal has one
// home. Per-channel/per-IP token buckets and tool/provider circuit-breaker
// config remain later phases (§7), labeled coming-soon.
export default function AbuseRateControlPage() {
  return (
    <AdminShell title="Abuse & Rate Control">
      <PageHint>
        Keep bursts, floods and abuse in check. Rate-limit knobs below are admin-editable and apply immediately - no
        redeploy or restart. Live detection (risk scores, anomalies, active throttles) is on{" "}
        <Link href="/platform-admin/fraud" className="font-medium text-primary hover:underline">
          Fraud &amp; Anomaly
        </Link>
        .
      </PageHint>

      <SectionCard
        title="Live detection"
        subtitle="Risk scores, anomaly log and active throttles for every tenant"
        icon={Gauge}
        delay={0}
      >
        <p className="text-sm text-muted-foreground">
          The abuse detector (AbuseSignalService) is live: it scores every principal on spend spikes, token burn, message
          floods, signup velocity, tool-error abuse and geo-velocity, then watches / throttles / suspends by tier.
        </p>
        <Link href="/platform-admin/fraud" className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline">
          Open Fraud &amp; Anomaly <ChevronRight size={13} />
        </Link>
      </SectionCard>

      <PlatformSettingsSection
        title="Operational rate-limit settings"
        subtitle="The rate-limit/abuse subset of the platform settings table - the full list lives on Platform Settings"
        keyFilter={(key) => RATE_LIMIT_KEY_PATTERN.test(key)}
        delay={0.03}
      />

      <ToolCircuitBreakersSection delay={0.06} />

      <InfoNote>
        Not built: per-user / per-channel / per-IP token-bucket controls and provider-failover circuit state. The
        existing limiters (signup, template import, workflow trigger, share, MCP server, gateway) are tuned only through
        the settings above where they are platform settings; the rest are deployment config. The hosted-provider breaker
        keeps its state in memory per instance and is not readable from the console.
      </InfoNote>
    </AdminShell>
  );
}

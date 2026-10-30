"use client";

import Link from "next/link";
import { Gauge, ChevronRight } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote } from "../components/sections/common";
import { ToolCircuitBreakersSection } from "../components/sections/circuit-breakers";
import { PlatformSettingsSection, RATE_LIMIT_KEY_PATTERN } from "../components/sections/platform-settings";
import { useTranslations } from "../../lib/i18n";

// Abuse & Rate Control §3.6 / §7 - rate limits, flood protection, circuit
// breakers. The live controls today: the rate-limit subset of the platform
// settings table (SignupRateLimiter and friends, admin-editable, apply on the
// next request) below, and the live detector (scores / anomalies / throttles)
// on Fraud & Anomaly - linked rather than duplicated so each signal has one
// home. Per-channel/per-IP token buckets and tool/provider circuit-breaker
// config remain later phases (§7), labeled coming-soon.
export default function AbuseRateControlPage() {
    const tHC = useTranslations("hcPlatformadminAbusePage");
  return (
    <AdminShell title={tHC("s8")}>
      <PageHint>
        {tHC("s7")}{" "}
        <Link href="/platform-admin/fraud" className="font-medium text-primary hover:underline">
          {tHC("s6")}
        </Link>
        .
      </PageHint>

      <SectionCard
        title={tHC("s5")}
        subtitle="Risk scores, anomaly log and active throttles for every tenant"
        icon={Gauge}
        delay={0}
      >
        <p className="text-sm text-muted-foreground">
          {tHC("s4")}
        </p>
        <Link href="/platform-admin/fraud" className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline">
          {tHC("s3")} <ChevronRight size={13} />
        </Link>
      </SectionCard>

      <PlatformSettingsSection
        title={tHC("s2")}
        subtitle="The rate-limit/abuse subset of the platform settings table - the full list lives on Platform Settings"
        keyFilter={(key) => RATE_LIMIT_KEY_PATTERN.test(key)}
        delay={0.03}
      />

      <ToolCircuitBreakersSection delay={0.06} />

      <InfoNote>
        {tHC("s1")}
      </InfoNote>
    </AdminShell>
  );
}

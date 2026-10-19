"use client";

import AdminShell from "../components/AdminShell";
import { PageHint } from "../components/sections/common";
import { FallbackSection } from "../components/sections/observability";
import {
  AbuseScoresSection,
  AbuseAnomaliesSection,
  ThrottledSection,
  AbuseThresholdsSection,
} from "../components/sections/abuse-monitor";

// Fraud & Anomaly §3.7 / §7 - the anomaly/abuse scoring layer, now fully live:
// AbuseSignalService scores every active tenant's principals on spend spikes,
// token burn, message floods, signup velocity, tool-error abuse and
// geo-velocity, folds them into a 0..100 risk score, then acts by tier -
// WATCH raises a fingerprinted anomaly issue, THROTTLE rate-limits the
// principal (TTL-bounded, clearable below), SUSPEND emergency-stops the tenant
// when auto-suspend is on. Thresholds are platform_setting rows (V120),
// rendered read-only here and edited on Platform Settings. The fleet-wide
// provider-fallback signal rounds it out as a spend/reliability proxy.
export default function FraudAnomalyPage() {
  return (
    <AdminShell title="Fraud & Anomaly">
      <PageHint>
        Detect misuse before it becomes a bill or an incident - live per-principal risk scores, the anomaly log, active
        throttles, and the detector&rsquo;s effective thresholds.
      </PageHint>

      <AbuseScoresSection pollMs={15000} delay={0} />
      <ThrottledSection pollMs={15000} delay={0.03} />
      <AbuseAnomaliesSection pollMs={15000} delay={0.06} />
      <FallbackSection pollMs={10000} delay={0.09} />
      <AbuseThresholdsSection pollMs={60000} delay={0.12} />
    </AdminShell>
  );
}

"use client";

import { HeartPulse } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote } from "../components/sections/common";
import { SystemStatusSection } from "../components/sections/health";
import { ExternalToolsSection } from "../components/sections/external-tools";
import { IssuesSection, LlmUsageSection, ActivitySection } from "../components/sections/observability";

// Diagnostics & Observability §10 - the single pane where an admin diagnoses
// everything without hunting across tools. Fully built this pass since its spec
// is complete:
//   1. External-tool deep links (Grafana / Prometheus / Langfuse / Temporal)
//      with live reachability dots, URLs from env config. (Phase A)
//   2. Native panels for the in-app APIs that need no external tool - system
//      status, LLM cost/latency/tokens, the audit log, and the issues portal.
// Phase B (iframe-embedded Grafana/Langfuse behind a scoped token) is the
// documented follow-up and intentionally not attempted here.
export default function DiagnosticsPage() {
  return (
    <AdminShell title="Diagnostics & Observability">
      <PageHint>
        One portal to diagnose everything - deep links to the external monitoring stack, plus native panels for the data
        core already exposes in-app. No hunting across tools.
      </PageHint>

      <ExternalToolsSection delay={0} />

      <InfoNote>
        The panels below render data core exposes directly - no external tool needed. External dashboards
        (Grafana/Prometheus/Langfuse/Temporal) open in a new tab above; embedding them inline behind a scoped token is a
        planned Phase B.
      </InfoNote>

      <SectionCard title="System status" icon={HeartPulse} delay={0.03}>
        <SystemStatusSection pollMs={15000} hideHeader />
      </SectionCard>

      <IssuesSection pollMs={10000} delay={0.06} />
      <LlmUsageSection pollMs={10000} delay={0.09} />
      <ActivitySection pollMs={10000} delay={0.12} />
    </AdminShell>
  );
}

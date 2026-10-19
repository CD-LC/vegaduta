"use client";

import Link from "next/link";
import { CreditCard, ChevronRight } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote } from "../components/sections/common";
import { PerUserBudgetSection, FreeTierQuotaSection, ChatGenerationSection } from "../components/sections/budgets";
import { SmartAiSection } from "../components/sections/smart-ai";

// Budgets & Limits §3.3 / §4 - the per-principal dollar budget subsystem.
// Global defaults are wired to GET/PUT /api/platform/per-user-budget (with a
// core-3 endpoint); per-plan limits are live on the dedicated Plan Limits
// editor (linked below, GET/PUT /api/platform/plan-limits). Per-tenant/user
// overrides do not exist in core and are not shown.
export default function BudgetsLimitsPage() {
  return (
    <AdminShell title="Budgets & Limits">
      <PageHint>
        A per-principal dollar budget over a configurable window, decremented by every modality&rsquo;s real cost.
        The global default is scaled by the tenant&rsquo;s plan.
      </PageHint>

      <PerUserBudgetSection />

      <FreeTierQuotaSection />

      <ChatGenerationSection />

      <SmartAiSection delay={0.03} />

      <SectionCard title="Per-plan limits" icon={CreditCard} delay={0.06}>
        <p className="text-xs text-muted-foreground">
          Per-tier caps (max users / agents / workflows / KB collections, BYOK, WhatsApp, white-label) are live today in
          the Plan Limits editor on Tenants &amp; Users, backed by{" "}
          <code className="font-mono">GET/PUT /api/platform/plan-limits</code>.
        </p>
        <Link
          href="/platform-admin/tenants"
          className="mt-1 inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
        >
          Open Plan Limits editor <ChevronRight size={13} />
        </Link>
      </SectionCard>

      <InfoNote>
        Per-plan dollar budgets are a multiplier on the global default above (Premium and Unlimited scale it; the
        multipliers are deployment config, not editable here). Per-tenant / per-user budget overrides do not exist in core,
        so none are offered. Tenant users can already buy a top-up when their budget is exhausted.
      </InfoNote>
    </AdminShell>
  );
}

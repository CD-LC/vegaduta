"use client";

import Link from "next/link";
import { CreditCard, ChevronRight } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote } from "../components/sections/common";
import { PerUserBudgetSection, FreeTierQuotaSection, ChatGenerationSection } from "../components/sections/budgets";
import { SmartAiSection } from "../components/sections/smart-ai";
import { useTranslations } from "../../lib/i18n";

// Budgets & Limits §3.3 / §4 - the per-principal dollar budget subsystem.
// Global defaults are wired to GET/PUT /api/platform/per-user-budget (with a
// core-3 endpoint); per-plan limits are live on the dedicated Plan Limits
// editor (linked below, GET/PUT /api/platform/plan-limits). Per-tenant/user
// overrides do not exist in core and are not shown.
export default function BudgetsLimitsPage() {
  const tHC = useTranslations("hcPlatformadminBudgetsPage");
  return (
    <AdminShell title={tHC("s7")}>
      <PageHint>{tHC("s6")}</PageHint>

      <PerUserBudgetSection />

      <FreeTierQuotaSection />

      <ChatGenerationSection />

      <SmartAiSection delay={0.03} />

      <SectionCard title={tHC("s5")} icon={CreditCard} delay={0.06}>
        <p className="text-xs text-muted-foreground">
          {tHC("s4")} <code className="font-mono">{tHC("s3")}</code>.
        </p>
        <Link
          href="/platform-admin/tenants"
          className="mt-1 inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
        >
          {tHC("s2")} <ChevronRight size={13} />
        </Link>
      </SectionCard>

      <InfoNote>{tHC("s1")}</InfoNote>
    </AdminShell>
  );
}

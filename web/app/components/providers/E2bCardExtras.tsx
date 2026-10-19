"use client";

import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import type { E2bSetup } from "./useE2bSetup";

/**
 * E2B-only follow-ups on the provider card: an ACTIVE credential alone isn't enough for run_code
 * (AgentToolProvider#resolveE2bTools also requires a template), so surface that gap here, plus the
 * saved-template note and the "Test connection" action. Callers gate on canManageProviders/plan.
 */
export default function E2bCardExtras({
  name,
  activated,
  e2b,
  canManage,
}: {
  name: string;
  activated: any;
  e2b: E2bSetup;
  /** canManageProviders && !isFreePlan */
  canManage: boolean;
}) {
  const t = useTranslations("providersPanel");
  const active = activated?.status === "ACTIVE";
  return (
    <>
      {canManage && active && !activated?.codeExecutionTemplateId && (
        <div role="presentation" onClick={(e) => e.stopPropagation()}>
          <form onSubmit={e2b.handleSetTemplate} className="mt-4 space-y-2">
            <p className="text-xs text-muted-foreground">{t("e2bTemplateNeededNote")}</p>
            <div className="flex flex-wrap items-center gap-2">
              <Label htmlFor={`e2b-template-id-${name}`} className="sr-only">
                {t("e2bTemplateIdPlaceholder")}
              </Label>
              <Input
                id={`e2b-template-id-${name}`}
                type="text"
                placeholder={t("e2bTemplateIdPlaceholder")}
                value={e2b.templateId}
                onChange={(e) => e2b.setTemplateId(e.target.value)}
                className="min-w-[min(220px,100%)] flex-1"
                required
              />
              <Button type="submit" size="sm" disabled={e2b.templateBusy}>
                {e2b.templateBusy ? t("savingButton") : t("saveButton")}
              </Button>
            </div>
          </form>
        </div>
      )}
      {active && activated?.codeExecutionTemplateId && (
        <p className="mt-3 text-xs text-muted-foreground">
          {t("e2bTemplateConfiguredNote", { templateId: activated.codeExecutionTemplateId })}
        </p>
      )}
      {/* "Test connection" is gated only on the credential being ACTIVE. */}
      {canManage && active && (
        <div className="mt-4 space-y-2" role="presentation" onClick={(e) => e.stopPropagation()}>
          <Button type="button" size="sm" variant="outline" disabled={e2b.testBusy} onClick={e2b.handleTestConnection}>
            {e2b.testBusy ? t("e2bTestConnectionBusyButton") : t("e2bTestConnectionButton")}
          </Button>
          {e2b.testResult && (
            <p
              className={cn("text-xs", e2b.testResult.ok ? "text-[var(--success-text)]" : "text-[var(--danger-text)]")}
            >
              {e2b.testResult.message}
            </p>
          )}
        </div>
      )}
    </>
  );
}

"use client";

import { Wrench, AlertTriangle, CheckCircle2, Power, Plus } from "lucide-react";
import Link from "next/link";
import { Badge } from "../ui/badge";
import { Card, CardContent } from "../ui/card";
import { Alert, AlertTitle, AlertDescription } from "../ui/alert";
import { Tabs, TabsList, TabsTrigger } from "../ui/tabs";
import { useTranslations } from "../../lib/i18n";
import { isBillingEnabled } from "../../lib/billing-flag";

/**
 * The tools page's status area: the error (with a plan-limit upgrade CTA),
 * success and register-warning alerts, then the auto-select banner.
 */
export function McpPanelNotices({
  error,
  success,
  registerWarnings,
}: {
  error: string | null;
  success: string | null;
  registerWarnings: string[];
}) {
  const t = useTranslations("mcpPanel");
  return (
    <>
      {/* Plan-limit rejections (McpRegistryService's TenantLimitExceededException
          - FREE caps at 2 connectors / catalog-only) get an upgrade CTA
          appended so the moment sells the fix instead of dead-ending
          (pricing audit 2026-07-31 §5.3/B5). Detected by the message text
          the backend deliberately keeps client-actionable ("...upgrade
          your plan..."); every other error renders exactly as before. */}
      {error && (
        <Alert variant="destructive">
          <AlertTriangle aria-hidden="true" />
          <AlertDescription>
            {error}
            {isBillingEnabled() && /upgrade your plan/i.test(String(error)) && (
              <>
                {" "}
                <Link href="/features" className="font-semibold underline underline-offset-2">
                  {t("planLimitUpgradeCta")}
                </Link>
              </>
            )}
          </AlertDescription>
        </Alert>
      )}
      {success && (
        <Alert variant="success">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>{success}</AlertDescription>
        </Alert>
      )}
      {/* register()'s soft-validation notices (e.g. "missing recommended
       * credential FOO_API_KEY") - surfaced right away instead of only
       * discoverable later via a failed connection attempt. */}
      {registerWarnings.length > 0 && (
        <Alert variant="warning">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("registerWarningsAlertTitle")}</AlertTitle>
          <AlertDescription>
            <ul className="ml-4 list-disc space-y-1">
              {registerWarnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      {/* Auto-select banner - real feature (ToolRelevanceSelector), but a
       * global Spring property, not a per-tenant toggle - so the status
       * pill on the right is deliberately non-interactive (a Badge, not a
       * button). */}
      <Card>
        <CardContent className="flex flex-wrap items-center gap-3 pt-6">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
            <Wrench aria-hidden="true" size={18} />
          </span>
          <div className="min-w-0 flex-1">
            <div className="text-sm font-semibold">{t("autoSelectBannerTitle")}</div>
            <div className="text-xs text-muted-foreground">{t("autoSelectBannerBody")}</div>
          </div>
          <Badge variant="secondary" className="shrink-0 gap-1">
            <Power aria-hidden="true" /> {t("platformDefaultAlwaysOn")}
          </Badge>
        </CardContent>
      </Card>
    </>
  );
}

/** The category tab strip (arrow keys move between tabs). */
export function McpTabBar({ activeTab, setActiveTab }: { activeTab: string; setActiveTab: (tab: string) => void }) {
  const t = useTranslations("mcpPanel");
const tabs = [
  { key: "all", label: t("tabAll") },
  { key: "builtin", label: t("tabBuiltin") },
  { key: "google", label: t("googleWorkspace") },
  { key: "mcp", label: t("tabMcpServers") },
  { key: "custom", label: t("tabCustom") },
  { key: "browse", label: t("browseRegistryTab") },
];
  return (
    <Tabs value={activeTab} onValueChange={setActiveTab}>
      <TabsList className="h-auto flex-wrap justify-start">
        {tabs.map((tab, idx) => (
          <TabsTrigger
            key={tab.key}
            value={tab.key}
            id={`mcp-tab-${tab.key}`}
            aria-controls={`mcp-tabpanel-${tab.key}`}
            onKeyDown={(e) => {
              if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
              e.preventDefault();
              const dir = e.key === "ArrowRight" ? 1 : -1;
              const nextTab = tabs[(idx + dir + tabs.length) % tabs.length];
              setActiveTab(nextTab.key);
              document.getElementById(`mcp-tab-${nextTab.key}`)?.focus();
            }}
          >
            {tab.label}
          </TabsTrigger>
        ))}
      </TabsList>
    </Tabs>
  );
}

/** The dashed "register a custom server" tile that closes the grid. */
export function AddServerTile({ onOpen }: { onOpen: () => void }) {
  const t = useTranslations("mcpPanel");
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => onOpen()}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onOpen();
        }
      }}
      className="flex min-h-[104px] cursor-pointer flex-col items-center justify-center gap-1.5 rounded-xl border border-dashed border-border p-4 text-center text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground"
    >
      <span className="flex size-8 items-center justify-center rounded-full bg-muted">
        <Plus aria-hidden="true" size={16} />
      </span>
      <div className="text-sm font-medium text-foreground">{t("registerCustomServer")}</div>
      <div className="text-xs">{t("registerCustomServerSub")}</div>
    </div>
  );
}

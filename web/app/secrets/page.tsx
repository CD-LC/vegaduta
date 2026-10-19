"use client";

// /secrets - the tenant secrets vault.
//
// Page/panel split follows /providers: this file owns fetching and route
// readiness, SecretsPanel owns the form, the list and every mutation. The
// panel calls onChanged() after a write rather than mutating a local copy,
// because a store is really a create-or-rotate server-side and the row that
// comes back can differ from what was sent (id preserved on rotation,
// preview recomputed, rotatedAt stamped).

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { api, type VaultSecret } from "../lib/api";
import AppShell from "../components/AppShell";
import { usePageReady } from "../components/RouteTransition";
import PageHeader from "../components/ax/PageHeader";
import SecretsPanel from "../components/SecretsPanel";
import { useTranslations } from "../lib/i18n";
import { SetupHint } from "../components/setup/SetupLinks";
import { Button } from "../components/ui/button";

export default function SecretsPage() {
  const t = useTranslations("secretsPage");
  const [secrets, setSecrets] = useState<VaultSecret[]>([]);
  const [loaded, setLoaded] = useState(false);

  const refresh = useCallback(() => {
    api
      .listSecrets()
      .then(setSecrets)
      .catch(() => setSecrets([]))
      .finally(() => setLoaded(true));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Called once, unconditionally, before any early return - rules of hooks,
  // and the convention every gated page in this app follows.
  usePageReady(loaded);

  const activeCount = secrets.filter((s) => s.status === "ACTIVE" && !s.expired).length;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={t("pageTitle")}
        subtitle={t("pageSubtitle")}
        actions={
          <Button asChild variant="outline" size="sm">
            <Link href="/tools">
              {t("useInToolsLink")} <ArrowRight size={14} />
            </Link>
          </Button>
        }
      />
      <p className="mb-6 text-sm text-muted-foreground">
        {activeCount > 0 ? t("activeSummary", { count: activeCount }) : t("noneYetSummary")}
      </p>
      <SetupHint className="mb-4 -mt-3" lead="Use secrets in:" targets={["tools", "mcp", "workflows", "integrations"]} />
      <SecretsPanel secrets={secrets} onChanged={refresh} />
    </AppShell>
  );
}

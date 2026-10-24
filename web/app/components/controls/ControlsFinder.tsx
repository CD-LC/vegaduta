"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { ArrowUpRight, Search } from "lucide-react";
import { settingsApi, type SettingsClient } from "../../lib/api/settings";
import { useTranslations } from "../../lib/i18n";
import { useAuth } from "../AuthProvider";
import { useSettingsCatalog } from "../settings/useSettings";
import { useSettingsText } from "../settings/useSettingsText";
import { Alert, AlertDescription } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Skeleton } from "../ui/skeleton";
import { CONTROL_AREAS, reachableAreas } from "./controlAreas";
import { matchesQuery, sectionsByAnchor, settingLinks } from "./controlsModel";

/**
 * "Where do I control...?": the control areas of the console and, built from the settings catalog, every runtime
 * setting with the pages that change it. One search box filters both lists (it also matches the words people use
 * for a thing: "prompt", "timeline", "timeout"). The settings list is the catalog, so a new setting shows up here
 * without anyone editing this page.
 */
export default function ControlsFinder({ client = settingsApi }: { client?: SettingsClient }) {
  const t = useTranslations("controls");
  const text = useSettingsText();
  const { isAdmin, hasPermission } = useAuth();
  const catalog = useSettingsCatalog(client);
  const [query, setQuery] = useState("");
  const reachable = useMemo(() => new Set(reachableAreas({ isAdmin, hasPermission }).map((a) => a.id)), [isAdmin, hasPermission]);

  const areas = CONTROL_AREAS.filter((a) =>
    matchesQuery(query, t(`areas.${a.id}.title`), t(`areas.${a.id}.summary`), t(`areas.${a.id}.keywords`))
  );
  const entries = useMemo(
    () =>
      catalog.entries.filter((e) =>
        matchesQuery(query, e.key, text.label(e), text.description(e), text.groupLabel(e.group))
      ),
    [catalog.entries, query, text]
  );
  const sections = sectionsByAnchor(entries);
  const searching = query.trim() !== "";
  const shown = areas.length + entries.length;

  return (
    <div className="space-y-8" data-testid="controls-finder">
      <div role="search" className="relative max-w-xl">
        <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          type="search"
          className="pl-9"
          aria-label={t("search.label")}
          placeholder={t("search.placeholder")}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      <p role="status" aria-live="polite" className="min-h-5 text-sm text-muted-foreground" data-testid="controls-count">
        {searching ? (shown === 0 ? t("search.none", { query: query.trim() }) : t("search.count", { count: shown })) : ""}
      </p>

      {areas.length > 0 ? (
        <section aria-labelledby="controls-areas-heading" data-testid="controls-areas">
          <h2 id="controls-areas-heading" className="text-lg font-semibold">
            {t("areasHeading")}
          </h2>
          <p className="mt-1 max-w-2xl text-sm text-muted-foreground">{t("areasIntro")}</p>
          <ul className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {areas.map((a) => {
              const Icon = a.icon;
              const allowed = reachable.has(a.id);
              return (
                <li key={a.id} className="flex flex-col gap-2 rounded-xl border border-border bg-card p-4" data-testid={`control-area-${a.id}`}>
                  <h3 className="flex items-center gap-2 text-base font-semibold">
                    <Icon aria-hidden="true" className="size-4 text-primary" />
                    {t(`areas.${a.id}.title`)}
                  </h3>
                  <p className="flex-1 text-sm text-muted-foreground">{t(`areas.${a.id}.summary`)}</p>
                  {allowed ? (
                    <Button asChild size="sm" variant="outline" className="self-start">
                      <Link href={a.href}>
                        {t("openArea")}
                        <span className="sr-only"> {t(`areas.${a.id}.title`)}</span>
                        <ArrowUpRight aria-hidden="true" />
                      </Link>
                    </Button>
                  ) : (
                    <p className="text-xs text-muted-foreground" data-testid={`control-area-${a.id}-restricted`}>
                      {t("needsAccess")}
                    </p>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}

      <section aria-labelledby="controls-settings-heading" data-testid="controls-settings">
        <h2 id="controls-settings-heading" className="text-lg font-semibold">
          {t("settingsHeading")}
        </h2>
        <p className="mt-1 max-w-2xl text-sm text-muted-foreground">{t("settingsIntro")}</p>

        {catalog.status === "loading" ? (
          <div role="status" aria-busy="true" aria-label={t("loading")} className="mt-4 space-y-3" data-testid="controls-loading">
            <Skeleton className="h-16" />
            <Skeleton className="h-16" />
            <Skeleton className="h-16" />
          </div>
        ) : catalog.status === "error" ? (
          <Alert variant="destructive" className="mt-4" data-testid="controls-error">
            <AlertDescription>
              <span>{t("loadFailed", { message: catalog.error ?? "" })}</span>
              <Button type="button" size="sm" variant="outline" onClick={catalog.reload}>
                {t("retry")}
              </Button>
            </AlertDescription>
          </Alert>
        ) : catalog.entries.length === 0 ? (
          <p className="mt-4 text-sm text-muted-foreground" data-testid="controls-empty">
            {t("emptyCatalog")}
          </p>
        ) : entries.length === 0 ? (
          <p className="mt-4 text-sm text-muted-foreground" data-testid="controls-no-match">
            {t("search.noSettings")}
          </p>
        ) : (
          <div className="mt-4 space-y-8">
            {sections.map((s) => (
              <div key={s.anchor} id={s.anchor} className="scroll-mt-20" data-testid={`controls-anchor-${s.anchor}`}>
                <h3 className="text-base font-semibold">{t(`anchors.${s.anchor}.title`)}</h3>
                <p className="mt-1 max-w-2xl text-sm text-muted-foreground">{t(`anchors.${s.anchor}.intro`)}</p>
                <ul className="mt-3 divide-y divide-border rounded-xl border border-border bg-card">
                  {s.items.map((e) => {
                    const links = settingLinks(e);
                    const label = text.label(e);
                    return (
                      <li key={e.key} id={`setting-${e.key}`} className="flex flex-col gap-2 p-4" data-testid={`control-setting-${e.key}`}>
                        <div className="flex flex-wrap items-center gap-2">
                          <h4 className="text-sm font-semibold">{label}</h4>
                          <Badge variant="secondary">{text.groupLabel(e.group)}</Badge>
                          {e.risk === "security" || e.risk === "high" ? <Badge variant="outline">{text.ui(`risk.${e.risk}`)}</Badge> : null}
                        </div>
                        <p className="text-sm text-muted-foreground">{text.description(e)}</p>
                        <ul className="flex flex-wrap gap-x-4 gap-y-1 text-sm">
                          {links.workspace ? (
                            <li>
                              <Link href={links.workspace} className="text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring">
                                {t("changeWorkspace")}
                                <span className="sr-only">: {label}</span>
                              </Link>
                            </li>
                          ) : null}
                          {links.agent ? (
                            <li>
                              <Link href={links.agent} className="text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring">
                                {t("changeAgent")}
                                <span className="sr-only">: {label}</span>
                              </Link>
                            </li>
                          ) : (
                            <li className="text-muted-foreground">{t("workspaceOnly")}</li>
                          )}
                        </ul>
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

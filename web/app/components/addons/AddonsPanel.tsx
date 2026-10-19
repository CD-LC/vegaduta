"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { PackagePlus, RefreshCw } from "lucide-react";
import { addonsApi, type AddonChange, type AddonView, type AddonsApi } from "../../lib/addonsApi";
import {
  NO_FILTERS,
  categoryCounts,
  dependentsOf,
  filterAddons,
  groupByCategory,
  isWaitingForHost,
  requirementsToEnable,
  type AddonFilters,
} from "../../lib/addonsModel";
import { usePageReady } from "../RouteTransition";
import PageHeader from "../ax/PageHeader";
import { EmptyState, Section } from "../ax/page-kit";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Skeleton } from "../ui/skeleton";
import AddonCard from "./AddonCard";
import AddonDialogs, { type PendingChange } from "./AddonDialogs";
import AddonFilterBar from "./AddonFilterBar";
import HostApplyPanel from "./HostApplyPanel";
import { pick, useAddonText, type AddonText } from "./addonText";
import { useAddons } from "./useAddons";

// /add-ons: optional extras for the personal install, grouped by what they are for. Core records the
// wish; compose add-ons are started by VegaDuta Setup on the computer (core cannot start containers),
// models are downloaded by core itself. The page therefore shows wanted, running, waiting and failed as
// different things and never says "on" for a wish nobody has applied.

function noticeText(change: AddonChange, byId: ReadonlyMap<string, AddonView>, text: AddonText): string {
  const { t } = text;
  const name = text.name(change.addon);
  if (change.changed.length === 0) return t("notice.unchanged", { name });
  const list = change.changed.map((id) => text.nameOf(id, byId)).join(", ");
  const base = t(change.addon.desired ? "notice.enabled" : "notice.disabled", { names: list });
  return change.needsHost ? `${base} ${t("notice.needsHost")}` : base;
}

export interface AddonsPanelProps {
  /** Test seam; the page uses the real client. */
  api?: AddonsApi;
}

export default function AddonsPanel({ api = addonsApi }: AddonsPanelProps) {
  const text = useAddonText();
  const { t } = text;
  const state = useAddons(api);
  const { data, loaded } = state;
  usePageReady(loaded);

  const [filters, setFilters] = useState<AddonFilters>(NO_FILTERS);
  const [pending, setPending] = useState<PendingChange | null>(null);

  const byId = useMemo(() => new Map((data?.addons ?? []).map((a) => [a.id, a] as const)), [data]);
  const visible = useMemo(
    () =>
      filterAddons(data?.addons ?? [], filters, (a) => `${text.name(a)} ${text.description(a)} ${text.category(a.category)}`),
    [data, filters, text]
  );
  const groups = useMemo(() => groupByCategory(visible), [visible]);
  const categories = useMemo(() => {
    const counts = categoryCounts(data?.addons ?? []);
    return groupByCategory(data?.addons ?? []).map((g) => ({ category: g.category, count: counts.get(g.category) ?? 0 }));
  }, [data]);

  const waiting = (data?.addons ?? []).filter((a) => isWaitingForHost(a) && a.kind === "compose-profile");
  const bridge = data?.hostBridge.configured ?? false;
  const hasModels = (data?.addons ?? []).some((a) => a.kind === "model-pull");

  function askEnable(a: AddonView) {
    setPending({ kind: "enable", addon: a, requirements: requirementsToEnable(a.id, byId) });
  }
  function askDisable(a: AddonView) {
    const dependents = dependentsOf(a.id, byId);
    setPending(dependents.length > 0 ? { kind: "cascade", addon: a, dependents } : { kind: "disable", addon: a });
  }

  async function confirm(change: PendingChange) {
    if (change.kind === "enable") {
      await state.enable(change.addon.id);
      return;
    }
    const result = await state.disable(change.addon.id, change.kind === "cascade");
    if (result.ok === false && result.blockedBy.length > 0) {
      // Core knows a dependent this view did not: ask before taking those down too.
      const dependents = result.blockedBy.map((id) => byId.get(id)).filter((a): a is AddonView => !!a);
      setPending({ kind: "cascade", addon: change.addon, dependents });
    }
  }

  const failure = state.failure;
  const failedName = failure ? text.nameOf(failure.id, byId) : "";

  return (
    <>
      <PageHeader
        icon={PackagePlus}
        title={t("title")}
        subtitle={t("subtitle")}
        actions={
          <Button type="button" variant="outline" size="sm" onClick={() => void state.refresh()}>
            <RefreshCw aria-hidden="true" />
            {t("refresh")}
          </Button>
        }
      />

      <div className="space-y-4">
        {failure && (
          <Alert variant="error" data-testid="addon-failure-alert">
            <AlertTitle>{t("error.title", { name: failedName })}</AlertTitle>
            <AlertDescription>
              <p>{text.error(failure.status, failure.code, failure.message)}</p>
              <Button type="button" variant="ghost" size="sm" onClick={state.clearFailure}>
                {t("dismiss")}
              </Button>
            </AlertDescription>
          </Alert>
        )}

        {state.notice && !failure && (
          <Alert variant="success" role="status" data-testid="addon-notice">
            <AlertDescription>
              <p>{noticeText(state.notice, byId, text)}</p>
              <Button type="button" variant="ghost" size="sm" onClick={state.clearNotice}>
                {t("dismiss")}
              </Button>
            </AlertDescription>
          </Alert>
        )}

        {!loaded && (
          <div aria-busy="true" aria-label={t("loading")} className="grid gap-3 md:grid-cols-2" data-testid="addons-loading">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-44 w-full rounded-xl" />
            ))}
          </div>
        )}

        {loaded && !data && (
          <Alert variant="error" data-testid="addons-load-error">
            <AlertTitle>{t("loadError.title")}</AlertTitle>
            <AlertDescription>
              <p>{state.loadError ? t("loadError.body", { message: state.loadError }) : t("loadError.bodyPlain")}</p>
              <Button type="button" size="sm" onClick={() => void state.refresh()}>
                {t("retry")}
              </Button>
            </AlertDescription>
          </Alert>
        )}

        {data && (
          <>
            {/* === false, not falsy: a pre-mayChange core answers without the field
                (mixed version during a rolling deploy), and switches must keep the
                old behaviour - clickable, with the role check answering 403 - rather
                than every card going read-only because the field is absent. */}
            {data.mayChange === false && (
              <Alert variant="default" data-testid="addons-readonly">
                <AlertDescription>
                  <p>{t("readOnly")}</p>
                </AlertDescription>
              </Alert>
            )}

            {state.stale && (
              <Alert variant="warning" role="status" data-testid="addons-stale">
                <AlertDescription>
                  <p>{t("stale")}</p>
                </AlertDescription>
              </Alert>
            )}

            <p className="text-sm text-muted-foreground" data-testid="addons-hardware">
              {t("hardware", {
                ram: Math.round(data.hardware.ramGb * 10) / 10,
                cores: data.hardware.cpuCores,
                gpu: data.hardware.gpu
                  ? `${data.hardware.gpu.name} (${data.hardware.gpu.vramGb} GB)`
                  : t(data.hardware.appleSilicon ? "hardwareApple" : "hardwareNoGpu"),
              })}
            </p>

            {hasModels && !data.ollama.reachable && (
              <Alert variant="warning" data-testid="addons-ollama-down">
                <AlertTitle>{t("ollamaDown.title")}</AlertTitle>
                <AlertDescription>
                  <p>
                    {t("ollamaDown.body")}{" "}
                    <Link href="/providers/local-model" className="underline underline-offset-2">
                      {t("ollamaDown.link")}
                    </Link>
                  </p>
                </AlertDescription>
              </Alert>
            )}

            <HostApplyPanel apply={data.apply} bridgeConfigured={bridge} pending={waiting} text={text} />

            <AddonFilterBar filters={filters} onChange={setFilters} categories={categories} total={data.addons.length} text={text} />

            {groups.length === 0 ? (
              <EmptyState
                icon={PackagePlus}
                title={t("empty.title")}
                description={t("empty.body")}
                action={
                  <Button type="button" variant="outline" size="sm" onClick={() => setFilters(NO_FILTERS)}>
                    {t("filter.clear")}
                  </Button>
                }
              />
            ) : (
              groups.map((g) => {
                const hint = pick(t, `categoryHint.${g.category}`, "");
                return (
                  <Section
                    key={g.category}
                    title={
                      <span data-testid={`addons-group-${g.category}`}>
                        {text.category(g.category)} <span className="text-muted-foreground">({g.items.length})</span>
                      </span>
                    }
                    description={hint || undefined}
                  >
                    <ul className="grid gap-3 md:grid-cols-2" aria-label={text.category(g.category)}>
                      {g.items.map((a) => (
                        <li key={a.id} className="min-w-0">
                          <AddonCard
                            addon={a}
                            byId={byId}
                            text={text}
                            hardware={data.hardware}
                            bridgeConfigured={bridge}
                            busy={state.busy.has(a.id)}
                            mayChange={data.mayChange !== false}
                            onEnable={askEnable}
                            onDisable={askDisable}
                          />
                        </li>
                      ))}
                    </ul>
                  </Section>
                );
              })
            )}

            <Card className="gap-1 border-dashed p-4">
              <p className="text-xs text-muted-foreground">{t("footnote")}</p>
            </Card>
          </>
        )}
      </div>

      <AddonDialogs
        pending={pending}
        hardware={data?.hardware ?? { ramGb: 0, cpuCores: 0, gpu: null, appleSilicon: false }}
        bridgeConfigured={bridge}
        text={text}
        onCancel={(c) => setPending((cur) => (cur === c ? null : cur))}
        onConfirm={confirm}
      />
    </>
  );
}

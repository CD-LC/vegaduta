"use client";

// Platform-hosted LLM provider pool card (split out of page.tsx): the pool
// master switch, region filter, the provider rows and the add/edit form.

import { motion, AnimatePresence } from "motion/react";
import { Info, Server, Pencil, Trash2 } from "lucide-react";
import { SwitchToggle } from "../components/sections/common";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Badge } from "../components/ui/badge";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import PaginationControls from "../components/PaginationControls";
import { HostedRowBadges } from "./hostedRowParts";
import { CREDENTIAL_TYPE_LABELS } from "./constants";
import { HostedProviderForm } from "./HostedProviderForm";
import type { HostedPool } from "./useHostedPool";

export function HostedPoolCard({ pool }: { pool: HostedPool }) {
  const {
    hostedProviders,
    hostedProvidersError,
    hostedProviderRegionFilter,
    setHostedProviderRegionFilter,
    poolSettings,
    poolSettingsBusy,
    handleTogglePoolEnabled,
    hostedQuery,
    setHostedQuery,
    hostedPagination,
    togglingHostedProviderId,
    handleToggleHostedProviderEnabled,
    startEditHostedProvider,
    handleDeleteHostedProvider,
    deletingHostedProviderId,
  } = pool;
  // (a2) Platform-Hosted LLM Provider Pool (FREE-tier, runtime-configurable)
  return (
    <motion.div
      className="mb-5"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, delay: 0.025, ease: [0.16, 1, 0.3, 1] }}
    >
      <Card>
        <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
          <div>
            <CardTitle className="flex items-center gap-2 text-sm">
              <Server size={16} className="text-primary" /> Platform-Hosted LLM Providers
            </CardTitle>
            <p className="mt-1 text-xs text-muted-foreground">
              {hostedProviders.length} provider{hostedProviders.length === 1 ? "" : "s"} in the free-tier pool ·
              lowest-priority enabled row wins every FREE-tier chat call
            </p>
          </div>
          {poolSettings && (
            <div className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground">
                {poolSettings.enabled ? "Pool enabled" : "Pool disabled"}
              </span>
              <SwitchToggle
                on={poolSettings.enabled}
                label="Turn the whole platform-hosted LLM pool on or off"
                disabled={poolSettingsBusy}
                onClick={handleTogglePoolEnabled}
              />
            </div>
          )}
        </CardHeader>
        <CardContent className="space-y-3">
          {poolSettings && !poolSettings.enabled && (
            <Alert variant="warning">
              <Info />
              <AlertDescription>
                The hosted LLM pool is switched off - every FREE-plan tenant&apos;s chat, Ask AI, and AI workflow
                generator calls will fail until you flip this back on, even if rows below are enabled.
              </AlertDescription>
            </Alert>
          )}

          {hostedProviders.some((row) => row.region) && (
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs text-muted-foreground">Filter by region:</span>
              <Button
                variant={hostedProviderRegionFilter === "" ? "default" : "outline"}
                size="sm"
                aria-pressed={hostedProviderRegionFilter === ""}
                onClick={() => setHostedProviderRegionFilter("")}
              >
                All
              </Button>
              {[...new Set(hostedProviders.map((row) => row.region).filter(Boolean))].sort().map((region) => (
                <Button
                  key={region}
                  variant={hostedProviderRegionFilter === region ? "default" : "outline"}
                  size="sm"
                  aria-pressed={hostedProviderRegionFilter === region}
                  onClick={() => setHostedProviderRegionFilter(region)}
                >
                  {region}
                </Button>
              ))}
            </div>
          )}

          {hostedProvidersError && (
            <Alert variant="destructive">
              <AlertDescription>{hostedProvidersError}</AlertDescription>
            </Alert>
          )}
          {hostedProviders.length === 0 && !hostedProvidersError && (
            <Alert variant="warning">
              <Info />
              <AlertDescription>
                No hosted provider is configured yet - FREE-plan tenants cannot chat until at least one row exists
                here and is enabled.
              </AlertDescription>
            </Alert>
          )}

          {hostedProviders.length > 10 && (
            <Input
              value={hostedQuery}
              onChange={(e) => setHostedQuery(e.target.value)}
              placeholder="Search hosted providers…"
              className="max-w-xs"
            />
          )}

          <AnimatePresence initial={false}>
            {hostedPagination.pageItems.map((row) => (
              <motion.div
                key={row.id}
                className="rounded-lg border border-border p-4"
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -8 }}
                transition={{ duration: 0.25 }}
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <div className="flex items-center gap-2 text-sm font-semibold uppercase tracking-wide text-foreground">
                      {row.label}
                      {row.providerName && (
                        <Badge variant="outline" title="Configured via the generic provider+key flow">
                          {row.providerName}
                        </Badge>
                      )}
                      <HostedRowBadges row={row} credentialTypeLabels={CREDENTIAL_TYPE_LABELS} />
                    </div>
                    <div className="mt-0.5 font-mono text-xs text-muted-foreground">
                      {row.baseUrl}
                      {row.completionsPath ? row.completionsPath : ""} · {row.model}
                      {row.modelAutoSelected && (
                        <span
                          className="ml-1 normal-case text-muted-foreground/80"
                          title="Auto-selected - blank the model field and save to re-detect after a vendor deprecates it"
                        >
                          (auto)
                        </span>
                      )}{" "}
                      · priority {row.priority}
                    </div>
                  </div>
                  <div className="flex flex-wrap items-center gap-3">
                    <div className="flex items-center gap-2">
                      <SwitchToggle
                        on={row.enabled}
                        label={`Enable ${row.label}`}
                        disabled={togglingHostedProviderId !== null}
                        onClick={() => handleToggleHostedProviderEnabled(row)}
                      />
                      <span className="text-xs text-muted-foreground">
                        {togglingHostedProviderId === row.id ? "Updating…" : row.enabled ? "Enabled" : "Disabled"}
                      </span>
                    </div>
                    <Button variant="outline" size="sm" onClick={() => startEditHostedProvider(row)}>
                      <Pencil size={13} />
                      Edit
                    </Button>
                    <Button
                      variant="destructive"
                      size="sm"
                      onClick={() => handleDeleteHostedProvider(row)}
                      disabled={deletingHostedProviderId === row.id}
                    >
                      <Trash2 size={13} />
                      {deletingHostedProviderId === row.id ? "Removing…" : "Remove"}
                    </Button>
                  </div>
                </div>
                {row.lastValidatedAt && (
                  <div className="mt-2 font-mono text-xs text-muted-foreground">
                    Last validated {new Date(row.lastValidatedAt).toLocaleString()}
                  </div>
                )}
              </motion.div>
            ))}
          </AnimatePresence>

          <PaginationControls
            page={hostedPagination.page}
            totalPages={hostedPagination.totalPages}
            totalItems={hostedPagination.totalItems}
            pageSize={hostedPagination.pageSize}
            onPageChange={hostedPagination.setPage}
            onPageSizeChange={hostedPagination.setPageSize}
            itemLabel="providers"
          />

          <HostedProviderForm pool={pool} />
        </CardContent>
      </Card>
    </motion.div>
  );
}

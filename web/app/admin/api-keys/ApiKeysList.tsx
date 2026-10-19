"use client";

import { Select } from "../../components/ui/select";
import { useMemo, useState } from "react";
import { KeyRound, RotateCw, Search, Trash2 } from "lucide-react";
import EmptyState from "../../components/EmptyState";
import PaginationControls from "../../components/PaginationControls";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../components/ui/table";
import type { TenantApiKeyResponse } from "../../lib/api/apiKeys";
import { useTranslations } from "../../lib/i18n";
import { usePagination } from "../../lib/usePagination";

export type ApiKeyStatus = "enabled" | "disabled" | "expired";

export function apiKeyStatus(k: Pick<TenantApiKeyResponse, "expired" | "enabled">): ApiKeyStatus {
  return k.expired ? "expired" : k.enabled ? "enabled" : "disabled";
}

/** Searchable, status-filterable, paginated table of tenant API keys. */
export default function ApiKeysList({
  keys,
  busyIds,
  onToggle,
  onRotate,
  onRevoke,
}: {
  keys: TenantApiKeyResponse[];
  busyIds: Set<string>;
  onToggle: (k: TenantApiKeyResponse) => void;
  onRotate: (k: TenantApiKeyResponse) => void;
  onRevoke: (k: TenantApiKeyResponse) => void;
}) {
  const t = useTranslations("adminApiKeysPage");
  const tu = useTranslations("adminCommerceUi");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<"" | ApiKeyStatus>("");

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return keys.filter((k) => {
      if (status && apiKeyStatus(k) !== status) return false;
      if (!q) return true;
      return k.name.toLowerCase().includes(q) || k.keyPrefix.toLowerCase().includes(q);
    });
  }, [keys, query, status]);
  const pg = usePagination(filtered);

  const badge = (k: TenantApiKeyResponse) => {
    const s = apiKeyStatus(k);
    if (s === "expired") return <Badge variant="destructive">{t("statusExpired")}</Badge>;
    if (s === "enabled") return <Badge variant="default">{t("statusEnabled")}</Badge>;
    return <Badge variant="secondary">{t("statusDisabled")}</Badge>;
  };

  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="relative flex-1">
          <Label htmlFor="api-key-search" className="sr-only">
            {tu("apiKeysSearchLabel")}
          </Label>
          <Search
            size={14}
            aria-hidden="true"
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            id="api-key-search"
            className="pl-9"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              pg.setPage(1);
            }}
            placeholder={tu("apiKeysSearchPlaceholder")}
          />
        </div>
        <div className="sm:w-48">
          <Label htmlFor="api-key-status">{tu("apiKeysStatusLabel")}</Label>
          <Select
            id="api-key-status"
            value={status}
            onValueChange={(v) => {
              setStatus(v as "" | ApiKeyStatus);
              pg.setPage(1);
            }}
            options={[
              { value: "", label: tu("apiKeysStatusAll") },
              { value: "enabled", label: tu("apiKeysStatusEnabled") },
              { value: "disabled", label: tu("apiKeysStatusDisabled") },
              { value: "expired", label: tu("apiKeysStatusExpired") },
            ]}
          />
        </div>
      </div>

      {filtered.length === 0 ? (
        <EmptyState icon={KeyRound} title={tu("noMatches")} />
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("colName")}</TableHead>
                <TableHead>{t("colKey")}</TableHead>
                <TableHead>{t("colPermissions")}</TableHead>
                <TableHead>{t("colLastUsed")}</TableHead>
                <TableHead>{t("colStatus")}</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {pg.pageItems.map((key) => (
                <TableRow key={key.id}>
                  <TableCell>
                    {key.name}
                    <div className="text-xs text-muted-foreground">
                      {t("createdBy", { who: key.createdBy ?? "?" })}
                      {key.expiresAt ? ` · ${t("expires", { when: key.expiresAt.slice(0, 10) })}` : ""}
                    </div>
                  </TableCell>
                  <TableCell className="font-mono text-xs">vdak_{key.keyPrefix}…</TableCell>
                  <TableCell className="max-w-xs whitespace-normal">
                    {key.permissions.length === 0 ? (
                      <span className="text-muted-foreground">{t("permissionsNone")}</span>
                    ) : (
                      key.permissions.join(", ")
                    )}
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">
                    {key.lastUsedAt ? key.lastUsedAt.slice(0, 16).replace("T", " ") : t("neverUsed")}
                  </TableCell>
                  <TableCell>{badge(key)}</TableCell>
                  <TableCell className="whitespace-nowrap">
                    <Button type="button" variant="ghost" size="sm" disabled={busyIds.has(key.id)} onClick={() => onToggle(key)}>
                      {key.enabled ? t("disableButton") : t("enableButton")}
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={busyIds.has(key.id)}
                      onClick={() => onRotate(key)}
                      aria-label={t("rotateAria", { name: key.name })}
                    >
                      <RotateCw size={13} aria-hidden="true" />
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={busyIds.has(key.id)}
                      onClick={() => onRevoke(key)}
                      aria-label={t("revokeAria", { name: key.name })}
                    >
                      <Trash2 size={13} aria-hidden="true" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      <div className="mt-3">
        <PaginationControls
          page={pg.page}
          totalPages={pg.totalPages}
          totalItems={pg.totalItems}
          pageSize={pg.pageSize}
          onPageChange={pg.setPage}
          onPageSizeChange={pg.setPageSize}
          itemLabel={tu("itemKeys")}
        />
      </div>
    </div>
  );
}

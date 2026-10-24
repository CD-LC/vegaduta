"use client";

import { useEffect, useId, useRef, useState } from "react";
import { X } from "lucide-react";
import { useLocale } from "../../lib/i18n";
import type { SettingCatalogEntry, SettingHistoryItem, SettingScope, SettingsClient } from "../../lib/api/settings";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { Skeleton } from "../ui/skeleton";
import { useSettingsText } from "./useSettingsText";

type State =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; items: SettingHistoryItem[] };

function formatWhen(iso: string, locale: string): string {
  const d = new Date(iso);
  if (!iso || Number.isNaN(d.getTime())) return iso;
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" }).format(d);
  } catch {
    return d.toISOString();
  }
}

/**
 * Right-hand drawer with the change history of one setting at the scope being edited: who changed it, when,
 * why, and a before/after diff of the value. Newest first. Escape or the close button closes it and focus
 * returns to the button that opened it.
 */
export default function SettingHistoryDrawer({
  entry,
  scope,
  agentId,
  client,
  onClose,
}: {
  /** The setting to show; null keeps the drawer closed. */
  entry: SettingCatalogEntry | null;
  scope: SettingScope;
  agentId?: string;
  client: SettingsClient;
  onClose: () => void;
}) {
  const { ui, label, formatValue } = useSettingsText();
  const locale = useLocale();
  const [state, setState] = useState<State>({ status: "loading" });
  const [nonce, setNonce] = useState(0);
  const panelRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const key = entry?.key;

  useDialogFocus(panelRef, closeRef, onClose, { enabled: entry != null });

  useEffect(() => {
    if (!key) return undefined;
    let cancelled = false;
    setState({ status: "loading" });
    client
      .getHistory(key, scope, agentId)
      .then((items) => {
        if (!cancelled) setState({ status: "ready", items: [...items].sort((a, b) => b.version - a.version) });
      })
      .catch((err: unknown) => {
        if (!cancelled) setState({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => {
      cancelled = true;
    };
  }, [client, key, scope, agentId, nonce]);

  if (!entry) return null;

  return (
    <div className="fixed inset-0 z-[90] flex justify-end bg-black/40" data-testid="setting-history">
      {/* The backdrop is a plain click target; keyboard users close with Escape (useDialogFocus) or the button. */}
      <div className="absolute inset-0" onClick={onClose} aria-hidden="true" />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className="relative flex h-full w-full max-w-md flex-col overflow-y-auto border-l border-border bg-card p-4 text-card-foreground shadow-xl sm:p-6"
      >
        <div className="mb-4 flex items-start justify-between gap-3">
          <div>
            <h2 id={titleId} className="text-base font-semibold">
              {ui("history.title", { label: label(entry) })}
            </h2>
            <p className="text-xs text-muted-foreground">{ui(scope === "agent" ? "history.scopeAgent" : "history.scopeTenant")}</p>
          </div>
          <Button ref={closeRef} type="button" variant="ghost" size="icon" aria-label={ui("history.close")} onClick={onClose}>
            <X aria-hidden="true" />
          </Button>
        </div>

        {state.status === "loading" ? (
          <div role="status" aria-busy="true" aria-label={ui("history.loading")} className="space-y-3">
            <Skeleton className="h-16" />
            <Skeleton className="h-16" />
          </div>
        ) : state.status === "error" ? (
          <Alert variant="destructive">
            <AlertDescription>
              <span>{ui("history.error", { message: state.message })}</span>
              <Button type="button" size="sm" variant="outline" onClick={() => setNonce((n) => n + 1)}>
                {ui("retry")}
              </Button>
            </AlertDescription>
          </Alert>
        ) : state.items.length === 0 ? (
          <p className="text-sm text-muted-foreground" data-testid="setting-history-empty">
            {ui("history.empty")}
          </p>
        ) : (
          <ol className="space-y-3" aria-label={ui("history.listLabel")}>
            {state.items.map((h) => (
              <li key={h.version} className="rounded-md border border-border p-3 text-sm" data-testid={`history-item-${h.version}`}>
                <div className="flex flex-wrap items-baseline justify-between gap-x-3 text-xs text-muted-foreground">
                  <span>{ui("history.version", { version: h.version })}</span>
                  <time dateTime={h.at}>{formatWhen(h.at, locale)}</time>
                </div>
                <p className="mt-1">
                  <span className="sr-only">{ui("history.before")} </span>
                  <del className="text-muted-foreground">{formatValue(entry, h.previousValue)}</del>
                  <span aria-hidden="true"> → </span>
                  <span className="sr-only">{ui("history.after")} </span>
                  <ins className="font-medium no-underline">{formatValue(entry, h.value)}</ins>
                </p>
                <p className="mt-1 text-xs">
                  <span className="text-muted-foreground">{ui("history.by")}</span> {h.actor || ui("history.unknownActor")}
                </p>
                {h.reason ? (
                  <p className="mt-1 text-xs">
                    <span className="text-muted-foreground">{ui("history.reason")}</span> {h.reason}
                  </p>
                ) : null}
              </li>
            ))}
          </ol>
        )}
      </div>
    </div>
  );
}

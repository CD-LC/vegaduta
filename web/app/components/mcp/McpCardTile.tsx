"use client";

import Link from "next/link";
import { ChevronRight, Loader2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Switch } from "../ui/switch";
import type { McpCard } from "../McpPanel/helpers";
import { CatalogVerifiedBadge, ReadinessBadge } from "../McpPanel/parts";
import { ToolPinBadge } from "../McpPanel/ToolPinStatus";
import { cardCat, cardDesc, cardIcon, cardMeta, cardState, cardTitle } from "./cardPresentation";

export interface McpCardTileProps {
  card: McpCard;
  isSelected: boolean;
  /** Transitional per-card state: adding | connecting | enabling | testing. */
  phase?: string;
  pins: { statuses: Record<string, any>; failed: Record<string, any>; loading: boolean };
  addingKey: string | null;
  connectingKey: string | null;
  /** Lets the panel scroll a card into view after an Add/Connect click. */
  registerRef: (selKey: string, el: HTMLElement | null) => void;
  onSelect: (selKey: string) => void;
  onToggleServer: (server: any) => void | Promise<void>;
  onRequestEnable: (server: any, selKey?: string) => void;
  onAddFromCatalog: (entry: any) => void;
  onConnectOAuth: (entry: any) => void;
}

/**
 * Marketplace-style tile. Header: category chip, title, meta line with the live state dot. Body:
 * two-line description. Footer: steady-state signal (provenance/readiness/tool-pin badges) on the
 * left, the real next-step CTA on the right; the transient phase pill takes over the left slot
 * while a round-trip is in flight. Registered MCP servers keep a quick-toggle Switch in the header.
 */
export default function McpCardTile({
  card,
  isSelected,
  phase,
  pins,
  addingKey,
  connectingKey,
  registerRef,
  onSelect,
  onToggleServer,
  onRequestEnable,
  onAddFromCatalog,
  onConnectOAuth,
}: McpCardTileProps) {
  const t = useTranslations("mcpPanel");

  function renderCardCta(card: McpCard) {
    if (card.kind === "catalog") {
      const entry = card.data;
      // Same branch order CatalogCardInspector uses: a ready entry is a
      // one-click Activate; OAuth precedes canConnect because an
      // OAuth-backed entry may still carry an informational dynamicUrl;
      // canConnect means real credentials are required, which only the
      // inspector's form can collect - so the card CTA just focuses it.
      const canConnect = Boolean(entry.dynamicCommand || entry.dynamicUrl) && (entry.requiredEnvVars?.length ?? 0) > 0;
      if (entry.ready || (!entry.requiresOAuth && !canConnect)) {
        const busy = addingKey === entry.key;
        return (
          <Button
            type="button"
            size="sm"
            className="relative z-10 ml-auto h-7 shrink-0 gap-1 whitespace-nowrap px-3"
            disabled={busy}
            onClick={() => onAddFromCatalog(entry)}
          >
            {busy && <Loader2 aria-hidden="true" size={12} className="animate-spin" />}
            {entry.ready ? t("activateButton") : t("addButton")}
          </Button>
        );
      }
      if (entry.requiresOAuth) {
        const busy = connectingKey === entry.key;
        return (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="relative z-10 ml-auto h-7 shrink-0 gap-1 whitespace-nowrap px-3"
            disabled={busy}
            onClick={() => onConnectOAuth(entry)}
          >
            {busy && <Loader2 aria-hidden="true" size={12} className="animate-spin" />}
            Connect
          </Button>
        );
      }
      return (
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="relative z-10 ml-auto h-7 shrink-0 gap-1 whitespace-nowrap px-3"
          onClick={() => onSelect(card.selKey)}
        >
          Set up <ChevronRight size={12} aria-hidden="true" />
        </Button>
      );
    }
    if (card.kind === "google-connect") {
      return (
        <Button
          variant="outline"
          size="sm"
          className="relative z-10 ml-auto h-7 shrink-0 gap-1 whitespace-nowrap px-3"
          asChild
        >
          <Link href="/admin/integrations">{t("goToIntegrations")}</Link>
        </Button>
      );
    }
    return (
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="relative z-10 ml-auto h-7 shrink-0 gap-1 whitespace-nowrap px-2.5 text-muted-foreground hover:text-foreground"
        onClick={() => onSelect(card.selKey)}
      >
        Details <ChevronRight size={12} aria-hidden="true" />
      </Button>
    );
  }

  function renderCard() {
    const Icon = cardIcon(card);
    const dimmed = card.kind === "catalog" || (card.kind === "mcp" && !card.data.enabled);
    // "Not live yet" tiles (unadded catalog entries, the Google connect
    // placeholder, disabled servers) sit one elevation rung lower - sunken
    // reads as "available to add" next to the glossy live cards.
    const sunken = dimmed || card.kind === "google-connect";
    const state = cardState(card);
    const stateLive = card.kind === "mcp" && (card.data.connectionStatus === "CONNECTING" || phase === "testing");
    return (
      <div
        ref={(el) => {
          registerRef(card.selKey, el);
        }}
        className="h-full"
      >
        <Card
          interactive
          cat={cardCat(card)}
          elevation={sunken ? "sunken" : undefined}
          className={cn("relative h-full gap-0 py-0", isSelected && "border-primary/60 ring-1 ring-primary/40")}
        >
          {/* One transparent stretched button owns "select this card"; the
           * visible header/body are plain blocks. Raw <button>s inherit the
           * global border + fill + padding base rule, which is what used to
           * draw a box inside the card for the header and the description.
           * The Switch and footer CTAs sit above it (z-10). */}
          <button
            type="button"
            onClick={() => onSelect(card.selKey)}
            aria-label={cardTitle(card, t)}
            aria-pressed={isSelected}
            className="absolute inset-0 z-0 h-full w-full rounded-[inherit] border-0 bg-transparent p-0 shadow-none hover:bg-transparent active:scale-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--accent-600)]"
          />
          <div className="pointer-events-none flex items-start gap-3 px-4 pt-4">
            <span className={cn("cat-chip cat-chip-lg shrink-0", dimmed && "opacity-60")}>
              <Icon size={18} />
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-semibold leading-snug text-foreground" title={cardTitle(card, t)}>
                {cardTitle(card, t)}
              </div>
              <div
                className={cn(
                  "mt-0.5 flex flex-wrap items-center gap-x-1.5 text-xs text-muted-foreground",
                  state === "var(--state-error)" && "text-destructive"
                )}
              >
                {state && (
                  <span
                    aria-hidden="true"
                    className={cn("state-dot", stateLive && "state-dot-live")}
                    style={{ ["--state" as string]: state } as any}
                  />
                )}
                {cardMeta(card, t)}
              </div>
            </div>
            {card.kind === "mcp" && (
              <Switch
                checked={card.data.enabled}
                aria-label={t("toggleServerAriaLabel", { name: card.data.name })}
                className="pointer-events-auto relative z-10 shrink-0 before:absolute before:-inset-[5px] before:content-['']"
                onCheckedChange={() => {
                  if (card.data.enabled) void onToggleServer(card.data);
                  else onRequestEnable(card.data, card.selKey);
                }}
              />
            )}
          </div>
          <div className="pointer-events-none flex-1 px-4 pb-4 pt-3">
            <p className="m-0 line-clamp-2 min-h-[2.5rem] text-[13px] leading-5 text-muted-foreground">
              {cardDesc(card, t)}
            </p>
          </div>
          <div className="relative z-10 mt-auto flex min-h-12 items-center justify-between gap-2 border-t border-border/60 px-4 py-2.5">
            {phase ? (
              /* Transitional per-card state - primary feedback for the
               * ~seconds right after Add/Activate/Connect/Enable is clicked;
               * the steady-state footer signals below take back over once it
               * clears. */
              <span
                className="state-pill"
                style={
                  {
                    ["--state" as string]: phase === "testing" ? "var(--state-warning)" : "var(--state-running)",
                  } as any
                }
              >
                <Loader2 aria-hidden="true" size={11} className="animate-spin" />
                {phase === "adding"
                  ? "Adding…"
                  : phase === "connecting"
                    ? "Connecting…"
                    : phase === "enabling"
                      ? "Enabling…"
                      : "Testing connection…"}
              </span>
            ) : card.kind === "builtin" ? (
              <span
                className="min-w-0 truncate text-[0.7rem] text-muted-foreground"
                title={t(card.data.availableFromKey)}
              >
                {t(card.data.availableFromKey)}
              </span>
            ) : card.kind === "google" ? (
              <span className="state-pill" style={{ ["--state" as string]: "var(--state-success)" } as any}>
                <span className="state-dot" aria-hidden="true" />
                {t("connectedLabel")}
              </span>
            ) : card.kind === "catalog" ? (
              <CatalogVerifiedBadge compact />
            ) : (
              <span className="flex min-w-0 flex-wrap items-center gap-1">
                <ReadinessBadge server={card.data} compact />
                {/* Only a real MCP server card has an id to pin; the "Connect Google" tile (kind google-connect) has no data. */}
                {card.kind === "mcp" && card.data && (
                  <ToolPinBadge
                    status={pins.statuses[card.data.id]}
                    failed={pins.failed[card.data.id]}
                    loading={pins.loading}
                  />
                )}
              </span>
            )}
            {renderCardCta(card)}
          </div>
        </Card>
      </div>
    );
  }

  return renderCard();
}

"use client";

import { useMemo, useState } from "react";
import { Check, MessageSquarePlus, Pencil, Pin, PinOff, Search, Trash2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import type { ThreadSummary } from "./assistantTypes";
import { groupThreads, type ThreadGroupKey } from "./threadIndex";

const GROUP_KEYS: Record<ThreadGroupKey, string> = {
  pinned: "groupPinned",
  today: "groupToday",
  yesterday: "groupYesterday",
  week: "groupWeek",
  earlier: "groupEarlier",
};

/** The left rail: the local thread index, grouped by day, with pin and remove. */
export default function ThreadList({
  threads,
  loaded,
  activeId,
  busy,
  onSelect,
  onNew,
  onTogglePin,
  onRename,
  onRemove,
}: {
  threads: ThreadSummary[];
  loaded: boolean;
  activeId: string | null;
  /** A turn is streaming: switching threads mid-reply is not offered. */
  busy: boolean;
  onSelect: (id: string) => void;
  onNew: () => void;
  onTogglePin: (id: string) => void;
  onRename: (id: string, title: string) => void;
  onRemove: (id: string) => void;
}) {
  const t = useTranslations("assistant");
  const [query, setQuery] = useState("");
  const [editing, setEditing] = useState<{ id: string; title: string } | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? threads.filter((row) => row.title.toLowerCase().includes(q)) : threads;
  }, [threads, query]);
  const groups = useMemo(() => groupThreads(shown, Date.now()), [shown]);

  function commitRename() {
    if (editing && editing.title.trim()) onRename(editing.id, editing.title.trim());
    setEditing(null);
  }
  return (
    <nav aria-label={t("threadsLabel")} className="flex h-full min-h-0 flex-col">
      <div className="flex items-center justify-between gap-2 px-3 py-3">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t("threadsLabel")}</h2>
        <Button type="button" size="sm" variant="outline" onClick={onNew} disabled={busy} className="gap-1">
          <MessageSquarePlus size={14} aria-hidden="true" />
          {t("newThread")}
        </Button>
      </div>
      {threads.length > 4 && (
        <div className="relative px-3 pb-2">
          <Search size={13} className="pointer-events-none absolute left-5 top-2.5 text-muted-foreground" aria-hidden="true" />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("searchThreads")}
            aria-label={t("searchThreads")}
            className="h-8 pl-7 text-sm"
          />
        </div>
      )}
      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
        {query.trim() && shown.length === 0 && <p className="px-2 text-xs text-muted-foreground">{t("noThreadMatches")}</p>}
        {loaded && threads.length === 0 && <p className="px-2 text-xs text-muted-foreground">{t("noThreads")}</p>}
        {groups.map((group) => (
          <section key={group.key} className="mb-3">
            <h3 className="px-2 pb-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
              {t(GROUP_KEYS[group.key])}
            </h3>
            <ul className="space-y-0.5">
              {group.rows.map((row) => {
                const active = row.id === activeId;
                if (editing?.id === row.id) {
                  return (
                    <li key={row.id} className="flex items-center gap-1 px-1">
                      <Input
                        ref={(el) => el?.focus()}
                        value={editing.title}
                        maxLength={80}
                        aria-label={t("renameThread")}
                        className="h-8 text-sm"
                        onChange={(e) => setEditing({ id: row.id, title: e.target.value })}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") commitRename();
                          if (e.key === "Escape") setEditing(null);
                        }}
                      />
                      <Button type="button" size="icon" variant="ghost" className="size-7" aria-label={t("save")} onClick={commitRename}>
                        <Check size={13} aria-hidden="true" />
                      </Button>
                    </li>
                  );
                }
                if (confirming === row.id) {
                  return (
                    <li key={row.id} className="flex items-center gap-1 rounded-md bg-destructive/10 px-2 py-1.5 text-sm">
                      <span className="min-w-0 flex-1 truncate text-destructive">{t("deleteThreadQuestion")}</span>
                      <Button
                        type="button"
                        size="sm"
                        variant="destructive"
                        className="h-6 px-2 text-xs"
                        onClick={() => {
                          setConfirming(null);
                          onRemove(row.id);
                        }}
                      >
                        {t("delete")}
                      </Button>
                      <Button type="button" size="sm" variant="ghost" className="h-6 px-2 text-xs" onClick={() => setConfirming(null)}>
                        {t("cancel")}
                      </Button>
                    </li>
                  );
                }
                return (
                  <li key={row.id} className="group/thread relative">
                    <button
                      type="button"
                      onClick={() => onSelect(row.id)}
                      disabled={busy && !active}
                      aria-current={active ? "page" : undefined}
                      className={cn(
                        "w-full truncate rounded-md px-2 py-1.5 pr-[4.5rem] text-left text-sm transition-colors",
                        active ? "bg-secondary text-foreground" : "text-muted-foreground hover:bg-secondary/60 hover:text-foreground",
                        "disabled:cursor-not-allowed disabled:opacity-60"
                      )}
                      title={row.title}
                    >
                      {row.title}
                    </button>
                    <span className="absolute inset-y-0 right-1 flex items-center gap-0.5 opacity-0 focus-within:opacity-100 group-hover/thread:opacity-100 [@media(hover:none)]:opacity-100">
                      <Button
                        type="button"
                        size="icon"
                        variant="ghost"
                        className="size-6"
                        aria-label={row.pinned ? t("unpin") : t("pin")}
                        title={row.pinned ? t("unpin") : t("pin")}
                        onClick={() => onTogglePin(row.id)}
                      >
                        {row.pinned ? <PinOff size={12} aria-hidden="true" /> : <Pin size={12} aria-hidden="true" />}
                      </Button>
                      <Button
                        type="button"
                        size="icon"
                        variant="ghost"
                        className="size-6"
                        aria-label={t("renameThread")}
                        title={t("renameThread")}
                        onClick={() => setEditing({ id: row.id, title: row.title })}
                      >
                        <Pencil size={12} aria-hidden="true" />
                      </Button>
                      <Button
                        type="button"
                        size="icon"
                        variant="ghost"
                        className="size-6"
                        aria-label={t("removeThread")}
                        title={t("removeThread")}
                        disabled={busy && active}
                        onClick={() => setConfirming(row.id)}
                      >
                        <Trash2 size={12} aria-hidden="true" />
                      </Button>
                    </span>
                  </li>
                );
              })}
            </ul>
          </section>
        ))}
      </div>
    </nav>
  );
}

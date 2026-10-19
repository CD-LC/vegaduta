"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import Select from "../Select";
import { ACTIVE_PROJECT_LABEL, activeProjectSlug, hasWrittenNotes, projectBlocks, projectSlug } from "../../memory/memoryFilters";
import type { MemoryOverview } from "../../memory/memoryTypes";

/**
 * Chat-side memory controls, mounted by ChatPanel with one line each.
 *
 * ActiveProjectSwitcher: pick which project's note rides along on every turn
 * (the reserved `active_project` memory block), without leaving the chat.
 * Renders nothing unless core reports the feature on (activeProjectEnabled),
 * and nothing at all if the memory read fails - it is a convenience and must
 * never put an error in front of a conversation. When the selected agent does
 * not have shared memory on, notes are not used for it, so instead of a
 * picker that would do nothing it shows a one-line hint pointing at /memory
 * (only when the user has actually written a note that is being left out).
 */
const NONE = "";
const LINK_CLASS = "font-medium text-primary underline-offset-4 hover:underline";

export function ActiveProjectSwitcher({
  agent,
  className,
}: {
  agent: { id: string; shareMemoryWithTenant?: boolean } | null | undefined;
  className?: string;
}) {
  const t = useTranslations("chatMemory");
  const [overview, setOverview] = useState<MemoryOverview | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(() => {
    // Guarded call: a surface whose api object lacks the method (an older
    // bundle, a test double) just shows no switcher.
    return Promise.resolve()
      .then(() => api.getMemoryQuiet())
      .then((o: MemoryOverview | null) => setOverview(o || null))
      .catch(() => setOverview(null));
  }, []);

  useEffect(() => {
    void load();
  }, [load, agent?.id]);

  if (!agent || !overview?.available || !overview.blocksEnabled || overview.activeProjectEnabled !== true) return null;

  if (!agent.shareMemoryWithTenant) {
    // Nothing written means nothing is being left out: no standing nag.
    if (!hasWrittenNotes(overview.blocks)) return null;
    return (
      <p className={cn("text-xs text-muted-foreground", className)} data-testid="chat-memory-notes-off">
        {t("notesOffHint")}{" "}
        <Link href="/memory" className={LINK_CLASS}>
          {t("notesOffLink")}
        </Link>
      </p>
    );
  }

  const projects = projectBlocks(overview.blocks);
  if (projects.length === 0) {
    return (
      <p className={cn("text-xs text-muted-foreground", className)} data-testid="chat-memory-no-projects">
        {t("noProjectsHint")}{" "}
        <Link href="/memory" className={LINK_CLASS}>
          {t("noProjectsLink")}
        </Link>
      </p>
    );
  }

  const active = activeProjectSlug(overview.blocks) || NONE;

  const change = async (slug: string) => {
    if (slug === active || saving) return;
    setSaving(true);
    try {
      if (slug) await api.saveMemoryBlock(ACTIVE_PROJECT_LABEL, slug);
      else await api.deleteMemoryBlock(ACTIVE_PROJECT_LABEL);
    } catch {
      /* toJson already showed the server's reason */
    } finally {
      await load();
      setSaving(false);
    }
  };

  return (
    <div className={cn("flex flex-wrap items-center gap-2 text-xs text-muted-foreground", className)} data-testid="chat-active-project">
      <span>{t("projectLabel")}</span>
      <Select
        aria-label={t("projectLabel")}
        value={active}
        disabled={saving}
        onChange={(e) => void change(String(e.target.value ?? ""))}
        options={[
          { value: NONE, label: t("projectNone") },
          ...projects.map((b) => ({ value: projectSlug(b.label), label: projectSlug(b.label) })),
        ]}
      />
      <span>{active ? t("projectActiveHint") : t("projectNoneHint")}</span>
    </div>
  );
}

/** "Manage all memory" - the way from a chat's per-agent list to the full Memory page. */
export function ManageMemoryLink({ className }: { className?: string }) {
  const t = useTranslations("chatMemory");
  return (
    <Link href="/memory" className={cn("text-xs", LINK_CLASS, className)}>
      {t("manageAll")}
    </Link>
  );
}

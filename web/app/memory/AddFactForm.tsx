"use client";

import { useState } from "react";
import { Plus } from "lucide-react";
import { api } from "../lib/api";
import Select from "../components/Select";
import { Button } from "../components/ui/button";
import { Textarea } from "../components/ui/textarea";
import { useTranslations } from "../lib/i18n";
import type { MemoryAgent } from "./memoryTypes";

/** Sentinel for "every assistant that shares memory" in the who-remembers picker. */
const SHARED = "__shared__";

/**
 * "Add a memory" - type a fact and choose who should remember it: one agent
 * (private to it) or the shared pool. Only mounted when the server reports
 * selfServiceEnabled, so it never offers a control whose endpoint is off.
 */
export default function AddFactForm({
  agents,
  maxChars,
  onAdded,
}: {
  agents: MemoryAgent[];
  maxChars: number;
  onAdded: () => void;
}) {
  const t = useTranslations("memoryPage");
  const [text, setText] = useState("");
  const [target, setTarget] = useState<string>(SHARED);
  const [busy, setBusy] = useState(false);

  const trimmed = text.trim();
  const over = text.length > maxChars;

  const submit = async () => {
    if (!trimmed || over || busy) return;
    setBusy(true);
    try {
      if (target === SHARED) await api.addMemoryFact(trimmed, "shared");
      else await api.addMemoryFact(trimmed, "agent", target);
      setText("");
      onAdded();
    } catch {
      /* toJson already showed the server's message; keep the text so it can be fixed */
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-2 rounded-lg border border-border bg-card p-3" data-testid="memory-add-fact">
      <p className="text-sm font-medium text-foreground">{t("addFactHeading")}</p>
      <Textarea
        value={text}
        rows={2}
        onChange={(e) => setText(e.target.value)}
        placeholder={t("addFactPlaceholder")}
        aria-label={t("addFactHeading")}
      />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs text-muted-foreground">{t("addFactWho")}</span>
          <Select
            aria-label={t("addFactWho")}
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            searchable={agents.length > 8}
            options={[
              { value: SHARED, label: t("addFactShared") },
              ...agents.map((a) => ({ value: a.id, label: t("addFactAgent", { name: a.name }) })),
            ]}
          />
        </div>
        <div className="flex items-center gap-2">
          <span className={`text-xs tabular-nums ${over ? "text-destructive" : "text-muted-foreground"}`}>
            {t("charCount", { count: text.length, max: maxChars })}
          </span>
          <Button size="sm" disabled={busy || !trimmed || over} onClick={submit}>
            <Plus size={14} aria-hidden="true" /> {t("addFactButton")}
          </Button>
        </div>
      </div>
      {target === SHARED && <p className="text-xs text-muted-foreground">{t("addFactSharedHint")}</p>}
    </div>
  );
}

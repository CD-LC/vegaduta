"use client";

import { useState } from "react";
import { Copy, Check } from "lucide-react";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";

// Task-id chip - direct feedback: "button to copy the execution ID is not
// professional - make it as per template design specification". Two visibly
// distinct segments (a labeled mono-id readout, then a separated icon-only
// copy action) instead of one ambiguous pill, with its own transient
// "copied" state on the action segment only (now token-driven shadcn styling,
// not the retired .ax-qt-idchip CSS). Used from both the live-trace
// header and the result card below so the two spots never drift apart.
export default function TaskIdChip({ taskId }: { taskId: string }) {
  const t = useTranslations("simpleTaskRunner");
  const [copied, setCopied] = useState(false);

  function handleCopy() {
    if (!navigator?.clipboard?.writeText) return;
    navigator.clipboard.writeText(taskId).then(
      () => {
        setCopied(true);
        toast.success(t("taskIdCopiedToast"));
        setTimeout(() => setCopied(false), 1500);
      },
      () => {}
    );
  }

  return (
    <span className="inline-flex items-center overflow-hidden rounded-md border border-border bg-secondary/40 text-xs">
      <span className="inline-flex items-center gap-1.5 py-1 ps-2.5 pe-2 font-mono text-muted-foreground">
        <span className="size-1.5 rounded-full bg-emerald-500" aria-hidden="true" />
        {t("taskIdLabel", { id: taskId.slice(0, 8) })}
      </span>
      <button
        type="button"
        className={cn(
          "flex items-center border-0 border-s border-border bg-transparent px-2 py-1.5 shadow-none transition-colors hover:bg-accent",
          copied ? "text-emerald-500" : "text-muted-foreground hover:text-foreground"
        )}
        onClick={handleCopy}
        title={t("copyTaskIdTitle")}
        aria-label={t("copyTaskIdAriaLabel")}
      >
        {copied ? <Check aria-hidden="true" size={11} /> : <Copy aria-hidden="true" size={11} />}
      </button>
    </span>
  );
}

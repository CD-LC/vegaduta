"use client";

import { useEffect, useRef, useState } from "react";
import { ChevronLeft, ChevronRight, Pencil, RefreshCw } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { cn } from "../lib/utils";
import { siblingLabel } from "../lib/chatBranching";
import { Button } from "./ui/button";
import { Textarea } from "./ui/textarea";

// Per-message controls for chat branching (edit / regenerate / flip between versions).
// Presentational only: ChatPanel owns the state through useChatBranching. Rendered only when the
// server reports app.chat.branching.enabled, so a deployment with the flag off never shows them.

/** The "< 2/3 >" version switcher. Clamped, not wrapping: the buttons disable at either end. */
export function BranchSwitcher({
  index,
  count,
  disabled,
  onPrevious,
  onNext,
}: {
  index: number;
  count: number;
  disabled?: boolean;
  onPrevious: () => void;
  onNext: () => void;
}) {
  const t = useTranslations("chatBranching");
  const label = siblingLabel(index, count);
  return (
    <div role="group" aria-label={t("versionGroup")} className="inline-flex items-center gap-0.5" data-testid="branch-switcher">
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="size-6"
        aria-label={t("previousVersion")}
        disabled={disabled || index <= 0}
        onClick={onPrevious}
      >
        <ChevronLeft className="size-3.5" aria-hidden="true" />
      </Button>
      <span
        className="min-w-8 text-center text-xs tabular-nums text-muted-foreground"
        aria-label={t("versionAria", { index: index + 1, count })}
        aria-live="polite"
      >
        {label}
      </span>
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="size-6"
        aria-label={t("nextVersion")}
        disabled={disabled || index >= count - 1}
        onClick={onNext}
      >
        <ChevronRight className="size-3.5" aria-hidden="true" />
      </Button>
    </div>
  );
}

/**
 * The action row under one message: Edit (user messages), Regenerate (assistant messages) and the
 * version switcher when there is more than one version. The actions appear on hover and on keyboard
 * focus anywhere inside the message (the parent carries `group`), and always on touch screens; the
 * switcher stays visible because "there are other versions" is information, not a shortcut.
 */
export function ChatBranchTurnBar({
  role,
  siblingIndex,
  siblingCount,
  busy,
  onEdit,
  onRegenerate,
  onSwitch,
}: {
  role: string;
  siblingIndex: number;
  siblingCount: number;
  busy: boolean;
  onEdit: () => void;
  onRegenerate: () => void;
  onSwitch: (delta: number) => void;
}) {
  const t = useTranslations("chatBranching");
  const hasVersions = siblingCount > 1;
  return (
    <div className="flex items-center gap-1" data-testid="branch-turn-bar">
      {hasVersions && (
        <BranchSwitcher
          index={siblingIndex}
          count={siblingCount}
          disabled={busy}
          onPrevious={() => onSwitch(-1)}
          onNext={() => onSwitch(1)}
        />
      )}
      <span
        className={cn(
          "inline-flex items-center opacity-0 transition-opacity",
          "group-hover:opacity-100 group-focus-within:opacity-100 focus-within:opacity-100 [@media(hover:none)]:opacity-100"
        )}
      >
        {role === "user" && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="size-6"
            aria-label={t("edit")}
            title={t("edit")}
            disabled={busy}
            onClick={onEdit}
          >
            <Pencil className="size-3.5" aria-hidden="true" />
          </Button>
        )}
        {role === "assistant" && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="size-6"
            aria-label={t("regenerate")}
            title={t("regenerate")}
            disabled={busy}
            onClick={onRegenerate}
          >
            <RefreshCw className="size-3.5" aria-hidden="true" />
          </Button>
        )}
      </span>
    </div>
  );
}

/**
 * Inline editor that replaces a user bubble. Keyboard: Ctrl/Cmd+Enter saves, Escape cancels, and
 * focus lands in the textarea (caret at the end) when it opens. Blank text cannot be submitted;
 * unchanged text can (it makes a fresh version of the same question, like asking it again).
 */
export function EditMessageForm({
  initialText,
  busy,
  onSave,
  onCancel,
}: {
  initialText: string;
  busy: boolean;
  onSave: (text: string) => void;
  onCancel: () => void;
}) {
  const t = useTranslations("chatBranching");
  const [text, setText] = useState(initialText);
  const ref = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.focus();
    el.setSelectionRange(el.value.length, el.value.length);
  }, []);

  const canSave = !busy && text.trim().length > 0;

  function submit() {
    if (canSave) onSave(text);
  }

  return (
    <form
      className="flex w-full max-w-[85%] flex-col gap-2"
      data-testid="branch-edit-form"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <Textarea
        ref={ref}
        value={text}
        aria-label={t("editLabel")}
        aria-describedby="branch-edit-hint"
        disabled={busy}
        maxLength={8000}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Escape") {
            e.preventDefault();
            onCancel();
          } else if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
            e.preventDefault();
            submit();
          }
        }}
      />
      <p id="branch-edit-hint" className="text-xs text-muted-foreground">
        {t("editHint")}
      </p>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" size="sm" onClick={onCancel} disabled={busy}>
          {t("cancel")}
        </Button>
        <Button type="submit" size="sm" disabled={!canSave}>
          {t("saveAndSubmit")}
        </Button>
      </div>
    </form>
  );
}

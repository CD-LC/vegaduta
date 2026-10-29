"use client";

import { useLayoutEffect, useRef, type ClipboardEvent, type KeyboardEvent, type ReactNode } from "react";
import { ArrowUp, CloudUpload, Phone, Square } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { RequestLifecycleSnapshot } from "../../lib/requestLifecycle";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { Textarea } from "../ui/textarea";
import ComposerLimitNote, { composerLimitProps, isOverComposerLimit } from "../ComposerLimitNote";
import { AttachmentChips, type AgentModalities, type PendingAttachment } from "../ChatAttachments";
import { RequestStatusIndicator } from "../reliability/RequestStatusIndicator";
import StreamReconnectingNotice, { type StreamReconnectingState } from "../StreamReconnectingNotice";
import { SLASH_COMMANDS, parseSlash } from "./dispatch";
import AttachMenu from "./AttachMenu";

export interface ComposerAttachments {
  modalities: AgentModalities | null;
  /** Files are saved in the projects folder, where the Assistant can search them. */
  searchable: boolean;
  items: PendingAttachment[];
  error: string | null;
  attaching: boolean;
  onFiles: (files: File[]) => void;
  onRemove: (id: string) => void;
}

/**
 * The one box. Enter sends, Shift+Enter breaks a line, a leading "/" lists the slash commands. While a
 * turn streams the send button becomes Stop and, when background jobs are reachable, "Keep going in the
 * background" hands the rest of the work to a job (rung 2).
 */
export default function AssistantComposer({
  value,
  onChange,
  onSend,
  onStop,
  onBackground,
  sending,
  disabled,
  attachments,
  snapshot,
  reconnecting,
  onCamera,
  onCall,
  toolbar,
  placeholder,
}: {
  /** The agent picker and mode chips (ComposerModeBar), rendered under the box. */
  toolbar?: ReactNode;
  /** Overrides the default placeholder (a mode or another agent words it differently). */
  placeholder?: string;
  value: string;
  onChange: (next: string) => void;
  onSend: () => void;
  onStop: () => void;
  onBackground?: (() => void) | null;
  sending: boolean;
  disabled: boolean;
  attachments: ComposerAttachments;
  snapshot: RequestLifecycleSnapshot | null;
  reconnecting: StreamReconnectingState | null;
  /** Open the camera / start a call; null where the box should not offer it. */
  onCamera: (() => void) | null;
  onCall: (() => void) | null;
}) {
  const t = useTranslations("assistant");
  const boxRef = useRef<HTMLTextAreaElement>(null);
  const overLimit = isOverComposerLimit(value.length);

  // The box grows with what is typed (up to max-h-48, then scrolls). CSS field-sizing does it where supported;
  // elsewhere the height follows the content here.
  useLayoutEffect(() => {
    const el = boxRef.current;
    if (!el || typeof CSS === "undefined" || CSS.supports?.("field-sizing", "content")) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight}px`;
  }, [value]);

  // When a turn settles and focus was left nowhere (the Stop button that vanished, say), the box takes it back
  // so the next message can be typed straight away. Focus the person put somewhere else stays there.
  const wasSending = useRef(sending);
  useLayoutEffect(() => {
    const idle =
      typeof document !== "undefined" && (!document.activeElement || document.activeElement === document.body);
    if (wasSending.current && !sending && !disabled && idle) boxRef.current?.focus({ preventScroll: true });
    wasSending.current = sending;
  }, [sending, disabled]);
  const canSend =
    !disabled &&
    !sending &&
    !attachments.attaching &&
    !overLimit &&
    (value.trim().length > 0 || attachments.items.length > 0);

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      if (canSend) onSend();
    }
  }

  // A pasted screenshot or file attaches like a picked one; pasted text is left alone.
  function onPaste(e: ClipboardEvent<HTMLTextAreaElement>) {
    const files = Array.from(e.clipboardData.files);
    if (files.length === 0 || disabled || sending) return;
    e.preventDefault();
    attachments.onFiles(files);
  }

  const canAttach = attachments.searchable || !!attachments.modalities;
  const showCall = !!onCall && !sending && value.trim().length === 0 && attachments.items.length === 0;

  // "/" with no space yet: the commands that match what was typed so far.
  const slash = parseSlash(value);
  const typingCommand = value.startsWith("/") && !/\s/.test(value.trim());
  const suggestions = typingCommand
    ? SLASH_COMMANDS.filter((c) => c.startsWith(slash?.command ?? value.slice(1).toLowerCase()))
    : [];

  return (
    <div className="asst-composer-bar px-3 pb-3 pt-2 sm:px-6">
      <div className="asst-column mx-auto w-full space-y-2">
        {reconnecting ? (
          <StreamReconnectingNotice state={reconnecting} />
        ) : (
          snapshot &&
          sending && <RequestStatusIndicator snapshot={snapshot} onCancel={onStop} compact className="text-xs" />
        )}
        {attachments.error && (
          <p role="alert" className="text-xs text-destructive">
            {attachments.error}
          </p>
        )}
        <AttachmentChips attachments={attachments.items} onRemove={attachments.onRemove} disabled={sending} />
        {suggestions.length > 0 && (
          <ul
            className="rounded-lg border border-border bg-card p-1 text-sm shadow-sm"
            role="listbox"
            aria-label={t("commandsLabel")}
          >
            {suggestions.map((command) => (
              <li key={command} role="option" aria-selected={false}>
                <button
                  type="button"
                  className="flex w-full items-baseline gap-2 rounded-md px-2 py-1 text-start hover:bg-secondary/60"
                  onClick={() => {
                    onChange(`/${command} `);
                    boxRef.current?.focus();
                  }}
                >
                  <code className="shrink-0 text-xs text-foreground">/{command}</code>
                  <span className="truncate text-xs text-muted-foreground">{t(`cmd_${command}`)}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
        <div
          className={cn(
            "asst-composer-box rounded-3xl border p-2 transition-[box-shadow,border-color]",
            "focus-within:border-ring/70 focus-within:ring-2 focus-within:ring-ring/25"
          )}
        >
          <div className="flex items-end gap-2">
            <AttachMenu
              searchable={attachments.searchable}
              modalities={attachments.modalities}
              disabled={sending || attachments.attaching || disabled}
              onFiles={attachments.onFiles}
              onCamera={disabled || sending ? null : onCamera}
              onCall={disabled || sending ? null : onCall}
            />
            <Textarea
              ref={boxRef}
              value={value}
              onChange={(e) => onChange(e.target.value)}
              onKeyDown={onKeyDown}
              onPaste={canAttach ? onPaste : undefined}
              placeholder={placeholder ?? t("composerPlaceholder")}
              aria-label={placeholder ?? t("composerPlaceholder")}
              disabled={disabled}
              rows={1}
              data-assistant-box=""
              {...composerLimitProps(value.length)}
              className="max-h-48 min-h-9 flex-1 resize-none border-0 bg-transparent px-2 py-1.5 shadow-none focus-visible:ring-0"
            />
            {sending && onBackground && (
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={onBackground}
                className="gap-1"
                title={t("keepGoingBackgroundHint")}
              >
                <CloudUpload size={14} aria-hidden="true" />
                <span className="hidden sm:inline">{t("keepGoingBackground")}</span>
              </Button>
            )}
            {attachments.attaching && (
              <span role="status" className="shrink-0 px-1 text-xs text-muted-foreground">
                {t("attaching")}
              </span>
            )}
            {showCall && (
              <Button
                type="button"
                size="icon"
                variant="outline"
                className="size-9 shrink-0 rounded-full"
                onClick={onCall}
                aria-label={t("callStart")}
                title={t("callStart")}
                data-testid="assistant-call"
              >
                <Phone size={16} aria-hidden="true" />
              </Button>
            )}
            {sending ? (
              <Button
                type="button"
                size="icon"
                onClick={onStop}
                aria-label={t("stop")}
                title={t("stop")}
                data-testid="assistant-stop"
                className="size-9 shrink-0 rounded-full"
              >
                <Square size={12} className="fill-current" aria-hidden="true" />
              </Button>
            ) : showCall ? null : (
              <Button
                type="button"
                size="icon"
                onClick={onSend}
                disabled={!canSend}
                aria-label={t("send")}
                title={t("send")}
                className="size-9 shrink-0 rounded-full"
              >
                <ArrowUp size={16} aria-hidden="true" />
              </Button>
            )}
          </div>
          {toolbar && <div className="mt-2 border-t border-border/60 px-1 pt-2">{toolbar}</div>}
        </div>
        <div className="flex items-center justify-between gap-2 text-[11px] text-muted-foreground">
          <span className="hidden sm:inline">{t("composerHint")}</span>
          <ComposerLimitNote length={value.length} />
        </div>
      </div>
    </div>
  );
}

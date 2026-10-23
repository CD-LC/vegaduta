"use client";

import type React from "react";
import { ArrowUp, Paperclip, Square, X } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { cn } from "../../../lib/utils";
import type { RequestLifecycleSnapshot } from "../../../lib/requestLifecycle";
import type { RoutingPreview } from "../../../lib/edge/routingState";
import { RequestStatusIndicator } from "../../reliability/RequestStatusIndicator";
import SandboxDataHint from "../../SandboxDataHint";
import { Button } from "../../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../../ui/tooltip";
import type { StagedFrame } from "../CameraPanel";
import { visionFallbackLabel } from "./format";
import type { AttachCapabilities, ChatAttachment, PlaygroundAgent, VisionFallbackOffer } from "./types";

/**
 * The composer: request status + Cancel for server and on-device turns, the
 * transient notices (face intro, attach error, fallback-policy and vision
 * fallback prompts, routing preview, tier-1 preparing), the pending attachment
 * chip, and the form itself.
 */
export function Composer({
  agent,
  input,
  onInputChange,
  inputRef,
  onSend,
  sending,
  attaching,
  stagedFrames,
  chatSnapshot,
  onCancelChat,
  edgePhase,
  edgeWarmingPercent,
  onCancelEdgeTurn,
  faceIntroNotice,
  attachError,
  pendingFallbackAsk,
  fallbackAskResolverRef,
  visionFallback,
  onDismissVisionFallback,
  onSendVisionFallback,
  attachment,
  onRemoveAttachment,
  routingPreview,
  tier1PreparingNotice,
  canAttach,
  fileInputRef,
  acceptTypes,
  capabilities,
  onAttachFile,
}: {
  agent: PlaygroundAgent | null;
  input: string;
  onInputChange: (value: string) => void;
  inputRef: React.MutableRefObject<HTMLTextAreaElement | null>;
  onSend: (e: React.SyntheticEvent) => void;
  sending: boolean;
  attaching: boolean;
  stagedFrames: StagedFrame[];
  chatSnapshot: RequestLifecycleSnapshot | null;
  onCancelChat: () => void;
  edgePhase: "checking" | "warming" | "generating" | null;
  edgeWarmingPercent: number | null;
  onCancelEdgeTurn: () => void;
  faceIntroNotice: { tone: "success" | "info"; text: string } | null;
  attachError: string | null;
  pendingFallbackAsk: string | null;
  fallbackAskResolverRef: React.MutableRefObject<((confirmed: boolean) => void) | null>;
  visionFallback: VisionFallbackOffer | null;
  onDismissVisionFallback: () => void;
  /** The explicit "send to the server instead" choice after on-device analysis declined. */
  onSendVisionFallback: (message: string, frames: StagedFrame[]) => void;
  attachment: ChatAttachment | null;
  onRemoveAttachment: () => void;
  routingPreview: RoutingPreview | null;
  tier1PreparingNotice: boolean;
  canAttach: boolean;
  fileInputRef: React.MutableRefObject<HTMLInputElement | null>;
  acceptTypes: string;
  capabilities: AttachCapabilities;
  onAttachFile: (e: React.ChangeEvent<HTMLInputElement>) => void;
}) {
  const t = useTranslations("agentPlayground");
  // A turn is running and something can actually be cancelled: the hosted request, or the on-device model.
  const canStop = sending && (Boolean(chatSnapshot?.cancellable) || Boolean(edgePhase));
  return (
    <div className="border-t border-border px-5 py-4">
      {/* Server chat path status + Cancel - not shown while an on-device
        (edge) reply is in flight, since sendViaEdge/sendFramesLocally
        never touch streamChat / chatController. Covers the normal send,
        the visionFallback "send to server" button, and CameraPanel's
        watch-loop turns - all three share sendServerTurn's one
        streamChat call, so exactly one status/cancel is ever relevant. */}
      {sending && chatSnapshot && (
        <div className="mb-2">
          <RequestStatusIndicator snapshot={chatSnapshot} onCancel={onCancelChat} />
        </div>
      )}
      {/* Bug fix (2026-08-06 follow-up): the comment above was accurate but
        left a real gap - an on-device turn had NO cancel affordance at
        all, only tier0/tier1's own internal 30s/60s timeouts as the sole
        way it would ever resolve. Same visual treatment as the server
        path's indicator above (not RequestStatusIndicator itself - edgePhase
        is a simpler, separate state machine, not a RequestLifecycleSnapshot),
        wired to the same cancelEdgeTurn()/edgeAbortControllerRef this
        session's turn used. */}
      {sending && edgePhase && (
        <div className="mb-2 flex items-center justify-between gap-2 rounded-md border border-border bg-secondary/30 px-2.5 py-1.5">
          <span className="inline-flex items-center gap-1.5 text-xs text-muted-foreground">
            <span className="size-1.5 animate-pulse rounded-full bg-primary" aria-hidden="true" />
            {edgePhase === "warming"
              ? edgeWarmingPercent != null
                ? t("edgeWarmingUpPercent", { percent: edgeWarmingPercent })
                : t("edgeWarmingUp")
              : edgePhase === "generating"
                ? t("edgeGenerating")
                : t("edgeChecking")}
          </span>
          <Button type="button" variant="outline" size="sm" onClick={onCancelEdgeTurn} className="h-6 px-2 text-xs">
            {t("cancel")}
          </Button>
        </div>
      )}

      {faceIntroNotice && (
        <div
          className={cn(
            "mb-2 rounded-lg border px-3 py-1.5 text-xs",
            faceIntroNotice.tone === "success"
              ? "border-[var(--ax-green)]/30 bg-[var(--ax-green)]/10 text-[var(--ax-green)]"
              : "border-border bg-card text-muted-foreground"
          )}
        >
          {faceIntroNotice.text}
        </div>
      )}
      {attachError && <p className="mb-2 text-sm text-destructive">{attachError}</p>}
      <SandboxDataHint agent={agent} />
      {/* Fix #6 (P1): the "ask me first" edge.fallbackPolicy prompt -
        same shape/timeout as CallPanel's own pendingFallbackAsk, wired
        here so this surface honors that per-browser choice too. */}
      {pendingFallbackAsk && (
        <div className="mb-2 flex flex-wrap items-center gap-2 rounded-lg border border-border bg-secondary/40 p-2.5 text-xs text-muted-foreground">
          <span className="min-w-0">{pendingFallbackAsk}</span>
          <Button type="button" variant="outline" size="sm" onClick={() => fallbackAskResolverRef.current?.(true)}>
            {t("fallbackSendToPlatform")}
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={() => fallbackAskResolverRef.current?.(false)}>
            {t("cancel")}
          </Button>
        </div>
      )}
      {/* On-device snapshot analysis declined - honest reason plus the
        explicit server escalation choice. Dismissing drops the frames;
        nothing is ever sent without the click. */}
      {visionFallback && (
        <div className="mb-2 flex flex-wrap items-center gap-2 rounded-lg border border-border bg-secondary/40 p-2.5 text-xs text-muted-foreground">
          <span className="min-w-0">
            {t("visionFallbackReason", { reason: visionFallbackLabel(visionFallback.reason) })}
          </span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={sending}
            onClick={() => {
              const fallback = visionFallback;
              onDismissVisionFallback();
              onSendVisionFallback(fallback.message, fallback.frames);
            }}
          >
            {t("visionFallbackOffer")}
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={onDismissVisionFallback}>
            {t("visionFallbackDismiss")}
          </Button>
        </div>
      )}
      {attachment && (
        <div className="mb-2 flex w-fit items-center gap-1.5 rounded-full border border-border bg-secondary/50 px-2.5 py-1 text-xs text-muted-foreground">
          <Paperclip size={11} />
          <span>{attachment.originalFilename || attachment.category}</span>
          <button
            type="button"
            onClick={onRemoveAttachment}
            aria-label={t("removeAttachment")}
            className="text-muted-foreground hover:text-foreground"
          >
            <X size={11} />
          </button>
        </div>
      )}
      {/* Part 4 requirement 4 - pre-send routing preview ("Your request
        will be processed by {X}"), computed BEFORE this message is sent
        via routingState.ts's resolveEffectiveRouting/previewRouting so it
        can diverge from the stored preference (e.g. "on-device" selected
        but capabilities resolve to hosted at this exact moment). Hidden
        once a turn is actually in flight - the status rows above
        (RequestStatusIndicator / edgePhase) already show the real
        outcome then, so both would be redundant. Purely informational -
        does not gate or change sendViaEdge/handleSend in any way. */}
      {!sending && agent && routingPreview && (
        <p className="mb-1.5 text-xs text-muted-foreground">
          {t("routingPreviewLabel", { label: routingPreview.label })}
        </p>
      )}
      {/* Cold-start honesty fix (2026-08-07) - see tier1PreparingNotice's
        own comment above. Only while nothing is sending yet (once a turn
        starts, the real edgePhase/edgeWarmingPercent indicator is the
        live, accurate signal and this would be redundant/stale). */}
      {!sending && agent && tier1PreparingNotice && (
        <p className="mb-1.5 text-xs text-muted-foreground">{t("edgePreparingOnDevice")}</p>
      )}
      <form onSubmit={onSend} className="flex items-center gap-2">
        {canAttach && (
          <>
            <input
              ref={fileInputRef}
              type="file"
              accept={acceptTypes}
              onChange={onAttachFile}
              style={{ display: "none" }}
            />
            <Button
              type="button"
              variant="outline"
              size="icon"
              className="shrink-0"
              onClick={() => fileInputRef.current?.click()}
              disabled={!agent || sending || attaching}
              aria-label={t("attachAFile")}
              title={
                attaching
                  ? t("uploading")
                  : `${t("attachPrefix")} ${[
                      capabilities.supportsImageInput && t("attachImage"),
                      capabilities.supportsAudioInput && t("attachVoice"),
                      capabilities.supportsVideoInput && t("attachVideo"),
                    ]
                      .filter(Boolean)
                      .join(", ")} - ${t("attachSupportsSuffix")}`
              }
            >
              <Paperclip size={14} />
            </Button>
          </>
        )}
        <textarea
          ref={inputRef}
          rows={1}
          className={cn(
            "max-h-40 min-h-10 w-full min-w-0 flex-1 resize-none overflow-y-auto rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow]",
            "placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40",
            "disabled:cursor-not-allowed disabled:opacity-50"
          )}
          placeholder={t("sendPlaceholder")}
          aria-label={t("sendPlaceholder")}
          value={input}
          onChange={(e) => {
            onInputChange(e.target.value);
            const el = e.target;
            el.style.height = "auto";
            el.style.height = `${el.scrollHeight}px`;
          }}
          onKeyDown={(e) => {
            // Enter sends (matches the single-line input's old behavior and
            // the "enterToSend" hint next to it); Shift+Enter inserts a
            // newline, the standard chat-input convention now that this is
            // a real multi-line textarea.
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              void onSend(e);
            }
          }}
          disabled={!agent || sending}
        />
        <span className="hidden shrink-0 text-xs text-muted-foreground sm:inline">{t("enterToSend")}</span>
        {canStop ? (
          // A clear, always-there way to stop a running reply: replaces Send while a turn can be cancelled, on the
          // hosted path and the on-device path alike.
          <Button
            type="button"
            size="icon"
            variant="destructive"
            className="shrink-0"
            onClick={() => {
              onCancelChat();
              onCancelEdgeTurn();
            }}
            aria-label={t("stopRun")}
            title={t("stopRun")}
            data-testid="composer-stop"
          >
            <Square size={12} aria-hidden="true" />
          </Button>
        ) : (
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              type="submit"
              size="icon"
              className="shrink-0"
              disabled={
                !agent || sending || attaching || (!input.trim() && !attachment && stagedFrames.length === 0)
              }
              aria-label={t("sendAriaLabel")}
            >
              <ArrowUp size={14} />
            </Button>
          </TooltipTrigger>
          <TooltipContent>{t("sendAriaLabel")}</TooltipContent>
        </Tooltip>
        )}
      </form>
    </div>
  );
}

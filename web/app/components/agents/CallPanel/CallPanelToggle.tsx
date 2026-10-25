// The collapse header of CallPanel: a slim status row that toggles the call
// body. The body is hidden via a class (never unmounted), so the LiveKit
// room, MediaRecorder and reply-audio chain all keep running while folded.

import { ChevronDown, ChevronUp } from "lucide-react";
import { cn } from "../../../lib/utils";
import { useTranslations } from "../../../lib/i18n";

interface CallPanelToggleProps {
  open: boolean;
  status: string;
  reconnecting: boolean;
  onToggle: () => void;
}

export default function CallPanelToggle({ open, status, reconnecting, onToggle }: CallPanelToggleProps) {
  const t = useTranslations("callPanel");
  const statusLabel =
    status === "connected"
      ? reconnecting
        ? t("reconnecting")
        : t("statusInCall")
      : status === "connecting"
        ? t("statusConnecting")
        : status === "error"
          ? t("statusFailed")
          : t("statusIdle");
  const title = t("sectionTitle");
  return (
    <button
      type="button"
      className="flex w-full items-center gap-2 text-left text-xs font-medium text-muted-foreground hover:text-foreground"
      aria-expanded={open}
      data-testid="call-panel-toggle"
      onClick={onToggle}
    >
      <span
        className={cn("state-dot", (status === "connected" || status === "connecting") && "state-dot-live")}
        style={
          {
            ["--state" as any]:
              status === "connected"
                ? reconnecting
                  ? "var(--state-warning)"
                  : "var(--state-success)"
                : status === "connecting"
                  ? "var(--state-warning)"
                  : status === "error"
                    ? "var(--state-error)"
                    : "var(--state-idle)",
          } as any
        }
        aria-hidden="true"
      />
      {title}
      <span className="text-muted-foreground/70">· {statusLabel}</span>
      {open ? (
        <ChevronUp className="ml-auto size-3.5" aria-hidden="true" />
      ) : (
        <ChevronDown className="ml-auto size-3.5" aria-hidden="true" />
      )}
    </button>
  );
}

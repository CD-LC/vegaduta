import {
  Ban, CheckCircle2, CircleDashed, Hand, Loader2, PauseCircle, Pin, SkipForward, XCircle, type LucideIcon,
} from "lucide-react";
import type { StatusKey } from "./types";

export const STATUS_META: Record<StatusKey, { label: string; Icon: LucideIcon }> = {
  ok: { label: "Succeeded", Icon: CheckCircle2 },
  pinned: { label: "Pinned", Icon: Pin },
  error: { label: "Failed", Icon: XCircle },
  skipped: { label: "Skipped", Icon: SkipForward },
  approval: { label: "Awaiting approval", Icon: Hand },
  cancelled: { label: "Cancelled", Icon: Ban },
  paused: { label: "Paused", Icon: PauseCircle },
  running: { label: "Running", Icon: Loader2 },
  unknown: { label: "Unknown", Icon: CircleDashed },
};

export function statusKey(raw: string | null | undefined): StatusKey {
  switch ((raw ?? "").toUpperCase()) {
    case "OK":
    case "SUCCESS":
    case "COMPLETED":
      return "ok";
    case "PINNED":
      return "pinned";
    case "ERROR":
    case "FAILED":
      return "error";
    case "SKIPPED":
      return "skipped";
    case "AWAITING_APPROVAL":
      return "approval";
    case "CANCELLED":
    case "CANCELED":
      return "cancelled";
    case "PAUSED":
      return "paused";
    case "RUNNING":
    case "STARTED":
      return "running";
    default:
      return "unknown";
  }
}

// Error-to-copy mapping for CameraPanel's capture and watch-session paths. Pure functions over the
// "cameraPanel" translator so the panel stays about state and the wording rules live in one place.

type Translate = (key: string, values?: Record<string, unknown>) => string;

// Mirror of VisionSessionService.APPROVAL_REQUIRED_MESSAGE_PREFIX (core) -
// the interim signal that tells a governance-policy 403
// (VisionApprovalRequiredException) apart from a plan-tier 403
// (VisionNotAllowedForPlanException). Both land here as HTTP 403 from
// api.startVisionSession; the backend also stamps a proper `code` JSON
// field on the response (see GlobalExceptionHandler's handler javadoc), but
// api.ts's toJsonQuietOn helper for this endpoint only threads
// `error`/`status` through to callers today, not arbitrary body fields, so
// this message-prefix check is what actually works right now. Update this
// alongside that Java constant if either side ever changes.
export const VISION_APPROVAL_REQUIRED_PREFIX = "[VISION_APPROVAL_REQUIRED] ";

// getUserMedia/getDisplayMedia failures are DOMExceptions with a
// discriminating `name` - permission denied vs no device vs device-busy
// are three different user actions, so each gets its own honest line
// (screen included: it used to collapse every screen failure into
// "cancelled or blocked", which is wrong for a missing/busy source).
export function captureErrorMessage(err: any, forSource: "camera" | "screen", t: Translate): string {
  const name = err?.name;
  if (name === "NotAllowedError" || name === "SecurityError") {
    return forSource === "screen" ? t("screenDenied") : t("cameraDenied");
  }
  if (name === "NotFoundError" || name === "OverconstrainedError") {
    return forSource === "screen" ? t("screenUnavailable") : t("cameraUnavailable");
  }
  if (name === "NotReadableError" || name === "AbortError") {
    return forSource === "screen" ? t("screenBusy") : t("cameraBusy");
  }
  return err?.message || t("captureFailed");
}

/**
 * The line to show for a failed watch-session start or watch turn, and whether the failure is the
 * plan-tier lock (the start control then turns into the shared upgrade-lock Button).
 */
export function watchError(err: any, t: Translate): { message: string; planLocked: boolean } {
  if (err?.status === 403) {
    const message: string = err?.message || "";
    if (message.startsWith(VISION_APPROVAL_REQUIRED_PREFIX)) {
      // A tenant admin's approval-policy override, not a plan-tier
      // lockout - "not fixed by upgrading" per VisionSessionService's
      // javadoc, so no upgrade CTA and no planLocked here. Surface the
      // backend's real (already-governance-framed) message verbatim.
      return { message: message.slice(VISION_APPROVAL_REQUIRED_PREFIX.length) || t("genericError"), planLocked: false };
    }
    return { message: t("upgradeReason"), planLocked: true };
  }
  // Live-config-driven message from VisionUsageService (includes the
  // actual configured daily limit and, on FREE, the on-device tip) -
  // surfaced verbatim, same as the sibling SNAPSHOT-send path already does.
  if (err?.status === 429) return { message: err?.message || t("allowanceUsed"), planLocked: false };
  if (err?.status === 503) return { message: t("visionDisabled"), planLocked: false };
  return { message: err?.message || t("genericError"), planLocked: false };
}

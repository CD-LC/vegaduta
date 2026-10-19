import type { Translator } from "./types";

// Shared token-class textarea styling, matching the ShadCN Input primitive
// (no ui/textarea primitive exists in the foundation, so this mirrors Input's
// look inline). Multi-line, so height is content-driven rather than fixed.
export const TEXTAREA_CLASS =
  "flex w-full min-w-0 rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow] placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40 disabled:cursor-not-allowed disabled:opacity-50";

// BACK/HOME/RECENTS/READ_SCREEN take no payload at all (see
// AutomationAccessibilityService.kt's dispatch table on the Android side),
// so the Payload field is hidden entirely for those rather than shown-but-ignored.
export const DEVICE_COMMAND_TYPES_WITHOUT_PAYLOAD = new Set(["READ_SCREEN", "BACK", "HOME", "RECENTS"]);

export function devicePayloadPlaceholder(t: Translator, commandType: string | undefined) {
  switch (commandType) {
    case "SWIPE":
      return t("devicePayloadPlaceholderSwipe");
    case "SCROLL":
      return t("devicePayloadPlaceholderScroll");
    case "DISMISS_NOTIFICATION":
      return t("devicePayloadPlaceholderDismissNotification");
    case "NOTIFICATION_ACTION":
      return t("devicePayloadPlaceholderNotificationAction");
    case "OPEN_APP":
      return t("devicePayloadPlaceholderOpenApp");
    case "TAP":
      return t("devicePayloadPlaceholderTap");
    default:
      return t("devicePayloadPlaceholderDefault");
  }
}

export function devicePayloadHint(t: Translator, commandType: string | undefined) {
  switch (commandType) {
    case "SWIPE":
      return t("devicePayloadHintSwipe");
    case "SCROLL":
      return t("devicePayloadHintScroll");
    case "DISMISS_NOTIFICATION":
    case "NOTIFICATION_ACTION":
      return t("devicePayloadHintNotification");
    default:
      return "";
  }
}

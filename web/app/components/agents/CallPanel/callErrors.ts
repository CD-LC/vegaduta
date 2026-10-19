import { ConnectionError, ConnectionErrorReason, DisconnectReason } from "livekit-client";
import type { useTranslations } from "../../../lib/i18n";

type Translate = ReturnType<typeof useTranslations>;

// Maps a LiveKit DisconnectReason to a human-readable failure line, or
// null for a clean/intentional disconnect. The JOIN_FAILURE/MEDIA_FAILURE
// family is the "signaling succeeded through the tunnel, then the actual
// media leg died" shape - exactly what a network that blocks UDP
// 50000-50100/TCP 7881 produces (room connects, then instantly drops).
export function disconnectMessage(t: Translate, reason: DisconnectReason | undefined) {
  switch (reason) {
    case undefined:
    case DisconnectReason.CLIENT_INITIATED:
      return null;
    case DisconnectReason.JOIN_FAILURE:
    case DisconnectReason.STATE_MISMATCH:
    case DisconnectReason.CONNECTION_TIMEOUT:
    case DisconnectReason.MEDIA_FAILURE:
      return t("mediaBlocked");
    case DisconnectReason.DUPLICATE_IDENTITY:
      return t("duplicateJoin");
    case DisconnectReason.SERVER_SHUTDOWN:
    case DisconnectReason.ROOM_DELETED:
    case DisconnectReason.PARTICIPANT_REMOVED:
    case DisconnectReason.SIGNAL_CLOSE:
      return t("serverEnded");
    default:
      return t("droppedUnknown");
  }
}

// Same honesty for the initial startCall() throw: token/auth rejection,
// unreachable signaling, blocked media (ICE/PeerConnection never came up),
// and a denied mic each get their own line instead of one opaque message.
// `unknown` because the throw sources are genuinely heterogeneous: a
// getUserMedia DOMException (name), livekit's ConnectionError (reason), or
// apiFetch's Error with the HTTP status attached (status) - the structural
// cast below reads the optional fields each source may carry rather than
// pretending one nominal error type covers all three.
export function startFailureMessage(t: Translate, err: unknown) {
  const shaped = err as { name?: string; status?: number; message?: string } | null | undefined;
  if (shaped?.name === "NotAllowedError") return t("micDenied");
  if (err instanceof ConnectionError) {
    if (err.reason === ConnectionErrorReason.NotAllowed) return t("authFailed");
    if (
      err.reason === ConnectionErrorReason.ServerUnreachable ||
      err.reason === ConnectionErrorReason.WebSocket ||
      err.reason === ConnectionErrorReason.ServiceNotFound
    ) {
      return t("serverUnreachable");
    }
    // The signaling websocket connected but the PeerConnection never did -
    // livekit-client reports this as a timeout/internal ConnectionError
    // whose message names the pc connection.
    if (err.reason === ConnectionErrorReason.Timeout || /pc connection|ice/i.test(err.message || "")) {
      return t("mediaBlocked");
    }
  }
  // Token minting itself failed (api.getVoiceToken) - apiFetch errors carry
  // the HTTP status.
  if (shaped?.status === 401 || shaped?.status === 403) return t("authFailed");
  return shaped?.message || t("couldNotStart");
}

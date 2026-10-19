import { LocalAuthError } from "../../lib/localAuthClient";
import { PasskeyError } from "../../lib/localAuthWebAuthn";
import { PIN_MAX, PIN_MIN } from "../../lib/localAuthPin";
import type { PinProblem } from "../../lib/localAuthPin";

export type T = (key: string, values?: Record<string, string | number>) => string;

/** Which credential a failed sign-in was about; a wrong secret reads differently for each. */
export type ErrorContext = "password" | "pin" | "recovery" | "passkey" | "account";

const MIN_PASSWORD = 12;

/** Turns any failure of a local-auth call into the sentence the person should read. */
export function describeLocalAuthError(t: T, err: unknown, context: ErrorContext = "account"): string {
  if (err instanceof PasskeyError) return t(`local.errors.${err.code}`);
  if (!(err instanceof LocalAuthError)) return t("local.errors.generic");
  switch (err.code) {
    case "invalid_credentials":
      // On the change-password step core answers 403 for a wrong CURRENT password; same wording.
      return t(
        context === "pin"
          ? "local.errors.pin_invalid"
          : context === "recovery"
            ? "local.errors.recovery_invalid"
            : context === "passkey"
              ? "local.errors.passkey_invalid"
              : "local.errors.invalid_credentials"
      );
    case "too_many_attempts":
      return t("local.errors.too_many_attempts", { seconds: err.retryAfterSeconds ?? 60 });
    case "weak_password":
      return t("local.errors.weak_password", { min: MIN_PASSWORD });
    case "weak_pin":
      return t("local.errors.weak_pin", { min: PIN_MIN, max: PIN_MAX });
    case "device_revoked":
    case "device_unknown":
    case "passkey_invalid":
    case "passkey_origin_unsupported":
    case "reauth_required":
    case "last_credential":
    case "invalid_setup_token":
    case "invalid_session":
      return t(`local.errors.${err.code}`);
    case "network":
    case "unavailable":
      return t("local.errors.network");
    default:
      return t("local.errors.generic");
  }
}

/** Instant feedback for a PIN the form will not send; null when it is acceptable. */
export function describePinProblem(t: T, problem: PinProblem): string | null {
  if (!problem) return null;
  return problem === "weak" ? t("local.errors.weak_pin", { min: PIN_MIN, max: PIN_MAX }) : t("local.pin.rules", { min: PIN_MIN, max: PIN_MAX });
}

// Client-side hints for the device PIN and recovery codes. Core is the authority (it answers
// 400 weak_pin); these only give instant feedback and keep the on-screen formatting in one place.

export const PIN_MIN = 6;
export const PIN_MAX = 12;

/** The few PINs core refuses by name (contract: passkey-pin-api.md), checked here for instant feedback. */
const COMMON_PINS = new Set(["123123", "112233", "696969", "121212"]);

export type PinProblem = "digits" | "length" | "weak" | null;

/** Why a PIN would be refused, or null. Mirrors core's rules: digits only, 6 to 12, not trivial. */
export function pinProblem(pin: string): PinProblem {
  if (!/^\d*$/.test(pin)) return "digits";
  if (pin.length < PIN_MIN || pin.length > PIN_MAX) return "length";
  if (/^(\d)\1+$/.test(pin) || COMMON_PINS.has(pin)) return "weak";
  const steps = [...pin].slice(1).map((d, i) => Number(d) - Number(pin[i]));
  if (steps.every((s) => s === 1) || steps.every((s) => s === -1)) return "weak";
  return null;
}

/** Keeps digits only and caps the length, for onChange handlers of a PIN field. */
export function sanitizePin(value: string): string {
  return value.replace(/\D/g, "").slice(0, PIN_MAX);
}

/** "ABCD-EFGH-JKLM-NPQR" from 16 raw characters; already grouped input is normalised the same way. */
export function formatRecoveryCode(code: string): string {
  const raw = normalizeRecoveryCode(code);
  return raw.match(/.{1,4}/g)?.join("-") ?? raw;
}

/** What core compares: letters and digits only, upper case (people type spaces and dashes freely). */
export function normalizeRecoveryCode(code: string): string {
  return code.replace(/[^A-Za-z0-9]/g, "").toUpperCase();
}

// Client calls for passkey, device-PIN and recovery-code sign-in, and for managing them. Each
// function is one request to the same-origin proxy (app/api/local-auth/unlock/[...path] and
// .../device), which forwards it to core's /api/auth/local/unlock/* endpoints. The browser never
// handles a device secret or a refresh token: the proxy keeps both in HttpOnly cookies.
import { localAuthPost, localAuthRequest } from "./localAuthClient";
import type { LocalSessionPayload } from "./localAuthClient";
import { createPasskey, getPasskey } from "./localAuthWebAuthn";
import { normalizeRecoveryCode } from "./localAuthPin";

export interface PasskeyView {
  id: string;
  label: string;
  createdAt?: string;
  lastUsedAt?: string | null;
  backedUp?: boolean;
}

export interface DeviceView {
  id: string;
  label: string;
  createdAt?: string;
  lastUsedAt?: string | null;
}

export interface UnlockMethods {
  hasPassword: boolean;
  passkeys: PasskeyView[];
  devices: DeviceView[];
  recoveryCodesRemaining: number;
}

/** What /config tells the sign-in page about this install. */
export interface LocalAuthConfig {
  setupRequired: boolean;
  /** Registered verifier types: password, pin, passkey, recovery. Absent on an older core: password only. */
  credentialTypes: string[];
}

/** The proxy's view of this browser's device cookie (no secret ever reaches the page). */
export interface DeviceStatus {
  known: boolean;
  id?: string;
  label?: string;
}

interface PasskeyOptions {
  challengeId: string;
  publicKey: Record<string, unknown>;
}

const UNLOCK = "unlock";

const bearer = (token: string | undefined): Record<string, string> | undefined =>
  token ? { Authorization: `Bearer ${token}` } : undefined;

/** Reads /config; any failure degrades to "password only", the behaviour of every core before passkeys. */
export async function fetchLocalAuthConfig(): Promise<LocalAuthConfig> {
  try {
    const cfg = await localAuthRequest<Record<string, unknown> | null>("GET", "config");
    const types = Array.isArray(cfg?.credentialTypes)
      ? (cfg.credentialTypes as unknown[]).filter((v): v is string => typeof v === "string")
      : [];
    return { setupRequired: !!cfg?.setupRequired, credentialTypes: types.length ? types : ["password"] };
  } catch {
    return { setupRequired: false, credentialTypes: ["password"] };
  }
}

/** Whether this browser holds a device cookie (PIN sign-in possible). Never throws. */
export async function fetchDeviceStatus(): Promise<DeviceStatus> {
  try {
    const status = await localAuthRequest<DeviceStatus | null>("GET", "device");
    return status && status.known ? status : { known: false };
  } catch {
    return { known: false };
  }
}

// ---- sign in ---------------------------------------------------------------------------------

/** PIN sign-in; the proxy adds the device id and secret from its cookie. */
export function pinLogin(pin: string): Promise<LocalSessionPayload> {
  return localAuthPost<LocalSessionPayload>(`${UNLOCK}/login`, { type: "pin", pin });
}

/** One-time recovery code sign-in. */
export function recoveryLogin(code: string): Promise<LocalSessionPayload> {
  return localAuthPost<LocalSessionPayload>(`${UNLOCK}/login`, {
    type: "recovery",
    identifier: "recovery",
    credentials: { code: normalizeRecoveryCode(code) },
  });
}

/** Full passkey sign-in: ask for a challenge, run the browser ceremony, present the assertion. */
export async function passkeyLogin(): Promise<LocalSessionPayload> {
  const options = await localAuthPost<PasskeyOptions>(`${UNLOCK}/passkey/login-options`, {});
  const assertion = await getPasskey(options.publicKey);
  return localAuthPost<LocalSessionPayload>(`${UNLOCK}/login`, {
    type: "passkey",
    identifier: options.challengeId,
    credentials: { assertion },
  });
}

// ---- first-run setup -------------------------------------------------------------------------

export interface SetupResult {
  session: LocalSessionPayload;
  recoveryCodes: string[];
}

/** Completes the one-time setup link with a PIN instead of a password. Enrols this browser as a device. */
export function setupWithPin(token: string, pin: string, label: string): Promise<SetupResult> {
  return localAuthPost<SetupResult>(`${UNLOCK}/setup/pin`, { token, pin, label });
}

/** Completes the one-time setup link with a passkey. */
export async function setupWithPasskey(setupToken: string, label: string): Promise<SetupResult> {
  const options = await localAuthPost<PasskeyOptions>(`${UNLOCK}/passkey/register-options`, { setupToken });
  const credential = await createPasskey(options.publicKey);
  const res = await localAuthPost<{ session: LocalSessionPayload; recoveryCodes: string[] }>(
    `${UNLOCK}/passkey/register`,
    { challengeId: options.challengeId, credential, label, setupToken }
  );
  return { session: res.session, recoveryCodes: res.recoveryCodes ?? [] };
}

// ---- account security (signed in) ------------------------------------------------------------

export function listMethods(token: string): Promise<UnlockMethods> {
  return localAuthRequest<UnlockMethods>("GET", `${UNLOCK}/methods`, undefined, bearer(token));
}

/** Adds a passkey to the signed-in account. Needs a fresh sign-in (403 reauth_required otherwise). */
export async function addPasskey(token: string, label: string): Promise<PasskeyView> {
  const options = await localAuthPost<PasskeyOptions>(`${UNLOCK}/passkey/register-options`, {}, bearer(token));
  const credential = await createPasskey(options.publicKey);
  const res = await localAuthPost<{ passkey: PasskeyView }>(
    `${UNLOCK}/passkey/register`,
    { challengeId: options.challengeId, credential, label },
    bearer(token)
  );
  return res.passkey;
}

/** Enrols THIS browser as a PIN device; the proxy stores the device secret in its cookie. */
export function enrolDevice(token: string, pin: string, label: string): Promise<{ deviceId?: string }> {
  return localAuthPost<{ deviceId?: string }>(`${UNLOCK}/devices`, { pin, label }, bearer(token));
}

export async function removeDevice(token: string, id: string): Promise<void> {
  await localAuthRequest("DELETE", `${UNLOCK}/devices/${encodeURIComponent(id)}`, undefined, bearer(token));
}

export async function removePasskey(token: string, id: string): Promise<void> {
  await localAuthRequest("DELETE", `${UNLOCK}/passkeys/${encodeURIComponent(id)}`, undefined, bearer(token));
}

/** Replaces the whole recovery-code set; the old codes stop working at once. */
export async function regenerateRecoveryCodes(token: string): Promise<string[]> {
  const res = await localAuthPost<{ codes: string[] }>(`${UNLOCK}/recovery-codes`, {}, bearer(token));
  return res.codes;
}

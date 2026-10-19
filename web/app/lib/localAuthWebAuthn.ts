// Browser-side WebAuthn helpers for the passkey sign-in of the personal install.
//
// Core speaks JSON: challenges, credential ids and keys travel as base64url strings, and a
// registration or assertion response goes back as the JSON string `PublicKeyCredential.toJSON()`
// produces. Browsers that have the JSON helpers (parseCreationOptionsFromJSON,
// parseRequestOptionsFromJSON, toJSON) are used as they are; the manual conversion below is the
// fallback for the rest, so the wire shape is the same either way.
//
// Nothing here talks to the server. localAuthUnlock.ts joins these helpers to the proxy calls.

/** Why a passkey cannot be used on this page; the sign-in and setup screens turn it into copy. */
export type PasskeyUnavailableReason = "no_webauthn" | "insecure_context" | "ip_origin";

/** `reason` is always set when `supported` is false (the tsconfig here does not narrow on a boolean literal, so it is optional in the type). */
export interface PasskeySupport {
  supported: boolean;
  reason?: PasskeyUnavailableReason;
}

/** Failure of a WebAuthn ceremony, with a stable code the UI maps to a message. */
export class PasskeyError extends Error {
  constructor(
    readonly code: "passkey_cancelled" | "passkey_exists" | "passkey_unsupported" | "passkey_failed",
    message?: string
  ) {
    super(message ?? code);
    this.name = "PasskeyError";
  }
}

const B64URL = /^[A-Za-z0-9_-]*$/;

export function bufferToBase64url(buffer: ArrayBuffer | ArrayBufferView): string {
  const bytes =
    buffer instanceof ArrayBuffer
      ? new Uint8Array(buffer)
      : new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Throws a TypeError for anything that is not base64url (a malformed server value must not become garbage bytes). */
export function base64urlToBuffer(value: string): ArrayBuffer {
  if (!B64URL.test(value) || value.length % 4 === 1) throw new TypeError("Not a base64url string.");
  const b64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = b64 + "=".repeat((4 - (b64.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function isIpHost(hostname: string): boolean {
  // IPv4 dotted quad, or an IPv6 literal (URL.hostname keeps the brackets; location.hostname too).
  return /^\d{1,3}(\.\d{1,3}){3}$/.test(hostname) || hostname.startsWith("[") || hostname.includes(":");
}

/**
 * Can a passkey work here? WebAuthn needs the API, a secure context (https, or localhost) and a
 * registrable domain as the relying-party id, so a bare IP address never works even over https.
 */
export function passkeySupport(): PasskeySupport {
  if (typeof window === "undefined") return { supported: false, reason: "no_webauthn" };
  // The address is checked before the API: browsers hide PublicKeyCredential on an insecure
  // origin, so "no WebAuthn" would be the misleading answer for http://192.168.x.x.
  if (isIpHost(window.location.hostname)) return { supported: false, reason: "ip_origin" };
  if (!window.isSecureContext) return { supported: false, reason: "insecure_context" };
  if (typeof window.PublicKeyCredential === "undefined" || !navigator.credentials) {
    return { supported: false, reason: "no_webauthn" };
  }
  return { supported: true };
}

/** A short name for a new credential or device, taken from the browser (no fingerprinting beyond what the UA already says). */
export function guessDeviceLabel(fallback: string): string {
  if (typeof navigator === "undefined") return fallback;
  const ua = navigator.userAgent;
  const os = /iPhone|iPad/.test(ua)
    ? "iOS"
    : /Android/.test(ua)
      ? "Android"
      : /Mac OS X|Macintosh/.test(ua)
        ? "macOS"
        : /Windows/.test(ua)
          ? "Windows"
          : /Linux|X11/.test(ua)
            ? "Linux"
            : "";
  const browser = /Edg\//.test(ua)
    ? "Edge"
    : /Firefox\//.test(ua)
      ? "Firefox"
      : /Chrome\//.test(ua)
        ? "Chrome"
        : /Safari\//.test(ua)
          ? "Safari"
          : "";
  const label = [browser, os].filter(Boolean).join(" on ");
  return label || fallback;
}

type JsonObject = Record<string, unknown>;

type JsonStatics = {
  parseCreationOptionsFromJSON?: (o: unknown) => PublicKeyCredentialCreationOptions;
  parseRequestOptionsFromJSON?: (o: unknown) => PublicKeyCredentialRequestOptions;
};

function statics(): JsonStatics {
  return (typeof PublicKeyCredential !== "undefined" ? PublicKeyCredential : {}) as unknown as JsonStatics;
}

function descriptors(list: unknown): PublicKeyCredentialDescriptor[] {
  if (!Array.isArray(list)) return [];
  return list.map((item) => {
    const d = item as JsonObject;
    return { ...(d as object), type: "public-key", id: base64urlToBuffer(String(d.id)) } as PublicKeyCredentialDescriptor;
  });
}

/** Server registration options (the `publicKey` object, base64url strings) to what navigator.credentials.create wants. */
export function creationOptionsFromJson(publicKey: JsonObject): PublicKeyCredentialCreationOptions {
  const native = statics().parseCreationOptionsFromJSON;
  if (typeof native === "function") return native.call(PublicKeyCredential, publicKey);
  const user = publicKey.user as JsonObject;
  return {
    ...(publicKey as object),
    challenge: base64urlToBuffer(String(publicKey.challenge)),
    user: { ...(user as object), id: base64urlToBuffer(String(user.id)) },
    excludeCredentials: descriptors(publicKey.excludeCredentials),
  } as PublicKeyCredentialCreationOptions;
}

/** Server login options to what navigator.credentials.get wants. */
export function requestOptionsFromJson(publicKey: JsonObject): PublicKeyCredentialRequestOptions {
  const native = statics().parseRequestOptionsFromJSON;
  if (typeof native === "function") return native.call(PublicKeyCredential, publicKey);
  return {
    ...(publicKey as object),
    challenge: base64urlToBuffer(String(publicKey.challenge)),
    allowCredentials: descriptors(publicKey.allowCredentials),
  } as PublicKeyCredentialRequestOptions;
}

type WithToJson = { toJSON?: () => unknown };

function encode(buffer: ArrayBuffer | null | undefined): string | null {
  return buffer ? bufferToBase64url(buffer) : null;
}

/** The JSON-string form of a registration response (toJSON() shape). */
export function registrationToJson(credential: PublicKeyCredential): string {
  const native = (credential as unknown as WithToJson).toJSON;
  if (typeof native === "function") return JSON.stringify(native.call(credential));
  const response = credential.response as AuthenticatorAttestationResponse;
  const out: JsonObject = {
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
    clientExtensionResults: credential.getClientExtensionResults(),
    response: {
      clientDataJSON: bufferToBase64url(response.clientDataJSON),
      attestationObject: bufferToBase64url(response.attestationObject),
      transports: typeof response.getTransports === "function" ? response.getTransports() : [],
      authenticatorData: encode(response.getAuthenticatorData?.()),
      publicKey: encode(response.getPublicKey?.()),
      publicKeyAlgorithm: response.getPublicKeyAlgorithm?.(),
    },
  };
  return JSON.stringify(out);
}

/** The JSON-string form of an assertion (toJSON() shape). */
export function assertionToJson(credential: PublicKeyCredential): string {
  const native = (credential as unknown as WithToJson).toJSON;
  if (typeof native === "function") return JSON.stringify(native.call(credential));
  const response = credential.response as AuthenticatorAssertionResponse;
  const out: JsonObject = {
    id: credential.id,
    rawId: bufferToBase64url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment ?? undefined,
    clientExtensionResults: credential.getClientExtensionResults(),
    response: {
      clientDataJSON: bufferToBase64url(response.clientDataJSON),
      authenticatorData: bufferToBase64url(response.authenticatorData),
      signature: bufferToBase64url(response.signature),
      userHandle: encode(response.userHandle),
    },
  };
  return JSON.stringify(out);
}

function ceremonyError(err: unknown): PasskeyError {
  if (err instanceof PasskeyError) return err;
  const name = (err as { name?: string } | null)?.name;
  // The user dismissed the dialog, or it timed out: both arrive as NotAllowedError by design.
  if (name === "NotAllowedError" || name === "AbortError") return new PasskeyError("passkey_cancelled");
  // This authenticator already holds a credential from the excludeCredentials list.
  if (name === "InvalidStateError") return new PasskeyError("passkey_exists");
  if (name === "NotSupportedError" || name === "SecurityError") return new PasskeyError("passkey_unsupported");
  return new PasskeyError("passkey_failed");
}

/** Runs the registration ceremony for server options; resolves the toJSON()-shaped response string. */
export async function createPasskey(publicKey: JsonObject): Promise<string> {
  const support = passkeySupport();
  if (!support.supported) throw new PasskeyError("passkey_unsupported", support.reason);
  try {
    const credential = (await navigator.credentials.create({
      publicKey: creationOptionsFromJson(publicKey),
    })) as PublicKeyCredential | null;
    if (!credential) throw new PasskeyError("passkey_cancelled");
    return registrationToJson(credential);
  } catch (err) {
    throw ceremonyError(err);
  }
}

/** Runs the assertion ceremony for server options; resolves the toJSON()-shaped assertion string. */
export async function getPasskey(publicKey: JsonObject): Promise<string> {
  const support = passkeySupport();
  if (!support.supported) throw new PasskeyError("passkey_unsupported", support.reason);
  try {
    const credential = (await navigator.credentials.get({
      publicKey: requestOptionsFromJson(publicKey),
    })) as PublicKeyCredential | null;
    if (!credential) throw new PasskeyError("passkey_cancelled");
    return assertionToJson(credential);
  } catch (err) {
    throw ceremonyError(err);
  }
}

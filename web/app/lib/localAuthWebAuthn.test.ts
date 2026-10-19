import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  PasskeyError,
  assertionToJson,
  base64urlToBuffer,
  bufferToBase64url,
  createPasskey,
  creationOptionsFromJson,
  getPasskey,
  guessDeviceLabel,
  passkeySupport,
  registrationToJson,
  requestOptionsFromJson,
} from "./localAuthWebAuthn";

const bytes = (...n: number[]) => new Uint8Array(n).buffer;
const asArray = (b: ArrayBuffer) => Array.from(new Uint8Array(b));

describe("base64url", () => {
  it("round-trips arbitrary bytes without padding or + and /", () => {
    const all = new Uint8Array(256).map((_, i) => i);
    const text = bufferToBase64url(all);
    expect(text).not.toMatch(/[+/=]/);
    expect(Array.from(new Uint8Array(base64urlToBuffer(text)))).toEqual(Array.from(all));
  });

  it("matches known vectors", () => {
    expect(bufferToBase64url(bytes(0xfb, 0xff))).toBe("-_8");
    expect(asArray(base64urlToBuffer("-_8"))).toEqual([0xfb, 0xff]);
    expect(bufferToBase64url(new Uint8Array([104, 105]))).toBe("aGk");
    expect(asArray(base64urlToBuffer(""))).toEqual([]);
  });

  it("accepts a typed-array view without leaking the bytes around it", () => {
    const backing = new Uint8Array([9, 9, 1, 2, 3, 9]);
    expect(bufferToBase64url(backing.subarray(2, 5))).toBe(bufferToBase64url(new Uint8Array([1, 2, 3])));
  });

  it("rejects text that is not base64url", () => {
    expect(() => base64urlToBuffer("ab+/")).toThrow(TypeError);
    expect(() => base64urlToBuffer("a")).toThrow(TypeError);
    expect(() => base64urlToBuffer("ab cd")).toThrow(TypeError);
  });
});

describe("passkeySupport", () => {
  const original = {
    host: window.location.hostname,
  };
  function env(opts: { hostname: string; secure: boolean; api: boolean }) {
    Object.defineProperty(window, "location", {
      configurable: true,
      value: { ...window.location, hostname: opts.hostname },
    });
    Object.defineProperty(window, "isSecureContext", { configurable: true, value: opts.secure });
    if (opts.api) vi.stubGlobal("PublicKeyCredential", class {});
    else vi.stubGlobal("PublicKeyCredential", undefined);
    Object.defineProperty(window, "PublicKeyCredential", { configurable: true, value: opts.api ? class {} : undefined });
    Object.defineProperty(navigator, "credentials", { configurable: true, value: opts.api ? {} : undefined });
  }
  afterEach(() => {
    vi.unstubAllGlobals();
    Object.defineProperty(window, "location", { configurable: true, value: { ...window.location, hostname: original.host } });
  });

  it("is supported on localhost and on an https host name", () => {
    env({ hostname: "localhost", secure: true, api: true });
    expect(passkeySupport()).toEqual({ supported: true });
    env({ hostname: "vega.example.com", secure: true, api: true });
    expect(passkeySupport()).toEqual({ supported: true });
  });

  it("names the reason: IP address, insecure context, or no WebAuthn", () => {
    env({ hostname: "192.168.1.20", secure: false, api: false });
    expect(passkeySupport()).toEqual({ supported: false, reason: "ip_origin" });
    env({ hostname: "10.0.0.5", secure: true, api: true });
    expect(passkeySupport()).toEqual({ supported: false, reason: "ip_origin" });
    env({ hostname: "[::1]", secure: true, api: true });
    expect(passkeySupport()).toEqual({ supported: false, reason: "ip_origin" });
    env({ hostname: "vega.lan", secure: false, api: false });
    expect(passkeySupport()).toEqual({ supported: false, reason: "insecure_context" });
    env({ hostname: "vega.example.com", secure: true, api: false });
    expect(passkeySupport()).toEqual({ supported: false, reason: "no_webauthn" });
  });
});

describe("options conversion (manual fallback)", () => {
  beforeEach(() => {
    vi.stubGlobal("PublicKeyCredential", class {});
  });
  afterEach(() => vi.unstubAllGlobals());

  it("turns registration options' base64url strings into buffers", () => {
    const out = creationOptionsFromJson({
      challenge: "-_8",
      rp: { id: "console.test", name: "VegaDuta" },
      user: { id: "aGk", name: "owner", displayName: "Owner" },
      pubKeyCredParams: [{ type: "public-key", alg: -7 }],
      excludeCredentials: [{ type: "public-key", id: "AQID" }],
      authenticatorSelection: { residentKey: "required", userVerification: "required" },
    });
    expect(asArray(out.challenge as ArrayBuffer)).toEqual([0xfb, 0xff]);
    expect(asArray(out.user.id as ArrayBuffer)).toEqual([104, 105]);
    expect(asArray(out.excludeCredentials![0].id as ArrayBuffer)).toEqual([1, 2, 3]);
    expect(out.rp).toEqual({ id: "console.test", name: "VegaDuta" });
    expect(out.authenticatorSelection).toEqual({ residentKey: "required", userVerification: "required" });
  });

  it("turns login options into buffers and keeps an empty allow list (discoverable credentials)", () => {
    const out = requestOptionsFromJson({ challenge: "AQID", rpId: "console.test", userVerification: "required", allowCredentials: [] });
    expect(asArray(out.challenge as ArrayBuffer)).toEqual([1, 2, 3]);
    expect(out.allowCredentials).toEqual([]);
    expect(out.rpId).toBe("console.test");
  });

  it("prefers the browser's own JSON parsers when they exist", () => {
    const parseCreationOptionsFromJSON = vi.fn(() => ({ native: "creation" }));
    const parseRequestOptionsFromJSON = vi.fn(() => ({ native: "request" }));
    vi.stubGlobal("PublicKeyCredential", Object.assign(class {}, { parseCreationOptionsFromJSON, parseRequestOptionsFromJSON }));
    const input = { challenge: "AQID" };
    expect(creationOptionsFromJson(input)).toEqual({ native: "creation" });
    expect(requestOptionsFromJson(input)).toEqual({ native: "request" });
    expect(parseCreationOptionsFromJSON).toHaveBeenCalledWith(input);
    expect(parseRequestOptionsFromJSON).toHaveBeenCalledWith(input);
  });
});

describe("response serialisation", () => {
  const rawId = bytes(1, 2, 3);
  const attestation = {
    id: "AQID",
    rawId,
    type: "public-key",
    authenticatorAttachment: "platform",
    getClientExtensionResults: () => ({}),
    response: {
      clientDataJSON: bytes(104, 105),
      attestationObject: bytes(0xfb, 0xff),
      getTransports: () => ["internal"],
      getPublicKeyAlgorithm: () => -7,
      getPublicKey: () => bytes(7),
      getAuthenticatorData: () => bytes(8),
    },
  } as unknown as PublicKeyCredential;

  it("builds the toJSON() shape by hand when toJSON is missing", () => {
    expect(JSON.parse(registrationToJson(attestation))).toEqual({
      id: "AQID",
      rawId: "AQID",
      type: "public-key",
      authenticatorAttachment: "platform",
      clientExtensionResults: {},
      response: {
        clientDataJSON: "aGk",
        attestationObject: "-_8",
        transports: ["internal"],
        authenticatorData: "CA",
        publicKey: "Bw",
        publicKeyAlgorithm: -7,
      },
    });
  });

  it("uses toJSON() when the browser provides it", () => {
    const withNative = { ...attestation, toJSON: () => ({ id: "native" }) } as unknown as PublicKeyCredential;
    expect(registrationToJson(withNative)).toBe('{"id":"native"}');
    expect(assertionToJson(withNative)).toBe('{"id":"native"}');
  });

  it("serialises an assertion, with a null userHandle when there is none", () => {
    const assertion = {
      id: "AQID",
      rawId,
      type: "public-key",
      getClientExtensionResults: () => ({}),
      response: {
        clientDataJSON: bytes(104, 105),
        authenticatorData: bytes(1),
        signature: bytes(2, 3),
        userHandle: null,
      },
    } as unknown as PublicKeyCredential;
    expect(JSON.parse(assertionToJson(assertion))).toEqual({
      id: "AQID",
      rawId: "AQID",
      type: "public-key",
      clientExtensionResults: {},
      response: { clientDataJSON: "aGk", authenticatorData: "AQ", signature: "AgM", userHandle: null },
    });
  });
});

describe("ceremonies", () => {
  function supported(credentials: Partial<CredentialsContainer>) {
    Object.defineProperty(window, "location", { configurable: true, value: { ...window.location, hostname: "localhost" } });
    Object.defineProperty(window, "isSecureContext", { configurable: true, value: true });
    Object.defineProperty(window, "PublicKeyCredential", { configurable: true, value: class {} });
    vi.stubGlobal("PublicKeyCredential", class {});
    Object.defineProperty(navigator, "credentials", { configurable: true, value: credentials });
  }
  afterEach(() => vi.unstubAllGlobals());

  const dom = (name: string) => Object.assign(new Error(name), { name });
  const reg = { challenge: "AQID", rp: { id: "localhost", name: "x" }, user: { id: "aGk", name: "o", displayName: "o" } };

  it("create resolves the JSON string of the registration", async () => {
    const credential = { id: "x", rawId: bytes(1), type: "public-key", getClientExtensionResults: () => ({}), response: { clientDataJSON: bytes(1), attestationObject: bytes(2) }, toJSON: () => ({ id: "x" }) };
    const create = vi.fn(async () => credential);
    supported({ create } as unknown as CredentialsContainer);
    await expect(createPasskey(reg)).resolves.toBe('{"id":"x"}');
    expect(create).toHaveBeenCalledOnce();
  });

  it("get resolves the JSON string of the assertion", async () => {
    const get = vi.fn(async () => ({ toJSON: () => ({ id: "y" }) }));
    supported({ get } as unknown as CredentialsContainer);
    await expect(getPasskey({ challenge: "AQID" })).resolves.toBe('{"id":"y"}');
  });

  it("maps a dismissed or timed-out prompt, an existing credential and an origin problem to stable codes", async () => {
    for (const [domName, code] of [
      ["NotAllowedError", "passkey_cancelled"],
      ["AbortError", "passkey_cancelled"],
      ["InvalidStateError", "passkey_exists"],
      ["SecurityError", "passkey_unsupported"],
      ["NotSupportedError", "passkey_unsupported"],
      ["WeirdError", "passkey_failed"],
    ] as const) {
      supported({ create: vi.fn(async () => { throw dom(domName); }) } as unknown as CredentialsContainer);
      await expect(createPasskey(reg), domName).rejects.toMatchObject({ name: "PasskeyError", code });
    }
  });

  it("a null credential counts as cancelled", async () => {
    supported({ get: vi.fn(async () => null) } as unknown as CredentialsContainer);
    await expect(getPasskey({ challenge: "AQID" })).rejects.toBeInstanceOf(PasskeyError);
  });

  it("refuses to start where passkeys cannot work", async () => {
    Object.defineProperty(window, "location", { configurable: true, value: { ...window.location, hostname: "10.1.1.1" } });
    await expect(createPasskey(reg)).rejects.toMatchObject({ code: "passkey_unsupported" });
    await expect(getPasskey({ challenge: "AQID" })).rejects.toMatchObject({ code: "passkey_unsupported" });
  });
});

describe("guessDeviceLabel", () => {
  it("names browser and OS from the user agent, with a fallback", () => {
    const ua = vi.spyOn(navigator, "userAgent", "get");
    ua.mockReturnValue("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15");
    expect(guessDeviceLabel("This device")).toBe("Safari on macOS");
    ua.mockReturnValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 Edg/120.0");
    expect(guessDeviceLabel("This device")).toBe("Edge on Windows");
    ua.mockReturnValue("curl/8");
    expect(guessDeviceLabel("This device")).toBe("This device");
    ua.mockRestore();
  });
});

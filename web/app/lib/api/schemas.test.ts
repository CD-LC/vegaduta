// FE-030: boundary validation for the session/identity reads and the stream
// event payloads. Pins the three behaviours the schemas promise: valid input
// passes through, unknown extra fields survive (core ships fields before this
// build knows them), and malformed input is logged once and degraded - never
// thrown.
import { beforeEach, describe, expect, it, vi } from "vitest";

const reportClientErrorMock = vi.hoisted(() => vi.fn());
vi.mock("../clientLogger", () => ({ reportClientError: reportClientErrorMock }));

import {
  AccountResponseSchema,
  MeResponseSchema,
  TenantSettingsResponseSchema,
  VoiceDoneEventSchema,
  parseAtBoundary,
  parseStepEventPayload,
  resetSchemaReportsForTests,
  type AccountResponse,
  type MeResponse,
  type TenantSettingsResponse,
} from "./schemas";

beforeEach(() => {
  reportClientErrorMock.mockReset();
  resetSchemaReportsForTests();
});

describe("MeResponseSchema (GET /api/me)", () => {
  it("accepts a valid payload unchanged", () => {
    const me = { subject: "u-1", tenantId: "t-1", roles: ["tenant-admin"], permissions: ["MANAGE_USERS"] };
    expect(parseAtBoundary(MeResponseSchema, me, "GET /api/me")).toEqual(me);
    expect(reportClientErrorMock).not.toHaveBeenCalled();
  });

  it("keeps unknown extra fields", () => {
    const me: MeResponse = { subject: "u-1", tenantId: "t-1", roles: [], permissions: [], plan: "PREMIUM" };
    expect(parseAtBoundary(MeResponseSchema, me, "GET /api/me")).toEqual(me);
    expect(reportClientErrorMock).not.toHaveBeenCalled();
  });

  it("logs a malformed payload and keeps only the fields that validate", () => {
    const result = parseAtBoundary(
      MeResponseSchema,
      { subject: "u-1", tenantId: "t-1", roles: "tenant-admin", permissions: ["A"], extra: 1 },
      "GET /api/me"
    );
    expect(result).toEqual({ subject: "u-1", tenantId: "t-1", permissions: ["A"], extra: 1 });
    expect(reportClientErrorMock).toHaveBeenCalledTimes(1);
    expect(reportClientErrorMock.mock.calls[0][0]).toBe("client_error");
    expect(String(reportClientErrorMock.mock.calls[0][2].detail)).toContain("roles");
  });

  it("degrades a non-object payload to null without throwing", () => {
    expect(parseAtBoundary(MeResponseSchema, "<html>502</html>", "GET /api/me")).toBeNull();
    expect(parseAtBoundary(MeResponseSchema, null, "GET /api/me")).toBeNull();
    expect(reportClientErrorMock).toHaveBeenCalled();
  });
});

describe("TenantSettingsResponseSchema (GET /api/tenant/settings)", () => {
  it("accepts the full entitlement payload, nulls included", () => {
    const settings: TenantSettingsResponse = {
      tenantId: "t-1",
      accountType: "ORGANIZATION",
      experienceMode: "ADVANCED",
      maxUsers: 5,
      maxAgents: null,
      organizationName: "Acme",
      plan: "PREMIUM",
      maxWorkflows: null,
      usePlatformLlm: true,
      byokAllowed: true,
      whatsappAllowed: false,
      whiteLabel: false,
      maxKbCollections: 10,
      liveVisionAllowed: false,
      brandDisplayName: null,
      brandLogoUrl: null,
    };
    expect(parseAtBoundary(TenantSettingsResponseSchema, settings, "settings")).toEqual(settings);
    expect(reportClientErrorMock).not.toHaveBeenCalled();
  });

  it("drops a wrongly-typed entitlement flag but keeps the rest", () => {
    const result = parseAtBoundary(
      TenantSettingsResponseSchema,
      { plan: "FREE", byokAllowed: "yes", organizationName: "Acme", newFlag: true },
      "settings"
    );
    expect(result).toEqual({ plan: "FREE", organizationName: "Acme", newFlag: true });
    expect(reportClientErrorMock).toHaveBeenCalledTimes(1);
  });
});

describe("AccountResponseSchema (GET /api/account)", () => {
  it("accepts nulls for every field", () => {
    const account: AccountResponse = { email: "a@example.com", phoneNumber: null, avatarUrl: null };
    expect(parseAtBoundary(AccountResponseSchema, account, "account")).toEqual(account);
  });
});

describe("mismatch reporting", () => {
  it("reports the same mismatch only once per label", () => {
    for (let i = 0; i < 5; i++) parseAtBoundary(MeResponseSchema, { subject: 1 }, "GET /api/me");
    expect(reportClientErrorMock).toHaveBeenCalledTimes(1);
  });
});

describe("parseStepEventPayload", () => {
  it("parses a valid step event", () => {
    const event = parseStepEventPayload('{"type":"tool_call","id":3,"tool":"web_search","args":"{}"}');
    expect(event).toEqual({ type: "tool_call", id: 3, tool: "web_search", args: "{}" });
    expect(reportClientErrorMock).not.toHaveBeenCalled();
  });

  it("passes unknown event types and extra fields through", () => {
    const event = parseStepEventPayload('{"type":"heartbeat","seq":9}');
    expect(event).toEqual({ type: "heartbeat", seq: 9 });
    expect(reportClientErrorMock).not.toHaveBeenCalled();
  });

  it("returns null for malformed JSON and for a non-object payload", () => {
    expect(parseStepEventPayload("{not json")).toBeNull();
    expect(parseStepEventPayload("42")).toBeNull();
    expect(reportClientErrorMock).toHaveBeenCalled();
  });

  it("keeps the valid fields of an event with a wrongly-typed field", () => {
    const event = parseStepEventPayload('{"type":"tool_result","id":"seven","preview":"ok"}');
    expect(event).toEqual({ type: "tool_result", preview: "ok" });
    expect(reportClientErrorMock).toHaveBeenCalledTimes(1);
  });
});

describe("VoiceDoneEventSchema", () => {
  it("accepts a done summary with extra fields", () => {
    const done = { sessionId: "s", transcript: "hi", replyText: "hello", speechText: "hello", audioBase64: "", ms: 12 };
    expect(parseAtBoundary(VoiceDoneEventSchema, done, "voice-turn:done")).toEqual(done);
  });
});

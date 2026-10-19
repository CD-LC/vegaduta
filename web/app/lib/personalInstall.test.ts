import { describe, expect, it, vi } from "vitest";
import { LOCAL_MODEL_LABEL, hiddenOnPersonalInstall, isPersonalInstall, poolLabel, showPlatformPoolToggle, workflowNodeOffered } from "./personalInstall";

// getPublicEnv reads NEXT_PUBLIC_AUTH_MODE from the public runtime config; the
// test setup leaves it unset, which is the hosted/Keycloak default. Local-auth
// ("personal install") is exercised by stubbing the env lookup - see
// authMode.test.ts for the same approach.
vi.mock("./authMode", () => ({ isLocalAuth: vi.fn() }));
vi.mock("./env", () => ({ getPublicEnv: vi.fn() }));
import { isLocalAuth } from "./authMode";
import { getPublicEnv } from "./env";

const local = () => {
  vi.mocked(isLocalAuth).mockReturnValue(true);
  vi.mocked(getPublicEnv).mockReturnValue(undefined);
};
const hosted = () => {
  vi.mocked(isLocalAuth).mockReturnValue(false);
  vi.mocked(getPublicEnv).mockReturnValue(undefined);
};
// Lite: Keycloak auth, but the self-hosted single-box tier (NEXT_PUBLIC_DEPLOY_ENV=lite).
const lite = () => {
  hosted();
  vi.mocked(getPublicEnv).mockImplementation((key) => (key === "NEXT_PUBLIC_DEPLOY_ENV" ? "lite" : undefined));
};

describe("hiddenOnPersonalInstall", () => {
  it("hides multi-tenant surfaces on a personal install", () => {
    local();
    for (const href of [
      "/shared-with-me",
      "/directory",
      "/studio",
      "/roi",
      "/admin/agent-trust",
      "/admin/commerce",
      "/admin/payments",
      "/admin/branding",
      "/admin/mcp-servers",
      "/admin/api-keys",
      "/admin/sso",
      "/admin/leads",
      "/features",
    ]) {
      expect(hiddenOnPersonalInstall(href), href).toBe(true);
    }
  });

  it("also hides routes nested under a hidden one", () => {
    local();
    expect(hiddenOnPersonalInstall("/admin/payments/mandates")).toBe(true);
    expect(hiddenOnPersonalInstall("/studio/clips")).toBe(true);
    // ... but not routes that merely share a prefix string.
    expect(hiddenOnPersonalInstall("/directory-2")).toBe(false);
    expect(hiddenOnPersonalInstall("/admin/api-keys-extra")).toBe(false);
  });

  it("keeps the surfaces a personal install actually uses", () => {
    local();
    for (const href of [
      "/assistant",
      "/agents",
      "/providers",
      "/troubleshoot",
      "/edge",
      "/memory",
      "/knowledge",
      "/secrets",
      "/admin/channels",
      "/admin/access",
      "/usage",
      "/learn",
      "/help",
    ]) {
      expect(hiddenOnPersonalInstall(href), href).toBe(false);
    }
  });

  it("hides nothing multi-tenant on a hosted (Keycloak) install", () => {
    hosted();
    expect(hiddenOnPersonalInstall("/admin/payments")).toBe(false);
    expect(hiddenOnPersonalInstall("/studio")).toBe(false);
  });

  it("folds Playground, Quick Task and Jobs into the Assistant on a personal install only", () => {
    local();
    for (const href of ["/playground", "/quick-task", "/jobs"]) expect(hiddenOnPersonalInstall(href), href).toBe(true);
    expect(hiddenOnPersonalInstall("/assistant")).toBe(false);
    hosted();
    for (const href of ["/playground", "/quick-task", "/jobs"]) expect(hiddenOnPersonalInstall(href), href).toBe(false);
    // The Assistant is for every install - the agent behind it is per user.
    expect(hiddenOnPersonalInstall("/assistant")).toBe(false);
    expect(hiddenOnPersonalInstall("/assistant-tools")).toBe(false);
  });

  it("lists Add-ons on personal and lite installs (the registry is install-wide and host-applied)", () => {
    local();
    expect(hiddenOnPersonalInstall("/add-ons")).toBe(false);
    lite();
    expect(hiddenOnPersonalInstall("/add-ons")).toBe(false);
    hosted();
    expect(hiddenOnPersonalInstall("/add-ons")).toBe(true);
    expect(hiddenOnPersonalInstall("/add-ons-extra")).toBe(false);
  });
});

describe("pool wording on a personal install", () => {
  it("calls the pool the local model, matching core's bootstrap label", () => {
    local();
    expect(isPersonalInstall()).toBe(true);
    expect(LOCAL_MODEL_LABEL).toBe("Local model (Ollama)");
    expect(poolLabel("Platform LLM")).toBe("Local model (Ollama)");
    expect(poolLabel("Platform-hosted (shared pool)")).toBe("Local model (Ollama)");
    expect(poolLabel("the shared platform pool", "the local model (Ollama)")).toBe("the local model (Ollama)");
  });

  it("hides the 'Use platform LLM' toggle, since the local model is the pool and always on", () => {
    local();
    expect(showPlatformPoolToggle()).toBe(false);
  });

  it("keeps the hosted wording and the toggle on a multi-tenant install", () => {
    hosted();
    expect(isPersonalInstall()).toBe(false);
    expect(poolLabel("Platform LLM")).toBe("Platform LLM");
    expect(poolLabel("the shared platform pool", "the local model")).toBe("the shared platform pool");
    expect(showPlatformPoolToggle()).toBe(true);
  });
});

describe("workflowNodeOffered", () => {
  it("stops offering nodes a one-person install cannot run", () => {
    local();
    for (const type of ["wait", "voice_menu", "bulk_email", "crm_lead", "device_control"]) {
      expect(workflowNodeOffered(type)).toBe(false);
    }
    expect(workflowNodeOffered("http_request")).toBe(true);
  });
  it("offers every node on a hosted install", () => {
    hosted();
    expect(workflowNodeOffered("voice_menu")).toBe(true);
    expect(workflowNodeOffered("wait")).toBe(true);
  });
});

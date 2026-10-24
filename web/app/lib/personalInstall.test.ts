import { describe, expect, it, vi } from "vitest";
import {
  LOCAL_MODEL_LABEL,
  hiddenOnPersonalInstall,
  isPersonalInstall,
  localModelAllowed,
  poolLabel,
  showPlatformPoolToggle,
  tierCommand,
  workflowNodeOffered,
} from "./personalInstall";

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
// Full: the multi-tenant tier (NEXT_PUBLIC_DEPLOY_ENV=full) - everything ships installed.
const full = () => {
  hosted();
  vi.mocked(getPublicEnv).mockImplementation((key) => (key === "NEXT_PUBLIC_DEPLOY_ENV" ? "full" : undefined));
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
      // Team access: one person on one machine (owner decision assumed per the lean-edition plan).
      "/admin/access",
      "/admin/users",
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

  it("lists Add-ons on personal and lite, hides it on full", () => {
    local();
    expect(hiddenOnPersonalInstall("/add-ons")).toBe(false);
    lite();
    expect(hiddenOnPersonalInstall("/add-ons")).toBe(false);
    full();
    // A full install ships every add-on - the rail drops the switchboard and the
    // page itself renders the "managed for you" notice.
    expect(hiddenOnPersonalInstall("/add-ons")).toBe(true);
    expect(hiddenOnPersonalInstall("/add-ons/anything")).toBe(true);
    hosted();
    // DEPLOY_ENV unset on a shared install keeps the switchboard (the switches
    // themselves still follow the caller's add-on permission).
    expect(hiddenOnPersonalInstall("/add-ons")).toBe(false);
    expect(hiddenOnPersonalInstall("/add-ons-extra")).toBe(false);
  });

  it("keeps local-model setup reachable on every tier; the page self-gates on capability", () => {
    local();
    expect(hiddenOnPersonalInstall("/providers/local-model")).toBe(false);
    lite();
    expect(hiddenOnPersonalInstall("/providers/local-model")).toBe(false);
    full();
    expect(hiddenOnPersonalInstall("/providers/local-model")).toBe(false);
    hosted();
    // The rail no longer hides it: the page asks core whether this install
    // manages a local model (localModelManagedHere) and shows the wizard or
    // the operator-managed notice accordingly.
    expect(hiddenOnPersonalInstall("/providers/local-model")).toBe(false);
    // The catalogue itself stays: the tenant may look, just not change the server.
    expect(hiddenOnPersonalInstall("/providers")).toBe(false);
  });

  it("localModelAllowed follows the personal install only", () => {
    local();
    expect(localModelAllowed()).toBe(true);
    lite();
    expect(localModelAllowed()).toBe(false);
    full();
    expect(localModelAllowed()).toBe(false);
  });
});

describe("edition seed", () => {
  // NEXT_PUBLIC_EDITION wins over the deploy-env derivation the other cases stub; local auth stays personal
  // whatever it says (that is what local auth meant before NEXT_PUBLIC_EDITION existed).
  const seeded = (edition: string, auth: boolean) => {
    vi.mocked(isLocalAuth).mockReturnValue(auth);
    vi.mocked(getPublicEnv).mockImplementation((key) => (key === "NEXT_PUBLIC_EDITION" ? edition : undefined));
  };

  it("makes the install personal from NEXT_PUBLIC_EDITION alone", () => {
    seeded("personal", false);
    expect(isPersonalInstall()).toBe(true);
    expect(localModelAllowed()).toBe(true);
    expect(hiddenOnPersonalInstall("/admin/payments")).toBe(true);
    expect(workflowNodeOffered("voice_menu")).toBe(false);
    expect(tierCommand("")).toBe("scripts/vegaduta personal");
  });

  it("keeps a local-auth install personal even when NEXT_PUBLIC_EDITION says lite or full", () => {
    seeded("lite", true);
    expect(isPersonalInstall()).toBe(true);
    seeded("full", true);
    expect(isPersonalInstall()).toBe(true);
    expect(tierCommand("")).toBe("scripts/vegaduta personal");
  });

  it("makes the install lite or full from NEXT_PUBLIC_EDITION when sign-in is not local", () => {
    seeded("lite", false);
    expect(isPersonalInstall()).toBe(false);
    expect(tierCommand("--browser")).toBe("scripts/vegaduta up --lite --browser");
    expect(hiddenOnPersonalInstall("/providers/local-model")).toBe(false);
    seeded("full", false);
    expect(hiddenOnPersonalInstall("/add-ons")).toBe(true);
    expect(hiddenOnPersonalInstall("/admin/payments")).toBe(false);
    expect(tierCommand("")).toBe("scripts/vegaduta up --full");
  });
});

describe("tierCommand", () => {
  it("names the personal launcher on a personal install", () => {
    local();
    expect(tierCommand("--local-ai=host")).toBe("scripts/vegaduta personal --local-ai=host");
    expect(tierCommand("")).toBe("scripts/vegaduta personal");
  });
  it("names `up --lite` on the lite tier and `up --full` on the shared one", () => {
    lite();
    expect(tierCommand("--browser")).toBe("scripts/vegaduta up --lite --browser");
    hosted();
    expect(tierCommand("--browser")).toBe("scripts/vegaduta up --full --browser");
    expect(tierCommand("")).toBe("scripts/vegaduta up --full");
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

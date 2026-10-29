import { beforeEach, describe, expect, it, vi } from "vitest";

// The gate reads the edition from lib/edition.ts, which reads these two. The test setup leaves both unset
// (an undeclared, shared install); each case stubs what it needs.
vi.mock("./authMode", () => ({ isLocalAuth: vi.fn() }));
vi.mock("./env", () => ({ getPublicEnv: vi.fn() }));
import { isLocalAuth } from "./authMode";
import { getPublicEnv } from "./env";
import { EDITION_GATES, EDITION_ROUTES, unavailableFeature } from "./editionCapabilities";
import { personalRoute, railHidden } from "./edition";

const env = (values: Record<string, string>) =>
  vi.mocked(getPublicEnv).mockImplementation((key) => values[key] || undefined);

const personal = () => {
  vi.mocked(isLocalAuth).mockReturnValue(true);
  env({ NEXT_PUBLIC_CODE_INTEL_URL: "http://code-intel:9000" });
};

beforeEach(() => {
  vi.mocked(isLocalAuth).mockReturnValue(false);
  env({ NEXT_PUBLIC_CODE_INTEL_URL: "http://code-intel:9000" });
});

describe("unavailableFeature", () => {
  it("gates multi-tenant and Keycloak pages on a personal install, including nested routes", () => {
    personal();
    expect(unavailableFeature("/admin/sso")?.reason).toBe("identity");
    expect(unavailableFeature("/admin/payments/mandates")?.reason).toBe("billing");
    expect(unavailableFeature("/shared-with-me")?.reason).toBe("team");
    // /admin/access and /admin/users are available on personal: they are how the
    // owner invites teammates (issueInviteLink mints /login?setup= links - local
    // auth has no public signup and no mail server).
    expect(unavailableFeature("/admin/access")).toBeNull();
    expect(unavailableFeature("/admin/users")).toBeNull();
  });

  it("is driven by NEXT_PUBLIC_EDITION too, with no local-auth signal", () => {
    env({ NEXT_PUBLIC_EDITION: "personal", NEXT_PUBLIC_CODE_INTEL_URL: "http://code-intel:9000" });
    expect(unavailableFeature("/admin/sso")?.reason).toBe("identity");
  });

  it("does not gate pages a personal install uses, or prefix lookalikes", () => {
    personal();
    for (const p of ["/assistant", "/agents", "/admin/channels", "/directory-2", "/admin/api-keys-extra", "/usage"]) {
      expect(unavailableFeature(p), p).toBeNull();
    }
  });

  it("does not gate pages that redirect or live outside the console shell", () => {
    personal();
    for (const p of ["/playground", "/quick-task", "/jobs", "/signup", "/try", "/view-as"]) {
      expect(unavailableFeature(p), p).toBeNull();
    }
  });

  it("gates nothing personal-scoped on Lite or Full", () => {
    for (const edition of ["lite", "full", ""]) {
      env({ NEXT_PUBLIC_EDITION: edition, NEXT_PUBLIC_CODE_INTEL_URL: "http://code-intel:9000" });
      for (const g of EDITION_GATES.filter((x) => x.scope === "personal")) {
        expect(unavailableFeature(g.route), `${edition || "undeclared"} ${g.route}`).toBeNull();
      }
    }
  });

  it("gates Code Intel on any install without a Code Intel URL", () => {
    env({});
    expect(unavailableFeature("/code-intel")?.reason).toBe("service");
    env({ NEXT_PUBLIC_CODE_INTEL_URL: "http://code-intel:9000" });
    expect(unavailableFeature("/code-intel")).toBeNull();
  });
});

describe("EDITION_ROUTES against the deep-link gates", () => {
  it("every rail:true route is also deep-link gated (or redirects away)", () => {
    for (const r of EDITION_ROUTES.filter((x) => x.scope === "personal" && x.rail)) {
      const gated = EDITION_GATES.some((g) => g.route === r.route);
      expect(gated || r.redirectTo !== undefined, r.route).toBe(true);
    }
  });

  it("every gate comes from a table row, with its icon and label", () => {
    expect(EDITION_GATES.length).toBe(EDITION_ROUTES.filter((r) => r.gate).length);
    for (const g of EDITION_GATES) {
      expect(g.navKey, g.route).toBeTruthy();
      expect(g.icon, g.route).toBeDefined();
      expect(EDITION_ROUTES.find((r) => r.route === g.route)?.navKey, g.route).toBe(g.navKey);
    }
  });

  it("rail-hidden, gated and denied all follow one table on a personal install", () => {
    personal();
    for (const g of EDITION_GATES.filter((x) => x.scope === "personal")) {
      const row = personalRoute(g.route);
      expect(row?.route, g.route).toBe(g.route);
      expect(railHidden(g.route), g.route).toBe(row?.rail);
      expect(unavailableFeature(g.route), g.route).toBe(g);
    }
  });
});

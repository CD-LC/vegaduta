import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("./personalInstall", () => ({ isPersonalInstall: vi.fn() }));
vi.mock("./env", () => ({ getPublicEnv: vi.fn() }));
import { isPersonalInstall } from "./personalInstall";
import { getPublicEnv } from "./env";
import { EDITION_GATES, unavailableFeature } from "./editionCapabilities";

beforeEach(() => {
  vi.mocked(isPersonalInstall).mockReturnValue(false);
  vi.mocked(getPublicEnv).mockReturnValue("http://code-intel:9000");
});

describe("unavailableFeature", () => {
  it("gates multi-tenant and Keycloak pages on a personal install, including nested routes", () => {
    vi.mocked(isPersonalInstall).mockReturnValue(true);
    expect(unavailableFeature("/admin/sso")?.reason).toBe("identity");
    expect(unavailableFeature("/admin/payments/mandates")?.reason).toBe("billing");
    expect(unavailableFeature("/shared-with-me")?.reason).toBe("team");
  });

  it("does not gate pages a personal install uses, or prefix lookalikes", () => {
    vi.mocked(isPersonalInstall).mockReturnValue(true);
    for (const p of ["/assistant", "/agents", "/admin/channels", "/directory-2", "/admin/api-keys-extra"]) {
      expect(unavailableFeature(p), p).toBeNull();
    }
  });

  it("gates nothing personal-scoped on Lite or Full", () => {
    for (const g of EDITION_GATES.filter((x) => x.scope === "personal")) {
      expect(unavailableFeature(g.route), g.route).toBeNull();
    }
  });

  it("gates Code Intel on any install without a Code Intel URL", () => {
    vi.mocked(getPublicEnv).mockReturnValue("");
    expect(unavailableFeature("/code-intel")?.reason).toBe("service");
    vi.mocked(getPublicEnv).mockReturnValue("http://code-intel:9000");
    expect(unavailableFeature("/code-intel")).toBeNull();
  });
});

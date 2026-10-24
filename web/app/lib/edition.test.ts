import fs from "node:fs";
import path from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  EDITION_ROUTES,
  PERSONAL_PLATFORM_ADMIN_ALLOW,
  PERSONAL_PUBLIC_DENY,
  channelOffered,
  declaredEdition,
  edition,
  isFullEdition,
  isLiteEdition,
  isPersonalDenied,
  isPersonalEdition,
  isPersonalPlatformAdminAllowed,
  parseEdition,
  personalRoute,
  railHidden,
  underRoute,
} from "./edition";

// getPublicEnv reads window.__ENV, then the live process.env value, so vi.stubEnv drives it.
const stub = (env: Record<string, string>) => {
  for (const [k, v] of Object.entries(env)) vi.stubEnv(k, v);
};
const asPersonal = () => stub({ NEXT_PUBLIC_EDITION: "personal" });
const asLite = () => stub({ NEXT_PUBLIC_EDITION: "lite" });
const asFull = () => stub({ NEXT_PUBLIC_EDITION: "full" });
const undeclared = (): void => {};

afterEach(() => {
  vi.unstubAllEnvs();
  delete window.__ENV;
});

describe("parseEdition", () => {
  it("accepts the three tiers, ignoring case and whitespace, and nothing else", () => {
    expect(parseEdition(" Personal ")).toBe("personal");
    expect(parseEdition("LITE")).toBe("lite");
    expect(parseEdition("full")).toBe("full");
    for (const bad of ["", "pro", "enterprise", "true", null, undefined])
      expect(parseEdition(bad), String(bad)).toBeNull();
  });
});

describe("edition resolution", () => {
  it("is undeclared, and every feature on, when nothing says otherwise", () => {
    expect(declaredEdition()).toBeNull();
    expect(edition()).toBe("full");
    expect(isPersonalEdition()).toBe(false);
    expect(isFullEdition()).toBe(false);
    expect(isLiteEdition()).toBe(false);
  });

  it("takes NEXT_PUBLIC_EDITION first", () => {
    asPersonal();
    expect(edition()).toBe("personal");
    expect(isPersonalEdition()).toBe(true);
    vi.unstubAllEnvs();
    asLite();
    expect(edition()).toBe("lite");
    expect(isLiteEdition()).toBe(true);
    vi.unstubAllEnvs();
    asFull();
    expect(isFullEdition()).toBe(true);
  });

  it("reads the runtime window.__ENV value like every other public key", () => {
    window.__ENV = { NEXT_PUBLIC_EDITION: "personal" };
    expect(isPersonalEdition()).toBe(true);
  });

  it("falls back to the pre-edition derivation: local auth is personal", () => {
    stub({ NEXT_PUBLIC_AUTH_MODE: "local" });
    expect(declaredEdition()).toBe("personal");
    expect(isPersonalEdition()).toBe(true);
  });

  it("falls back to NEXT_PUBLIC_DEPLOY_ENV=lite|full, ignoring other deploy names", () => {
    stub({ NEXT_PUBLIC_DEPLOY_ENV: "lite" });
    expect(edition()).toBe("lite");
    vi.unstubAllEnvs();
    stub({ NEXT_PUBLIC_DEPLOY_ENV: "full" });
    expect(isFullEdition()).toBe(true);
    for (const name of ["production", "staging", "personal"]) {
      vi.unstubAllEnvs();
      stub({ NEXT_PUBLIC_DEPLOY_ENV: name });
      expect(declaredEdition(), name).toBeNull();
    }
  });

  it("resolves every combination of explicit edition, auth mode and deploy env", () => {
    // [NEXT_PUBLIC_EDITION, NEXT_PUBLIC_AUTH_MODE, NEXT_PUBLIC_DEPLOY_ENV] -> declared edition.
    // Local auth is personal whatever else is set (the pre-edition meaning); an explicit tier decides
    // when sign-in is not local; the deploy env is only the last fallback.
    const editions = ["", "personal", "lite", "full"];
    const auths = ["", "keycloak", "local"];
    const deploys = ["", "lite", "full", "production"];
    for (const e of editions)
      for (const a of auths)
        for (const d of deploys) {
          vi.unstubAllEnvs();
          const env: Record<string, string> = {};
          if (e) env.NEXT_PUBLIC_EDITION = e;
          if (a) env.NEXT_PUBLIC_AUTH_MODE = a;
          if (d) env.NEXT_PUBLIC_DEPLOY_ENV = d;
          stub(env);
          let want: ReturnType<typeof declaredEdition>;
          if (a === "local") want = "personal";
          else if (e) want = e as "personal" | "lite" | "full";
          else want = d === "lite" || d === "full" ? d : null;
          expect(declaredEdition(), `edition=${e} auth=${a} deploy=${d}`).toBe(want);
        }
  });

  it("does not let an explicit lite or full un-hide pages on a local-auth console", () => {
    stub({ NEXT_PUBLIC_EDITION: "full", NEXT_PUBLIC_AUTH_MODE: "local", NEXT_PUBLIC_DEPLOY_ENV: "lite" });
    expect(edition()).toBe("personal");
    vi.unstubAllEnvs();
    stub({ NEXT_PUBLIC_EDITION: "lite", NEXT_PUBLIC_AUTH_MODE: "local" });
    expect(isPersonalEdition()).toBe(true);
  });

  it("lets an explicit edition win over the deploy env when sign-in is not local", () => {
    stub({ NEXT_PUBLIC_EDITION: "full", NEXT_PUBLIC_AUTH_MODE: "keycloak", NEXT_PUBLIC_DEPLOY_ENV: "lite" });
    expect(edition()).toBe("full");
    vi.unstubAllEnvs();
    stub({ NEXT_PUBLIC_EDITION: "personal", NEXT_PUBLIC_DEPLOY_ENV: "full" });
    expect(edition()).toBe("personal");
  });

  it("treats an unrecognised NEXT_PUBLIC_EDITION as unset, so a typo never hides pages", () => {
    stub({ NEXT_PUBLIC_EDITION: "persnal" });
    expect(declaredEdition()).toBeNull();
    expect(isPersonalEdition()).toBe(false);
  });
});

describe("channelOffered", () => {
  it("drops the social channels on a personal install and keeps the rest", () => {
    asPersonal();
    for (const key of ["slack", "discord", "teams", "x", "Slack"]) expect(channelOffered(key), key).toBe(false);
    for (const key of ["telegram", "signal", "whatsapp", "whatsappqr", "imessage", "email", "voice"]) {
      expect(channelOffered(key), key).toBe(true);
    }
  });

  it("offers every channel on lite, full and an undeclared install", () => {
    for (const seed of [asLite, asFull, undeclared]) {
      vi.unstubAllEnvs();
      seed();
      for (const key of ["slack", "discord", "teams", "x", "telegram"]) expect(channelOffered(key), key).toBe(true);
    }
  });
});

describe("EDITION_ROUTES", () => {
  const personalRows = EDITION_ROUTES.filter((r) => r.scope === "personal");

  it("has one row per route", () => {
    const routes = EDITION_ROUTES.map((r) => r.route);
    expect(new Set(routes).size).toBe(routes.length);
  });

  it("lists every route the plan hides on a personal install", () => {
    const routes = EDITION_ROUTES.map((r) => r.route);
    for (const r of [
      "/shared-with-me",
      "/directory",
      "/studio",
      "/roi",
      "/features",
      "/admin/agent-trust",
      "/admin/commerce",
      "/admin/payments",
      "/admin/branding",
      "/admin/mcp-servers",
      "/admin/api-keys",
      "/admin/sso",
      "/admin/leads",
      "/admin/billing",
      "/admin/access",
      "/admin/users",
      "/playground",
      "/quick-task",
      "/jobs",
      "/view-as",
      "/signup",
      "/try",
      "/templates/mine",
      "/.well-known/agent-card.json",
      "/.well-known/agent.json",
    ]) {
      expect(routes, r).toContain(r);
    }
  });

  it("deep-link gates every route the rail hides, unless the page redirects away", () => {
    for (const r of personalRows.filter((x) => x.rail)) {
      expect(r.gate || r.redirectTo !== undefined, r.route).toBe(true);
    }
  });

  it("gives every gated row a nav label, and no redirecting row a gate or a deny", () => {
    for (const r of EDITION_ROUTES) {
      if (r.gate) expect(r.navKey, r.route).toBeTruthy();
      if (r.redirectTo !== undefined) {
        expect(r.gate, r.route).toBe(false);
        expect(r.deny, r.route).toBe(false);
        expect(r.redirectTo.startsWith("/"), r.route).toBe(true);
        expect(personalRoute(r.redirectTo), `${r.route} redirect target`).toBeNull();
      }
    }
  });

  it("gates nothing on lite, full or an undeclared install", () => {
    for (const seed of [asLite, asFull, undeclared]) {
      vi.unstubAllEnvs();
      seed();
      for (const r of personalRows) expect(railHidden(r.route), r.route).toBe(false);
    }
  });

  it("hides exactly the rail rows on a personal install, including nested routes", () => {
    asPersonal();
    for (const r of personalRows) expect(railHidden(r.route), r.route).toBe(r.rail);
    expect(railHidden("/admin/payments/mandates")).toBe(true);
    expect(railHidden("/admin/access?tab=roles")).toBe(true);
    for (const keep of [
      "/assistant",
      "/agents",
      "/providers",
      "/admin/channels",
      "/usage",
      "/admin",
      "/admin/maintenance",
    ]) {
      expect(railHidden(keep), keep).toBe(false);
    }
  });

  it("never matches a route that merely shares a prefix string", () => {
    asPersonal();
    for (const lookalike of ["/directory-2", "/admin/api-keys-extra", "/signup-help", "/jobsboard", "/viewas"]) {
      expect(railHidden(lookalike), lookalike).toBe(false);
      expect(isPersonalDenied(lookalike), lookalike).toBe(false);
    }
    expect(underRoute("/studio/clips", "/studio")).toBe(true);
    expect(underRoute("/studios", "/studio")).toBe(false);
  });
});

describe("proxy deny list", () => {
  it("denies the gated pages, the public pages and the agent cards", () => {
    for (const p of [
      "/admin/sso",
      "/admin/payments/mandates",
      "/shared-with-me",
      "/admin/access",
      "/admin/users",
      "/directory",
      "/signup",
      "/signup/complete",
      "/try",
      "/try/envoy-demo",
      "/view-as",
      "/templates/mine",
      "/.well-known/agent-card.json",
      "/.well-known/agent.json",
    ]) {
      expect(isPersonalDenied(p), p).toBe(true);
    }
  });

  it("leaves the pages another mechanism answers, and the pages a personal install uses", () => {
    // /playground, /quick-task and /jobs redirect to /assistant; the billing switch redirects /admin/billing;
    // /templates (the public gallery) stays, only /templates/mine is the submissions page.
    for (const p of [
      "/playground",
      "/quick-task",
      "/jobs",
      "/admin/billing",
      "/assistant",
      "/agents",
      "/admin/channels",
      "/templates",
      "/templates/abc",
      "/usage",
      "/code-intel",
      "/.well-known/change-password",
    ]) {
      expect(isPersonalDenied(p), p).toBe(false);
    }
  });

  it("exports the public (outside-the-console) subset for the proxy", () => {
    expect([...PERSONAL_PUBLIC_DENY].sort()).toEqual(
      [
        "/.well-known/agent-card.json",
        "/.well-known/agent.json",
        "/signup",
        "/templates/mine",
        "/try",
        "/view-as",
      ].sort()
    );
    for (const p of PERSONAL_PUBLIC_DENY) expect(isPersonalDenied(p), p).toBe(true);
  });

  it("is path-only: the caller decides whether the install is personal", () => {
    expect(isPersonalEdition()).toBe(false);
    expect(isPersonalDenied("/signup")).toBe(true);
  });
});

describe("platform-admin allowlist", () => {
  it("keeps the LLM pools, tool credentials and settings (and anything nested under them)", () => {
    expect(PERSONAL_PLATFORM_ADMIN_ALLOW).toEqual([
      "/platform-admin/llm-providers",
      "/platform-admin/tool-credentials",
      "/platform-admin/settings",
    ]);
    for (const p of PERSONAL_PLATFORM_ADMIN_ALLOW) expect(isPersonalPlatformAdminAllowed(p), p).toBe(true);
    expect(isPersonalPlatformAdminAllowed("/platform-admin/llm-providers/pool-1")).toBe(true);
  });

  it("lets the Overview root through (it redirects) and refuses the operator pages", () => {
    expect(isPersonalPlatformAdminAllowed("/platform-admin")).toBe(true);
    for (const p of [
      "/platform-admin/tenants",
      "/platform-admin/billing",
      "/platform-admin/ops",
      "/platform-admin/api/academy/learners",
      "/platform-admin/settings-extra",
    ]) {
      expect(isPersonalPlatformAdminAllowed(p), p).toBe(false);
    }
  });

  it("is not about other paths", () => {
    expect(isPersonalPlatformAdminAllowed("/assistant")).toBe(true);
    expect(isPersonalPlatformAdminAllowed("/platform-adminx")).toBe(true);
  });
});

describe("module contract", () => {
  it("stays importable from the proxy: only env.ts and authMode.ts, no React, icons or i18n", () => {
    const src = fs.readFileSync(path.resolve(process.cwd(), "app/lib/edition.ts"), "utf8");
    const imports = [...src.matchAll(/^import .* from "([^"]+)";/gm)].map((m) => m[1]).sort();
    expect(imports).toEqual(["./authMode", "./env"]);
  });
});

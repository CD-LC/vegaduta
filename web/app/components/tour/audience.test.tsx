// audience.ts: who the visitor is, and which steps each audience walks.
// Split from tour.test.tsx; shared mocks and fixtures live in ./tourTestSupport.

import { ABSENT, ALL, PRESENT, accessFor, fake, ids, mock, walk, type FakeStep } from "./tourTestSupport";
import { describe, expect, it } from "vitest";
import {
  EXPERIENCE_MODE_SIMPLE,
  MIN_TOUR_STEPS,
  resolveAccess,
  resolveAudience,
  selectSteps,
  type TourAccess,
} from "./audience";
import { TOUR_STEPS } from "../TourProvider";

describe("resolveAudience", () => {
  it("ranks platform-admin over tenant-admin over member", () => {
    // A platform-admin who ALSO holds tenant-admin gets the platform walk: the
    // narrower fact about them is that they operate the platform, and the
    // tenant walk's pricing and provisioning copy describes a workspace that
    // may not be theirs (AuthProvider.tsx:622 - they may have no tenant).
    expect(resolveAudience({ isAdmin: true, isPlatformAdmin: true })).toBe("platform-admin");
    expect(resolveAudience({ isAdmin: false, isPlatformAdmin: true })).toBe("platform-admin");
    expect(resolveAudience({ isAdmin: true, isPlatformAdmin: false })).toBe("tenant-admin");
    expect(resolveAudience({ isAdmin: false, isPlatformAdmin: false })).toBe("member");
  });

  it("degrades an unreadable identity to member, never to an admin walk", () => {
    // Unknown IDENTITY degrades narrower, and the direction matters: the
    // admin-only steps describe pages that redirect a non-admin to "/", so
    // guessing upward hands someone a tour of doors that close in their face.
    const junk = [undefined, null, {}, { isAdmin: "yes", isPlatformAdmin: "yes" }, { isAdmin: 1, isPlatformAdmin: 1 }];
    for (const opts of junk) {
      expect(
        resolveAudience(opts as unknown as { isAdmin: boolean; isPlatformAdmin: boolean }),
        `${JSON.stringify(opts)} resolved to something other than member`
      ).toBe("member");
    }
  });
});

describe("resolveAccess", () => {
  it("returns a complete, conservative snapshot when tenantSettings is null", () => {
    // null is TWO states this function cannot tell apart and must not try to:
    // AppChromeProvider's best-effort fetch still in flight, and a genuinely
    // tenant-less platform-admin. Both get the same answer - no confirmed
    // entitlements - because the alternative is pitching WhatsApp or BYOK or
    // Live Vision to a workspace nobody has confirmed carries them.
    const access = resolveAccess({ isAdmin: true, isPlatformAdmin: false, tenantSettings: null });

    expect(access).toEqual({
      audience: "tenant-admin",
      plan: null,
      accountType: null,
      experienceMode: null,
      whatsappAllowed: false,
      byokAllowed: false,
      liveVisionAllowed: false,
    });
  });

  it("does not throw on any shape a best-effort fetch can hand it", () => {
    const shapes: unknown[] = [undefined, null, 0, "", [], "PREMIUM", { plan: null }];
    for (const tenantSettings of shapes) {
      expect(() => resolveAccess({ isAdmin: false, isPlatformAdmin: false, tenantSettings })).not.toThrow();
    }
  });

  it("upper-cases enums but never coerces one into a tier it recognises", () => {
    const access = resolveAccess({
      isAdmin: false,
      isPlatformAdmin: false,
      tenantSettings: { plan: "premium", accountType: " b2c ", experienceMode: "simple" },
    });
    expect(access.plan).toBe("PREMIUM");
    expect(access.accountType).toBe("B2C");
    expect(access.experienceMode).toBe("SIMPLE");

    // An unknown tier stays unknown rather than being rounded to FREE: the
    // copy layer decides what to do with it, and it decides by falling back to
    // the claim that is true of everyone.
    const future = resolveAccess({
      isAdmin: false,
      isPlatformAdmin: false,
      tenantSettings: { plan: "ENTERPRISE" },
    });
    expect(future.plan).toBe("ENTERPRISE");
  });

  it('reads entitlements as strictly true, so "false" is not an entitlement', () => {
    // A backend that ever serialises these as strings would otherwise flip
    // every gate ON, which is the direction that produces a false promise.
    const access = resolveAccess({
      isAdmin: false,
      isPlatformAdmin: false,
      tenantSettings: {
        whatsappAllowed: "false",
        byokAllowed: "true",
        liveVisionAllowed: 1,
      },
    });
    expect(access.whatsappAllowed).toBe(false);
    expect(access.byokAllowed).toBe(false);
    expect(access.liveVisionAllowed).toBe(false);
  });
});

describe("selectSteps rule 1 - audience", () => {
  it("keeps only the steps tagged for this visitor, in input order", () => {
    const table = [
      fake("first", ALL),
      fake("admin-only", ["tenant-admin"]),
      fake("member-only", ["member"]),
      fake("both", ["member", "tenant-admin"]),
      fake("help", ALL),
    ];

    expect(ids(selectSteps(table, accessFor("member"), PRESENT))).toEqual(["first", "member-only", "both", "help"]);
    expect(ids(selectSteps(table, accessFor("tenant-admin"), PRESENT))).toEqual([
      "first",
      "admin-only",
      "both",
      "help",
    ]);
  });

  it("treats a missing or malformed `audiences` as every audience", () => {
    // Degrading an untagged step to "nobody" would delete it from every tour
    // silently; degrading it to "everybody" makes it visible, which is the
    // failure a human notices and fixes.
    const table = [
      fake("first", ALL),
      { id: "untagged", target: null } as unknown as FakeStep,
      { id: "empty", target: null, audiences: [] } as unknown as FakeStep,
      fake("help", ALL),
    ];
    expect(ids(selectSteps(table, accessFor("platform-admin"), PRESENT))).toEqual([
      "first",
      "untagged",
      "empty",
      "help",
    ]);
  });
});

describe("selectSteps rule 2 - entitlement", () => {
  it("drops a step whose `requires` is false for this tenant", () => {
    const table = [
      fake("first", ALL),
      fake("a", ALL),
      fake("gated", ALL, { requires: (a: TourAccess) => a.whatsappAllowed }),
      fake("b", ALL),
      fake("help", ALL),
    ];

    const without = accessFor("member", { experienceMode: "ADVANCED" });
    expect(ids(selectSteps(table, without, PRESENT))).toEqual(["first", "a", "b", "help"]);

    const with_ = accessFor("member", { experienceMode: "ADVANCED", whatsappAllowed: true });
    expect(ids(selectSteps(table, with_, PRESENT))).toEqual(["first", "a", "gated", "b", "help"]);
  });

  it("drops a step whose `requires` throws, rather than the whole tour", () => {
    // An entitlement we cannot evaluate is an entitlement we must not pitch -
    // but one bad predicate must not take the other steps down with it.
    const table = [
      fake("first", ALL),
      fake("a", ALL),
      fake("explodes", ALL, {
        requires: () => {
          throw new Error("settings shape changed");
        },
      }),
      fake("b", ALL),
      fake("help", ALL),
    ];
    expect(ids(selectSteps(table, accessFor("member"), PRESENT))).toEqual(["first", "a", "b", "help"]);
  });

  it("never reclaims an entitlement drop to pad the floor", () => {
    // The floor tops up from experience-mode drops ONLY. A rule-2 drop is a
    // false claim, and three steps is not worth one lie.
    const table = [
      fake("first", ALL),
      fake("gated1", ALL, { requires: () => false }),
      fake("gated2", ALL, { requires: () => false }),
      fake("gated3", ALL, { requires: () => false }),
      fake("help", ALL),
    ];
    // first + help survive (protected), the three gated ones do not, so the
    // floor cannot be met and the honest answer is "run no tour".
    expect(selectSteps(table, accessFor("member"), PRESENT)).toEqual([]);
  });
});

describe("selectSteps rule 3 - SIMPLE experience mode", () => {
  it("keeps only `simple` steps when the workspace runs SIMPLE", () => {
    const table = [
      fake("first", ALL, { simple: true }),
      fake("easy", ALL, { simple: true }),
      fake("advanced-1", ALL),
      fake("advanced-2", ALL),
      fake("easy-2", ALL, { simple: true }),
      fake("help", ALL, { simple: true }),
    ];

    const simple = accessFor("member", { experienceMode: EXPERIENCE_MODE_SIMPLE });
    expect(ids(selectSteps(table, simple, PRESENT))).toEqual(["first", "easy", "easy-2", "help"]);

    const advanced = accessFor("member", { experienceMode: "ADVANCED" });
    expect(ids(selectSteps(table, advanced, PRESENT))).toEqual(ids(table));
  });

  it("gives an UNRECOGNISED experience mode the fullest tour, not the reduced one", () => {
    // The split that matters: unknown IDENTITY and unknown ENTITLEMENT degrade
    // narrower (never over-promise), but unknown PRESENTATION PREFERENCE
    // degrades FULLER. A mode string this build has never heard of - including
    // a future one - must not silently amputate two thirds of the tour.
    const table = [
      fake("first", ALL, { simple: true }),
      fake("advanced-1", ALL),
      fake("advanced-2", ALL),
      fake("help", ALL, { simple: true }),
    ];
    for (const mode of ["GALACTIC", "", "   ", "Simple-ish", null, undefined, 7]) {
      const access = accessFor("member", { experienceMode: mode });
      expect(ids(selectSteps(table, access, PRESENT)), `experienceMode=${String(mode)}`).toEqual(ids(table));
    }
  });
});

describe("selectSteps rule 4 - reachability", () => {
  it("drops a step whose anchor is not on screen", () => {
    // THE GUARANTEE. The tour can never spotlight a row this visitor cannot
    // reach - which is what retired the old Channels dead end, where the
    // step's own "Show me" bounced a non-admin off /admin/channels.
    const table = [
      fake("first", ALL),
      fake("here", ALL, { target: "nav-here" }),
      fake("gone", ALL, { target: "nav-gone" }),
      fake("also-here", ALL, { target: "nav-also" }),
      fake("help", ALL, { target: "help-link" }),
    ];
    const present = (t: string | null) => t !== "nav-gone";
    expect(ids(selectSteps(table, accessFor("member"), present))).toEqual(["first", "here", "also-here", "help"]);
  });

  it("exempts a null target, and drops even a PROTECTED step whose anchor is gone", () => {
    // Protection (rule 5) exempts the first and help steps from rules 2 and 3,
    // but NOT from this one. Force-keeping a step whose anchor is missing would
    // re-create exactly the broken spotlight this rule exists to kill.
    const table = [
      fake("first", ALL, { target: "nav-first" }),
      fake("a", ALL),
      fake("b", ALL),
      fake("c", ALL),
      fake("help", ALL, { target: "help-link" }),
    ];
    expect(ids(selectSteps(table, accessFor("member"), ABSENT))).toEqual(["a", "b", "c"]);
  });

  it("skips the check entirely when no usable callback is supplied", () => {
    // A caller that cannot answer "is it on screen?" gets the fullest tour
    // rather than an empty one - the same degrade-fuller direction as an
    // unknown experience mode, and for the same reason.
    const table = [
      fake("first", ALL, { target: "nav-first" }),
      fake("a", ALL, { target: "nav-a" }),
      fake("b", ALL, { target: "nav-b" }),
      fake("help", ALL, { target: "help-link" }),
    ];
    const noCallback = undefined as unknown as (t: string | null) => boolean;
    expect(ids(selectSteps(table, accessFor("member"), noCallback))).toEqual(ids(table));

    const thrower = () => {
      throw new Error("detached document");
    };
    // A callback that throws drops only the step it was asked about.
    expect(selectSteps(table, accessFor("member"), thrower)).toEqual([]);
  });
});

describe("selectSteps rule 5 - the floor", () => {
  it("tops a short tour back up from the experience-mode drops, in order", () => {
    // Six designed steps, only two of them `simple`, so SIMPLE alone would
    // leave a 2-step tour. The reclaimed step lands back in its DESIGNED
    // position rather than being appended after the help step.
    const table = [
      fake("first", ALL, { simple: true }),
      fake("adv-1", ALL),
      fake("adv-2", ALL),
      fake("adv-3", ALL),
      fake("adv-4", ALL),
      fake("help", ALL, { simple: true }),
    ];
    const simple = accessFor("member", { experienceMode: EXPERIENCE_MODE_SIMPLE });
    const got = ids(selectSteps(table, simple, PRESENT));

    expect(got.length).toBeGreaterThanOrEqual(MIN_TOUR_STEPS);
    expect(got).toEqual(["first", "adv-1", "help"]);
    expect(got[0]).toBe("first");
    expect(got).toContain("help");
  });

  it("keeps the first and the help step even when a rule would have dropped them", () => {
    const table = [
      fake("first", ALL, { requires: () => false }),
      fake("a", ALL, { simple: true }),
      fake("b", ALL, { simple: true }),
      fake("c", ALL, { simple: true }),
      fake("help", ALL, { requires: () => false }),
    ];
    const simple = accessFor("member", { experienceMode: EXPERIENCE_MODE_SIMPLE });
    expect(ids(selectSteps(table, simple, PRESENT))).toEqual(["first", "a", "b", "c", "help"]);
  });

  it("protects the LAST step when the audience's set has no help step at all", () => {
    const table = [
      fake("first", ALL, { requires: () => false }),
      fake("a", ALL),
      fake("b", ALL),
      fake("tail", ALL, { requires: () => false }),
    ];
    expect(ids(selectSteps(table, accessFor("member"), PRESENT))).toEqual(["first", "a", "b", "tail"]);
  });

  it("returns a DELIBERATELY short designed set untouched", () => {
    // The platform-admin case. Three designed steps is not attrition, it is
    // the design - so the floor must not treat it as a tour that fell apart.
    const table = [fake("p1", ["platform-admin"]), fake("p2", ["platform-admin"]), fake("help", ALL)];
    expect(ids(selectSteps(table, accessFor("platform-admin"), PRESENT))).toEqual(["p1", "p2", "help"]);
    expect(table.length).toBeLessThanOrEqual(MIN_TOUR_STEPS);
  });

  it("returns [] rather than a one-step tour, and [] rather than throwing", () => {
    // "A one-step tour is worse than no tour" is a contract, not a preference:
    // the caller must read [] as "run nothing", NOT as "fall back to the full
    // catalogue" - falling back is how a member ends up on the admin walk.
    const table = [
      fake("first", ALL, { target: "gone" }),
      fake("a", ALL, { target: "gone" }),
      fake("b", ALL, { target: "gone" }),
      fake("c", ALL, { target: "gone" }),
      fake("help", ALL, { target: "gone" }),
    ];
    expect(selectSteps(table, accessFor("member"), ABSENT)).toEqual([]);
    expect(selectSteps([], accessFor("member"), PRESENT)).toEqual([]);
    expect(selectSteps(null as unknown as FakeStep[], accessFor("member"), PRESENT)).toEqual([]);
  });

  it("degrades a missing or garbage access object to the narrowest identity", () => {
    // Not to []: this runs inside a render path, and a tour that vanishes
    // because a prop arrived one commit late is a worse failure than a tour
    // that is briefly generic. The degrade is to member with NO confirmed
    // entitlements - narrow on identity, narrow on entitlement, never wider.
    const table = [
      fake("first", ALL),
      fake("member-only", ["member"]),
      fake("admin-only", ["tenant-admin"]),
      fake("gated", ALL, { requires: (a: TourAccess) => a.byokAllowed }),
      fake("help", ALL),
    ];
    for (const junk of [null, undefined, {}, 42, "member"]) {
      expect(ids(selectSteps(table, junk as unknown as TourAccess, PRESENT)), `access=${JSON.stringify(junk)}`).toEqual(
        ["first", "member-only", "help"]
      );
    }
  });

  it("selects for a member with no tenant-settings yet without crashing", () => {
    // The real loading window: AppChromeProvider's fetch is in flight, so plan
    // and experienceMode are null. The tour must still be selectable.
    const access = resolveAccess({ isAdmin: false, isPlatformAdmin: false, tenantSettings: null });
    const got = selectSteps(TOUR_STEPS, access, PRESENT);
    expect(got.length).toBeGreaterThanOrEqual(MIN_TOUR_STEPS);
    expect(ids(got)[0]).toBe("start");
  });
});

describe("who walks what", () => {
  it("gives a tenant-admin the full walk, in catalogue order", () => {
    expect(walk("tenant-admin")).toEqual([
      "start",
      "build",
      "agents",
      "workflows",
      "knowledge",
      "connect-group",
      "channels",
      "tools",
      "edge",
      "playground",
      "monitor",
      "plans-group",
      "admin",
      "topbar",
      "edge-chip",
      "search",
      "security",
      "account",
      "help",
    ]);
  });

  it("gives a member their own work and never an admin-only screen", () => {
    const member = walk("member");
    expect(member).toEqual([
      "start",
      "build",
      "home",
      "agents",
      "workflows",
      "knowledge",
      "shared-with-me",
      "connect-group",
      "tools",
      "edge",
      "playground",
      "plans-group",
      "topbar",
      "edge-chip",
      "search",
      "security",
      "account",
      "help",
    ]);
    // Named explicitly rather than left to the array equality: these three are
    // the whole point of the round, and a future reorder must not be able to
    // make them pass by accident.
    expect(member, "a member was walked through an admin-only screen").not.toContain("channels");
    expect(member, "a member was walked through an admin-only screen").not.toContain("monitor");
    expect(member, "a member was not shown where their shared work lives").toContain("shared-with-me");
  });

  it("gives a tenant-admin no shared-with-me step", () => {
    // Not a reachability question - the row exists for everyone - but an
    // editorial one: an admin's first login is about building, not about what
    // somebody granted them.
    expect(walk("tenant-admin")).not.toContain("shared-with-me");
  });

  it("gives a platform-admin a short, different tour and no agent walkthrough", () => {
    const platform = walk("platform-admin");
    // Six: the two platform steps, the three universal chrome steps (topbar,
    // jump-to, account) and help. Still no tenant screen and still short -
    // the chrome stops are the same three cards every visitor gets.
    expect(platform).toEqual(["platform-console", "platform-workspace", "topbar", "search", "account", "help"]);
    expect(platform.length).toBeLessThanOrEqual(6);
    expect(platform, "a platform-admin was walked through building an agent").not.toContain("agents");
    expect(platform).not.toContain("start");
  });

  it("gives a platform-admin the same tour with NO tenant at all", () => {
    // They may legitimately have none (AuthProvider.tsx:620-623), and none
    // of their steps reads an entitlement, so the null settings case must be
    // identical rather than degraded.
    expect(walk("platform-admin", null)).toEqual([
      "platform-console",
      "platform-workspace",
      "topbar",
      "search",
      "account",
      "help",
    ]);
  });

  it("reduces both tenant walks under SIMPLE without dropping the spine", () => {
    const simple = { plan: "FREE", experienceMode: EXPERIENCE_MODE_SIMPLE };
    expect(walk("member", simple)).toEqual([
      "start",
      "build",
      "home",
      "agents",
      "workflows",
      "knowledge",
      "shared-with-me",
      "connect-group",
      "edge",
      "playground",
      "plans-group",
      "topbar",
      "edge-chip",
      "search",
      "security",
      "account",
      "help",
    ]);
    expect(walk("tenant-admin", simple)).toEqual([
      "start",
      "build",
      "agents",
      "workflows",
      "knowledge",
      "connect-group",
      "channels",
      "edge",
      "playground",
      "plans-group",
      "topbar",
      "edge-chip",
      "search",
      "security",
      "account",
      "help",
    ]);
  });

  it("walks a brand-new consumer through fifteen stops or fewer, covering what they must know", () => {
    // C1-C4's subject, named: self-signup grants tenant-admin
    // (TenantAssignableRoles), TenantProvisioningService creates the tenant
    // B2C/SIMPLE, and the complimentary grant makes the plan read as paid.
    // "Consumers abandon long tours" is the brief, so the LENGTH is part of
    // the contract - adding a sixteenth SIMPLE step has to be a decision,
    // not drift. The bound moved nine -> fifteen in the header-controls pass
    // (founder request: the tour must name the rail groups and the topbar,
    // not just the rows on the launch path) - the six additions are all
    // orientation, not new surfaces.
    const consumer = walk("tenant-admin", {
      plan: "UNLIMITED",
      accountType: "B2C",
      experienceMode: EXPERIENCE_MODE_SIMPLE,
    });
    // The launch-story walk plus the console's own furniture: agent ->
    // workflow -> app/channel -> test, then Inbox & people, Plans & billing
    // and the three chrome stops.
    expect(consumer).toEqual([
      "start",
      "build",
      "agents",
      "workflows",
      "knowledge",
      "connect-group",
      "channels",
      "edge",
      "playground",
      "plans-group",
      "topbar",
      "edge-chip",
      "search",
      "security",
      "account",
      "help",
    ]);
    // 16, not 15: the two-factor step (2026-10-09, direct instruction to tell
    // consumers about 2FA from the top header) is one deliberate extra stop.
    expect(consumer.length).toBeLessThanOrEqual(16);
    // The ADVANCED surface SIMPLE exists to hide stays hidden.
    expect(consumer).not.toContain("tools");
    expect(consumer).not.toContain("monitor");
    expect(consumer).not.toContain("admin");
  });

  it("drops the Edge AI step when the build does not carry edge at all", () => {
    // With NEXT_PUBLIC_EDGE_AGENTS off, /edge renders "isn't enabled in this
    // build yet". Walking someone to that sentence is the tour pitching a
    // feature the deployment does not have - rule 2's whole reason to exist.
    mock.edgeEnabled = false;
    expect(walk("tenant-admin")).not.toContain("edge");
    expect(walk("member")).not.toContain("edge");
    // The topbar chip goes with it - EdgeSetupChip renders nothing in an
    // edge-less build, so its step has the same gate.
    expect(walk("tenant-admin")).not.toContain("edge-chip");
    // ...and nothing else goes with them.
    expect(walk("tenant-admin")).toContain("playground");
  });

  it("drops a nav row that is not on screen, for every audience", () => {
    // The rail row a build removes, or a route that does not render it: the
    // step goes, and nothing else does.
    const noKnowledge = (t: string | null) => t !== "nav-knowledge";
    const got = ids(selectSteps(TOUR_STEPS, accessFor("member"), noKnowledge));
    expect(got).not.toContain("knowledge");
    expect(got).toContain("agents");
    expect(got).toContain("help");
  });
});

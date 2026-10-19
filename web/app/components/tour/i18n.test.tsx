// The tour i18n keys and the variant key resolution chain.
// Split from tour.test.tsx; shared mocks and fixtures live in ./tourTestSupport.

import {
  CHROME_KEYS,
  REMOVED_KEYS,
  SUFFIXES,
  TOUR_MESSAGES,
  firstExistingKey,
  stepVariantsFor,
} from "./tourTestSupport";
import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync } from "node:fs";
import { audienceVariantToken, planVariantToken, variantKeys } from "./TourOverlay";
import { type TourAudience } from "./audience";
import { TOUR_STEPS } from "../TourProvider";

describe("tour i18n keys", () => {
  const tour = TOUR_MESSAGES;

  function assertString(key: string) {
    const value = tour?.[key];
    expect(typeof value, `tour.${key} must exist as a string`).toBe("string");
    expect(String(value).trim().length, `tour.${key} must not be empty`).toBeGreaterThan(0);
  }

  it("resolves a Title, Body, Point1 and Point2 for every step AND every audience it is tagged for", () => {
    // Round 3's version of the suite's oldest and most valuable test. The copy
    // is now VARIED - the overlay asks for `startFreeTitle` / `startPaidTitle`
    // / `startPlatformTitle` through variantKeys() and falls back down the
    // chain - so "does tour.startTitle exist" is no longer the question. The
    // question is whether the chain the overlay actually walks ENDS on a real
    // string, for every audience that step is tagged for and on both sides of
    // the paid/free boundary. Every entry in that chain is a KEY, so a stem
    // nobody wrote any keys for cannot be rescued: it renders the literal
    // "tour.<stem>Title" on screen, in 30 locales at once.
    expect(tour, "en.json has no `tour` namespace").toBeTruthy();

    // null covers the real loading window and a tenant-less platform-admin;
    // "ENTERPRISE" covers a tier this build has never heard of.
    const plans = [null, "FREE", "PREMIUM", "UNLIMITED", "ENTERPRISE"];
    for (const step of TOUR_STEPS) {
      // Inline steps render their own English when the key is missing; they
      // have their own all-or-nothing test below.
      if (step.inline) continue;
      for (const audience of step.audiences) {
        for (const plan of plans) {
          for (const suffix of SUFFIXES) {
            const chain = variantKeys(step.titleKey, suffix, stepVariantsFor(audience, plan));
            expect(
              firstExistingKey(chain),
              `step "${step.id}" renders a raw dotted path for ${audience}/${plan ?? "no plan"}: ` +
                `none of ${chain.join(", ")} exists in en.json`
            ).not.toBeNull();
          }
        }
      }
    }
  });

  it("keeps its in-code English mirror byte-identical to the keys that render", () => {
    // TourStep.title/body/points are a MIRROR of the four en.json strings and
    // have no render path at all - they exist so a reviewer can read the
    // tour's wording next to its structure instead of paging through a
    // 6000-line en.json. They drifted (2026-09): in-code step 0 read "Let's
    // create your first agent - free, no API key needed." while the key
    // rendered "Welcome to VegaDuta", and seven of the other eight disagreed
    // too, so the block a reviewer trusts was actively lying about what ships.
    // Nothing caught it because the old assertion only checked non-emptiness.
    //
    // Round 3: the mirror is compared against the key the overlay resolves for
    // this step's FIRST tagged audience with an unresolved plan - i.e. the
    // default variant, the one wording every visitor can land on. A varied
    // step therefore mirrors its default variant, and a step with no keys at
    // all fails here too, loudly, rather than mirroring `undefined`.
    for (const step of TOUR_STEPS) {
      if (step.inline) continue;
      const variants = stepVariantsFor(step.audiences[0], null);
      const resolved: Record<string, string | null> = {};
      for (const suffix of SUFFIXES) {
        resolved[suffix] = firstExistingKey(variantKeys(step.titleKey, suffix, variants));
      }
      const mirror: Record<string, string> = {
        Title: step.title,
        Body: step.body,
        Point1: step.points[0],
        Point2: step.points[1],
      };
      for (const suffix of SUFFIXES) {
        const key = resolved[suffix];
        expect(key, `step ${step.id}: no en.json key backs ${step.titleKey}${suffix}`).not.toBeNull();
        expect(mirror[suffix], `${step.id}.${suffix.toLowerCase()} !== tour.${key} (the in-code mirror is stale)`).toBe(
          String(tour?.[key as string])
        );
      }
    }
  });

  it("ships an INLINE step's keys all together or not at all, and never lets them drift", () => {
    // Round 4. An inline step (TourStep.inline) has no en.json keys yet and
    // renders its in-code English. The two states that are fine are "no keys"
    // and "all four keys, byte-identical to the in-code strings". The state
    // that is NOT fine is a partial set: the overlay resolves each string on
    // its own, so two translated strings and two English ones would land on
    // one card, in every locale but English.
    const inline = TOUR_STEPS.filter((s) => s.inline);
    // Sorted: the contract is WHICH steps ship unkeyed, not where they sit in
    // the catalogue (admin moved to the end in the launch-story rebuild).
    expect(inline.map((s) => s.id).sort(), "admin and edge are the inline steps today").toEqual(["admin", "edge"]);
    for (const step of inline) {
      expect(step.title.trim().length).toBeGreaterThan(0);
      expect(step.body.trim().length).toBeGreaterThan(0);
      const present = SUFFIXES.filter((suffix) => typeof tour?.[`${step.titleKey}${suffix}`] === "string");
      expect(
        [0, SUFFIXES.length],
        `step "${step.id}" has a PARTIAL key set in en.json: ${present.join(", ")}`
      ).toContain(present.length);
      if (present.length === SUFFIXES.length) {
        expect(step.title).toBe(String(tour?.[`${step.titleKey}Title`]));
        expect(step.body).toBe(String(tour?.[`${step.titleKey}Body`]));
        expect(step.points[0]).toBe(String(tour?.[`${step.titleKey}Point1`]));
        expect(step.points[1]).toBe(String(tour?.[`${step.titleKey}Point2`]));
      }
      // The key-collision fence, for the new stem: stem + a variant token must
      // not be some OTHER step's key set.
      for (const token of ["Platform", "Admin", "Member", "Free", "Paid"]) {
        expect(
          tour?.[`${step.titleKey}${token}Title`],
          `tour.${step.titleKey}${token}Title exists and would hijack the "${step.id}" step`
        ).toBeUndefined();
      }
    }
  });

  it("never lets a step that HAS keys hide behind the inline fallback", () => {
    // The fallback exists for keys that do not exist yet. Flagging a keyed
    // step `inline` would turn this suite's oldest guard - a missing key is
    // loud - into a silent English string for that step, in every locale.
    for (const step of TOUR_STEPS) {
      if (step.inline) continue;
      expect(step.inline, `step ${step.id}`).toBeFalsy();
    }
  });

  it("has every chrome key the overlay's own markup asks for", () => {
    for (const key of CHROME_KEYS) assertString(key);
  });

  it("has dropped the keys the rebuilds retired", () => {
    for (const key of REMOVED_KEYS) {
      expect(tour?.[key], `tour.${key} is retired and must not be back`).toBeUndefined();
    }
  });

  it("resolves the welcome, completion and duration chains for every audience", () => {
    // These three have no un-varied key left to land on, so each one passes a
    // DEFAULT VARIANT as the tail of its chain. That tail is the only thing
    // standing between a visitor and a dotted path, and nothing else asserts
    // it exists.
    for (const audience of ["member", "tenant-admin", "platform-admin"] as TourAudience[]) {
      const token = audienceVariantToken(audience);
      for (const suffix of ["Title", "Body"]) {
        expect(
          firstExistingKey(variantKeys("welcome", suffix, [token])),
          `welcome${suffix} unresolved for ${audience}`
        ).not.toBeNull();
        expect(
          firstExistingKey(variantKeys("completed", suffix, [token, "Admin"])),
          `completed${suffix} unresolved for ${audience}`
        ).not.toBeNull();
      }
      for (const suffix of ["Cta", "Secondary"]) {
        expect(
          firstExistingKey(variantKeys("completed", suffix, [token, "Admin"])),
          `completed${suffix} unresolved for ${audience}`
        ).not.toBeNull();
      }
      expect(firstExistingKey(variantKeys("replayHint", "", [token]))).not.toBeNull();
    }
    // The length promise: short walk and long walk both have a string.
    expect(firstExistingKey(variantKeys("duration", "", ["Short"]))).toBe("durationShort");
    expect(firstExistingKey(variantKeys("duration", "", [null]))).toBe("duration");
  });

  it("still states the price and the prerequisite on step 0", () => {
    // THE REGRESSION THIS SUITE EXISTS FOR (D1, 2026-09). v1's tour opened
    // with "Let's create your first agent - free, no API key needed." and its
    // second screen added "A hosted model is already included." The 9-step
    // rebuild wrote 54 new keys and kept the word "free" exactly once, about
    // channels; "no API key", "hosted model" and "included" appeared nowhere.
    // A new visitor's first two questions are "what does this cost" and "must
    // I plug in an OpenAI key" - the 3-step tour answered both in its opening
    // sentence and the 9-step tour answered neither.
    //
    // Round 3 split step 0 per plan, which is the right fix (a tenant inside
    // the complimentary UNLIMITED grant and the same tenant after it lapses
    // need different sentences) and also a new way to lose the facts: they
    // could survive in `startPaid*` and quietly vanish from the variant a
    // FREE workspace actually reads. So the guard now runs over the variant
    // the overlay resolves for an unknown or free plan - which is the only
    // variant every visitor can be shown.
    //
    // Matched as CONCEPTS, deliberately: an exact-sentence assertion would
    // fire on every legitimate reword and get deleted within a month, which is
    // how the facts went missing in the first place. Reword freely; do not
    // delete. Each claim is verified elsewhere in this same en.json
    // (signup.subtitle, agentWorkbench.platformLlmFreeTierNote,
    // signup.completeSuccessBox), so none of it is marketing this test
    // invented.
    const startStep = TOUR_STEPS.find((s) => s.id === "start");
    expect(startStep, "the catalogue has no step 0").toBeTruthy();

    const variants = stepVariantsFor("member", null);
    const keys = SUFFIXES.map((suffix) => firstExistingKey(variantKeys(startStep!.titleKey, suffix, variants)));
    for (const key of keys) expect(key, "step 0's copy does not resolve at all").not.toBeNull();

    const startCopy = keys
      .map((k) => String(tour?.[k as string] ?? ""))
      .join(" - ")
      .toLowerCase();

    expect(startCopy, "step 0 no longer says starting is free / costs nothing up front").toMatch(
      /\bfree\b|no cost|nothing to pay|no card|no credit card|no api key or card/
    );

    expect(startCopy, "step 0 no longer says a model is provided (hosted / included / built in)").toMatch(
      /hosted|included|provided|built[- ]in|comes with/
    );

    expect(startCopy, "step 0 no longer says an API key is not required").toMatch(
      /no api key|without an api key|no key (?:needed|required)|api key (?:not |isn't |is not )/
    );
  });

  it("ships every step-0 plan variant complete, or none of it", () => {
    // Half a variant is worse than no variant: the chain would fall through
    // mid-card and a PREMIUM tenant would read a paid title over free-tier
    // bullets. All four suffixes, or the stem should not exist.
    for (const stem of ["startFree", "startPaid", "startPlatform"]) {
      for (const suffix of SUFFIXES) assertString(`${stem}${suffix}`);
    }
  });

  it("tells the launch narrative end to end: agent first, then workflow, then app/channel", () => {
    // The resolved TENANT narrative (2026-10 rebuild): what a tenant visitor
    // actually reads across the welcome hero and the launch-path steps. This
    // is a concept guard, not a prose pin - product may reword freely, but the
    // story may not lose a beat: the agent comes first and powers what
    // follows, starting is free with no card/key, a workflow orchestrates it,
    // and an app or channel puts it in people's hands.
    const resolve = (stem: string) =>
      SUFFIXES.map((suffix) => firstExistingKey(variantKeys(stem, suffix, stepVariantsFor("tenant-admin", null))))
        .map((key) => String(tour?.[key as string] ?? ""))
        .join(" ");
    const narrative = [
      String(tour?.welcomeTitle ?? ""),
      String(tour?.welcomeBody ?? ""),
      resolve("start"),
      resolve("build"),
      resolve("agents"),
      resolve("workflows"),
      resolve("channels"),
    ]
      .join(" ")
      .toLowerCase();

    expect(narrative, "the agent no longer leads the story").toMatch(
      /start with (a custom|the) agent|the agent comes first|build the agent/
    );
    expect(narrative, "the agent no longer powers the app/channel outcome").toMatch(
      /power (ai )?apps|power an app|powers apps|answers on each channel/
    );
    expect(narrative, "the tour no longer says starting is free / no card").toMatch(
      /\bfree\b|no cost|nothing to pay|no (?:api key or |credit )?card/
    );
    expect(narrative, "the tour no longer covers tuning a custom agent").toMatch(/custom agent|tune|instructions/);
    expect(narrative, "the tour no longer covers workflows").toMatch(/workflow/);
    expect(narrative, "the tour no longer covers channels/customer placement").toMatch(/channel|customer|where people/);
  });

  it("promises no countable number of stops, since the count is now per-visitor", () => {
    // "Eight short stops" was true for exactly one audience in one experience
    // mode. A member in SIMPLE walks six, a platform-admin three. A promise the
    // visitor can count has to survive selection, so step 0 no longer names a
    // number at all.
    for (const stem of ["startFree", "startPaid", "startPlatform"]) {
      for (const suffix of SUFFIXES) {
        const value = String(tour?.[`${stem}${suffix}`] ?? "").toLowerCase();
        expect(
          value,
          `${stem}${suffix} promises a fixed number of stops, which the audience split made false`
        ).not.toMatch(
          /\b(?:one|two|three|four|five|six|seven|eight|nine|ten|\d+)\s+(?:short\s+)?(?:steps?|stops?|screens?)\b/
        );
      }
    }
  });

  it("keeps the placeholders the overlay interpolates", () => {
    // interpolate() leaves an unknown {placeholder} in place verbatim, so
    // dropping one ships a literal "{current}" to the user rather than a 3.
    expect(String(tour.stepCounter)).toContain("{current}");
    expect(String(tour.stepCounter)).toContain("{total}");
    expect(String(tour.progress)).toContain("{current}");
    expect(String(tour.progress)).toContain("{total}");
    expect(String(tour.jumpToStep)).toContain("{n}");
  });

  it('resolves every literal t("key") the overlay source contains', () => {
    // The generic version of the test above: this catches a key added to
    // TourOverlay.tsx later that nobody remembered to add to en.json. The
    // varied strings go through tv() instead and are covered by the chain
    // tests; this is the un-varied remainder.
    // Comments are stripped first so prose about t() cannot match.
    // The overlay's strings are spread over TourOverlay.tsx and overlay/* since the split.
    const files = ["TourOverlay.tsx", ...readdirSync("app/components/tour/overlay").map((f) => `overlay/${f}`)];
    const source = files
      .map((f) => readFileSync(`app/components/tour/${f}`, "utf8"))
      .join("\n")
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .replace(/^\s*\/\/.*$/gm, "");
    const keys = new Set<string>();
    for (const m of source.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
    expect(keys.size, 'found no t("...") calls - did the regex go stale?').toBeGreaterThan(5);
    for (const key of keys) assertString(key);
  });
});

describe("variant key resolution", () => {
  it("maps enums to the tokens en.json actually uses, and unknowns to nothing", () => {
    expect(audienceVariantToken("platform-admin")).toBe("Platform");
    expect(audienceVariantToken("tenant-admin")).toBe("Admin");
    expect(audienceVariantToken("member")).toBe("Member");
    expect(audienceVariantToken("archivist")).toBeNull();
    expect(audienceVariantToken(null)).toBeNull();

    // PREMIUM and UNLIMITED share one variant: the copy splits on "is this
    // workspace paying", which is the only distinction the wording cares
    // about. A tenant inside the complimentary grant is UNLIMITED today and
    // FREE the day CompGrantExpiryReverter runs, and each side reads the line
    // that is true of it.
    expect(planVariantToken("FREE")).toBe("Free");
    expect(planVariantToken("PREMIUM")).toBe("Paid");
    expect(planVariantToken("UNLIMITED")).toBe("Paid");
    // An unrecognised plan contributes NO key, so a future tier degrades to
    // generic copy instead of asking for a key nobody wrote.
    expect(planVariantToken("ENTERPRISE")).toBeNull();
    expect(planVariantToken(null)).toBeNull();
  });

  it("tries both spellings per variant and always ends on the un-varied key", () => {
    expect(variantKeys("start", "Title", ["Platform", "Free"])).toEqual([
      "startPlatformTitle",
      "startTitlePlatform",
      "startFreeTitle",
      "startTitleFree",
      "startTitle",
    ]);
    // Nulls contribute nothing, and the un-varied key is never duplicated.
    expect(variantKeys("welcome", "Body", [null, undefined])).toEqual(["welcomeBody"]);
  });
});

import { getPublicEnv } from "./env";
// "Free for everyone" build mode (2026-09-22, open-source posture): when
// NEXT_PUBLIC_FREE_MODE is exactly "1" at build time, the public site stops
// selling - pricing CTAs, the tier grid and the comparison table are replaced
// by a single "everything is free" surface plus a Buy Me a Coffee link.
//
// The backend half lives in core (FreeModeService / platform.free-mode.enabled
// in control_plane.platform_setting, seeded ON by V237): it makes every tenant
// resolve as UNLIMITED for feature gates while keeping billing code and stored
// plans intact. This flag only governs what the SITE advertises; the two are
// independent so an image can be rebuilt without a backend change.
//
// NEXT_PUBLIC_* values are inlined into the client bundle at build time
// (RULE 0: build with the deploy args, never a bare `next build` for prod).
// COFFEE_URL deliberately has NO baked default - inventing a donation URL
// would route money to a stranger's page; the button renders only when a
// real one is passed (NEXT_PUBLIC_COFFEE_URL).

export const FREE_MODE = getPublicEnv("NEXT_PUBLIC_FREE_MODE") === "1";

export const COFFEE_URL = (getPublicEnv("NEXT_PUBLIC_COFFEE_URL") || "").trim();

/** True when a coffee link exists to point at - free mode without a URL still works, it just doesn't beg. */
export const HAS_COFFEE_LINK = COFFEE_URL.length > 0;

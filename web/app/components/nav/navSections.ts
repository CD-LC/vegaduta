// Primary navigation data, extracted from AppShell.tsx (behaviour-free) so the rail,
// the hub flyout, the mobile drawer and the command palette share one blueprint and
// AppShell stays under the file-size ratchet. Labels are `nav` i18n keys.
//
// Groups follow the journey the product tour narrates: BUILD (what you author),
// CONNECT (how agents reach the world), TEST & MONITOR (verify and observe), then
// PLANS, HELP and ADMINISTRATION. /devices is deliberately absent: it is a redirect
// to /admin/channels?channel=mobile-sdk, not a screen.
//
// Each section carries a `cat` hue (docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md, section 2)
// as a wayfinding landmark only; every row also has its own icon, so colour is never
// the only cue.
//
// Every item has the same keys (badge/badgeVariant/exact always present, even when
// null) so TypeScript infers one uniform type. `adminOnly` on a section gates the whole
// group, `adminHidden` drops an item for admins (HOME), `perm` requires one granular
// scope (the PERM_* the backend enforces), and a null section `labelKey` renders the
// standalone HOME row with no header. Filtering happens at render time in AppShell.

import type { NavSection } from "./navTypes";
import { AUTHOR_NAV_SECTIONS } from "./navSectionsAuthor";
import { OPERATE_NAV_SECTIONS } from "./navSectionsOperate";

export type { NavItem, NavSection } from "./navTypes";

export const NAV_SECTIONS: NavSection[] = [...AUTHOR_NAV_SECTIONS, ...OPERATE_NAV_SECTIONS];

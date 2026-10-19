// Shared vitest wiring for the product-tour suites: jsdom stubs, the per-test
// reset, and every vi.mock the overlay and provider need. Imported FIRST by
// ./tourTestSupport so these mocks are registered before any tour module loads.
import { afterEach, beforeEach, vi } from "vitest";
import type { useTour } from "../TourProvider";
import { DEFAULT_SETTINGS } from "./tourContractData";

/* ------------------------------------------------------------------ */
/* jsdom gaps the overlay legitimately depends on                      */
/* ------------------------------------------------------------------ */
// Stubbed here rather than defended against inside the components: all three
// are real browser APIs the overlay is right to use, and weakening the
// component to suit the test runner would be the wrong trade.

class TestResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

beforeEach(() => {
  vi.stubGlobal("ResizeObserver", TestResizeObserver);
  vi.stubGlobal(
    "matchMedia",
    vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
  );
  // jsdom does not implement scrollIntoView at all; useTourTarget calls it
  // before measuring and try/catches, but stubbing keeps the catch unexercised
  // so a genuine throw from elsewhere would still surface.
  Element.prototype.scrollIntoView = vi.fn();
  window.localStorage.clear();
  // vd_tour_ended lives here, and it is what suppresses the first-run modals
  // that follow the tour - a leak between tests would silently disable the
  // D13 control case rather than fail it.
  window.sessionStorage.clear();
  setTour(null);
  mock.realCopy = false;
  mock.tenantSettings = { ...DEFAULT_SETTINGS };
  mock.edgeEnabled = true;
  // Default: the availability endpoint FAILS. That is the honest default for a
  // suite about fail-proofing - every test that does not care about the hosted
  // claim runs with the request rejected, so an unhandled rejection anywhere
  // in that path would surface as a failing run rather than hide behind a
  // happy mock.
  mock.availability = () => Promise.reject(new Error("availability unavailable"));
  push.mockClear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/* ------------------------------------------------------------------ */
/* Mocks                                                               */
/* ------------------------------------------------------------------ */

// vi.hoisted, because vi.mock factories are lifted above every `const` in the
// file and would otherwise read these in their temporal dead zone.
const { push, mock } = vi.hoisted(() => ({
  push: vi.fn(),
  mock: {
    tour: null as Record<string, unknown> | null,
    tenantSettings: null as Record<string, unknown> | null,
    // When false (the default) t() is the identity, so every assertion binds
    // to a KEY rather than to English copy and a reword never reds the suite.
    // The counter tests flip it on, because "Step 2 of 6" is a claim about the
    // NUMBERS and the numbers only exist once the placeholders are filled.
    realCopy: false,
    messages: null as Record<string, Record<string, unknown>> | null,
    // The build-time NEXT_PUBLIC_EDGE_AGENTS gate, per test.
    edgeEnabled: true,
    // GET /api/providers/availability, per test.
    availability: (() => Promise.reject(new Error("unset"))) as () => Promise<unknown>,
  },
}));

// The edge step is gated on a build-time env flag that is unset under vitest.
// Overriding the one function (the module is dependency-free by design) lets a
// test pick either side of the gate.
vi.mock("../../lib/edge/capabilities", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/edge/capabilities")>();
  return { ...actual, edgeEnabled: () => mock.edgeEnabled };
});

// Only the one wrapper the provider calls. Everything else on `api` is the real
// object, because AppChromeProvider's real module is loaded below too.
vi.mock("../../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, getProviderAvailability: () => mock.availability() },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push, replace: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  // The provider now mounts FieldSpotlight (?tour=field:<id>) and CourtMap.
  usePathname: () => "/",
  useSearchParams: () => new URLSearchParams(),
}));

// t() returns the key by default (see mock.realCopy above). The i18n key tests
// are what prove those keys resolve to real strings.
vi.mock("../../lib/i18n", () => ({
  useTranslations: (ns: string) => (key: string, values?: Record<string, string | number>) => {
    if (!mock.realCopy) return key;
    const table = mock.messages?.[ns];
    const raw = table && typeof table[key] === "string" ? String(table[key]) : `${ns}.${key}`;
    if (!values) return raw;
    return raw.replace(/\{(\w+)\}/g, (whole, name: string) => (name in values ? String(values[name]) : whole));
  },
}));

// The provider reads entitlements from AppChromeProvider's ONE shared
// GET /api/tenant/settings rather than fetching its own. Overriding the hook
// (and nothing else on the module) is how a test picks a plan or an experience
// mode without standing up the whole chrome provider and its network calls.
vi.mock("../AppChromeProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../AppChromeProvider")>();
  return {
    ...actual,
    useAppChrome: () => ({ ...actual.useAppChrome(), tenantSettings: mock.tenantSettings }),
  };
});

// useTour is overridable per-test: the render/keyboard tests drive the overlay
// from a fixed context so they assert on the overlay alone, while the
// integration tests leave the override null and run the REAL provider.
// The real hook is always called (never conditionally), so the rules of hooks
// hold either way.
vi.mock("../TourProvider", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../TourProvider")>();
  return {
    ...actual,
    useTour: () => {
      const real = actual.useTour();
      return mock.tour ? { ...real, ...mock.tour } : real;
    },
  };
});

type TourContextShape = ReturnType<typeof useTour>;

/** Pin the overlay to a fixed context for this test; null hands it back to the
 * real provider. */
function setTour(value: Partial<TourContextShape> | null) {
  mock.tour = value as Record<string, unknown> | null;
}

export { TestResizeObserver, mock, push, setTour };
export type { TourContextShape };

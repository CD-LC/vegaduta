import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import messages from "../../../messages/en.json";

// Real English strings (nested keys, {placeholder} interpolation) so the tests read what a person reads.
function lookup(ns: string, key: string): string | undefined {
  let cur: unknown = (messages as Record<string, unknown>)[ns];
  for (const part of key.split(".")) {
    if (cur && typeof cur === "object") cur = (cur as Record<string, unknown>)[part];
    else return undefined;
  }
  return typeof cur === "string" ? cur : undefined;
}
vi.mock("../../lib/i18n", () => ({
  useTranslations: (ns: string) => (key: string, values?: Record<string, string | number>) => {
    const raw = lookup(ns, key) ?? `${ns}.${key}`;
    return raw.replace(/\{(\w+)\}/g, (_, k) => String(values?.[k] ?? `{${k}}`));
  },
}));
vi.mock("next/link", () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a>,
}));
vi.mock("next/navigation", () => ({ usePathname: () => "/add-ons" }));

import { AddonApiError, type AddonsApi } from "../../lib/addonsApi";
import AddonsPanel from "./AddonsPanel";
import HostApplyPanel from "./HostApplyPanel";
import { useAddonText } from "./addonText";
import { addon, applyFor, catalogFixture, mockAddonsApi } from "./fixtures";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const card = (id: string) => screen.getByTestId(`addon-${id}`);
const turnOn = (name: string) => screen.getByRole("button", { name: new RegExp(`^Turn on ${name}`) });
const turnOff = (name: string) => screen.getByRole("button", { name: new RegExp(`^Turn off ${name}`) });

describe("AddonsPanel list", () => {
  it("groups add-ons by category with their honest facts", async () => {
    render(<AddonsPanel api={mockAddonsApi()} />);
    await screen.findByTestId("addon-workspace");

    expect(screen.getByTestId("addons-group-assistant")).toHaveTextContent("Assistant (2)");
    expect(screen.getByTestId("addons-group-search")).toHaveTextContent("Search (1)");
    expect(screen.getByTestId("addons-group-documents")).toHaveTextContent("Documents (1)");
    expect(screen.getByTestId("addons-group-models")).toHaveTextContent("Local models (1)");
    expect(screen.getByTestId("addons-group-tools")).toHaveTextContent("Tools (1)");

    const ws = within(card("workspace"));
    expect(ws.getByText("About 1.3 GB on disk")).toBeInTheDocument();
    expect(ws.getByText("Up to 3 GB extra memory")).toBeInTheDocument();
    expect(ws.getByText("No GPU needed")).toBeInTheDocument();
    expect(ws.getByText("Licence: Apache-2.0")).toBeInTheDocument();
    expect(ws.getByText("Free")).toBeInTheDocument();
    expect(ws.getByTestId("addon-status")).toHaveTextContent("On");
    expect(ws.getByText("Unlocks: Assistant terminal.")).toBeInTheDocument();

    // A model's memory figure is what the machine should have free, not extra use.
    expect(within(card("model-qwen35-2b")).getByText("Wants about 6 GB free memory")).toBeInTheDocument();
    expect(screen.getByTestId("addons-hardware")).toHaveTextContent(
      "This computer: 16 GB memory, 8 CPU cores, Apple silicon (memory is shared with the graphics chip)."
    );
    // The learn-more link points at the repository docs when no docs site is configured.
    const link = within(card("search")).getByRole("link", { name: /Learn more/ });
    expect(link).toHaveAttribute("href", "https://github.com/CD-LC/vegaduta/blob/main/docs/local-models.md#free-web-search");
  });

  it("warns about a machine that is too small without blocking the button", async () => {
    render(<AddonsPanel api={mockAddonsApi()} />);
    await screen.findByTestId("addon-browser");
    expect(within(card("browser")).getByTestId("addon-fit")).toHaveTextContent(
      "This computer has 16 GB of memory and this wants about 1 GB."
    );
    expect(turnOn("Assistant browser")).toBeEnabled();
  });

  it("shows planned add-ons as coming soon, with no way to turn them on", async () => {
    const api = mockAddonsApi();
    render(<AddonsPanel api={api} />);
    await screen.findByTestId("addon-tools");
    const tools = within(card("tools"));
    expect(tools.getByText("Coming soon")).toBeInTheDocument();
    expect(tools.queryByRole("button")).toBeNull();
    expect(tools.queryByTestId("addon-status")).toBeNull();
    expect(tools.queryByText(/on disk/)).toBeNull();
    expect(api.state.addons.find((a) => a.id === "tools")?.desired).toBe(false);
  });

  it("shows a loading state, then an error with a retry when the list cannot be read", async () => {
    const good = mockAddonsApi();
    const list = vi.fn<AddonsApi["list"]>().mockRejectedValueOnce(new Error("core is down")).mockImplementation(good.list);
    render(<AddonsPanel api={{ ...good, list }} />);
    expect(screen.getByTestId("addons-loading")).toBeInTheDocument();
    const alert = await screen.findByTestId("addons-load-error");
    expect(alert).toHaveTextContent("Could not load add-ons");
    expect(alert).toHaveTextContent("core is down");
    expect(screen.queryByTestId("addon-search")).toBeNull();

    fireEvent.click(within(alert).getByRole("button", { name: "Try again" }));
    await screen.findByTestId("addon-search");
    expect(screen.queryByTestId("addons-load-error")).toBeNull();
  });

  it("keeps the last state and says so when a background refresh fails", async () => {
    const good = mockAddonsApi();
    const list = vi.fn<AddonsApi["list"]>().mockImplementationOnce(good.list).mockRejectedValue(new Error("offline"));
    render(<AddonsPanel api={{ ...good, list }} />);
    await screen.findByTestId("addon-search");
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await screen.findByTestId("addons-stale");
    expect(screen.getByTestId("addon-search")).toBeInTheDocument();
  });
});

describe("AddonsPanel filters", () => {
  it("searches by what a person can read and clears back", async () => {
    render(<AddonsPanel api={mockAddonsApi()} />);
    await screen.findByTestId("addon-search");
    fireEvent.change(screen.getByRole("searchbox", { name: "Search add-ons" }), { target: { value: "chromium" } });
    expect(screen.queryByTestId("addon-search")).toBeNull();
    expect(screen.getByTestId("addon-browser")).toBeInTheDocument();

    fireEvent.change(screen.getByRole("searchbox", { name: "Search add-ons" }), { target: { value: "zzz" } });
    expect(screen.getByText("No add-ons match")).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole("button", { name: "Clear filters" })[0]);
    expect(screen.getByTestId("addon-search")).toBeInTheDocument();
  });

  it("filters by category chip and by state", async () => {
    render(<AddonsPanel api={mockAddonsApi()} />);
    await screen.findByTestId("addon-search");
    const models = screen.getByRole("button", { name: /^Local models/ });
    fireEvent.click(models);
    expect(models).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("addon-model-qwen35-2b")).toBeInTheDocument();
    expect(screen.queryByTestId("addon-search")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /^All/ }));

    fireEvent.click(screen.getByRole("combobox", { name: "Show" }));
    fireEvent.click(screen.getByRole("option", { name: "Coming soon" }));
    expect(screen.getByTestId("addon-tools")).toBeInTheDocument();
    expect(screen.queryByTestId("addon-workspace")).toBeNull();
  });
});

describe("AddonsPanel enable and disable", () => {
  it("confirms with the cost, records the wish, and shows the host step instead of claiming it is on", async () => {
    const api = mockAddonsApi();
    const enable = vi.spyOn(api, "enable");
    render(<AddonsPanel api={api} />);
    await screen.findByTestId("addon-search");

    fireEvent.click(turnOn("Web search"));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("Turn on Web search?");
    expect(dialog).toHaveTextContent("Uses about 377 MB of disk and up to 512 MB of extra memory.");
    expect(dialog).toHaveTextContent("VegaDūta Setup starts it on this computer.");
    expect(enable).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByRole("button", { name: "Turn on" }));
    await waitFor(() => expect(enable).toHaveBeenCalledWith("search"));

    expect(await screen.findByTestId("addon-notice")).toHaveTextContent(
      "Saved. Turning on: Web search. It still needs to be applied on this computer."
    );
    // Wanted is not running: the badge says waiting, and there is no "On".
    const search = within(card("search"));
    await waitFor(() => expect(search.getByTestId("addon-status")).toHaveTextContent("Waiting to be turned on"));
    expect(search.getByText(/Waiting for VegaDūta Setup to turn it on/)).toBeInTheDocument();
    expect(search.getByRole("button", { name: /^Turn off Web search/ })).toBeInTheDocument();

    const host = within(screen.getByTestId("addon-host-panel"));
    expect(host.getByText("To turn on: Web search")).toBeInTheDocument();
    expect(host.getByLabelText("Run in the install folder")).toHaveTextContent(
      "./scripts/vegaduta personal --profile workspace --search"
    );
    expect(host.queryByTestId("addon-host-no-bridge")).toBeNull();
    // Nothing polls the install yet, so the panel must not promise automatic application.
    expect(host.queryByText(/every 30 seconds/)).toBeNull();
    expect(host.getByText(/does not apply changes by itself yet/)).toBeInTheDocument();
    expect(host.getByText(/never switches an add-on off/)).toBeInTheDocument();
  });

  it("tells the truth when VegaDūta Setup is not linked: copy the command, nothing applies it for you", async () => {
    const api = mockAddonsApi({ bridge: false });
    render(<AddonsPanel api={api} />);
    await screen.findByTestId("addon-browser");
    fireEvent.click(turnOn("Assistant browser"));
    // Low memory is advice inside the confirmation, not a block.
    expect(await screen.findByTestId("confirm-fit")).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Turn on" }));

    const host = await screen.findByTestId("addon-host-panel");
    expect(within(host).getByTestId("addon-host-no-bridge")).toHaveTextContent("not linked to this install");
    expect(within(host).getByText(/nothing applies them automatically/)).toBeInTheDocument();
    expect(within(host).getByLabelText("Also add these lines to deploy/compose/.env")).toHaveTextContent(
      "CORE_IMAGE_VARIANT=-full CORE_MEM_LIMIT=2560m"
    );
    expect(within(card("browser")).getByText(/Run the command shown above/)).toBeInTheDocument();
  });

  it("copies the command", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    const api = mockAddonsApi();
    render(<AddonsPanel api={api} />);
    await screen.findByTestId("addon-search");
    fireEvent.click(turnOn("Web search"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Turn on" }));
    const host = await screen.findByTestId("addon-host-panel");
    fireEvent.click(within(host).getAllByRole("button", { name: /^Copy/ })[0]);
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("./scripts/vegaduta personal --profile workspace --search"));
    expect(await within(host).findByText("Copied")).toBeInTheDocument();
  });

  it("lists what else a turn-on brings with it", async () => {
    const addons = catalogFixture().map((a) => (a.id === "workspace" ? { ...a, desired: false, status: "off" as const } : a));
    render(<AddonsPanel api={mockAddonsApi({ addons })} />);
    await screen.findByTestId("addon-documents");
    expect(within(card("documents")).getByText("Needs: Build terminal and projects folder.")).toBeInTheDocument();
    expect(
      within(card("documents")).getByText("Turning this on also turns on: Build terminal and projects folder.")
    ).toBeInTheDocument();
    fireEvent.click(turnOn("Documents and transcription"));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("This also turns on: Build terminal and projects folder.");
    // Cost covers the add-on and its requirements: 4140 + 1350 MB.
    expect(dialog).toHaveTextContent("Uses about 5.4 GB of disk and up to 5 GB of extra memory.");
  });

  it("asks before turning off an add-on others need, then cascades", async () => {
    const api = mockAddonsApi();
    const disable = vi.spyOn(api, "disable");
    render(<AddonsPanel api={api} />);
    await screen.findByTestId("addon-documents");
    // Turn documents on, so workspace is needed by it.
    fireEvent.click(turnOn("Documents and transcription"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Turn on" }));
    await waitFor(() =>
      expect(within(card("workspace")).getByText(/Needed by Documents and transcription/)).toBeInTheDocument()
    );

    fireEvent.click(turnOff("Build terminal"));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("Turn off Build terminal and projects folder and what needs it?");
    expect(dialog).toHaveTextContent("Documents and transcription need Build terminal and projects folder.");
    expect(disable).not.toHaveBeenCalled();

    fireEvent.click(within(dialog).getByRole("button", { name: "Turn all off" }));
    await waitFor(() => expect(disable).toHaveBeenCalledWith("workspace", true));
    expect(await screen.findByTestId("addon-notice")).toHaveTextContent(
      "Saved. Turning off: Documents and transcription, Build terminal and projects folder."
    );
    // Both are still running until the host stops them.
    await waitFor(() =>
      expect(within(card("workspace")).getByTestId("addon-status")).toHaveTextContent("Waiting to be turned off")
    );
    const hostPanel = within(screen.getByTestId("addon-host-panel"));
    expect(hostPanel.getByText(/--no-search, --no-tools and --no-browser/)).toBeInTheDocument();
    expect(hostPanel.getByText(/never removes anything/)).toBeInTheDocument();
  });

  it("falls back to the cascade prompt when core refuses a disable the page did not foresee", async () => {
    const api = mockAddonsApi();
    const real = api.disable;
    const disable = vi
      .fn<AddonsApi["disable"]>()
      .mockRejectedValueOnce(new AddonApiError("Needed.", 409, "required_by", { dependents: ["documents"] }))
      .mockImplementation(real);
    render(<AddonsPanel api={{ ...api, disable }} />);
    await screen.findByTestId("addon-workspace");
    fireEvent.click(turnOff("Build terminal"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Turn off" }));
    await waitFor(() => expect(screen.getByRole("alertdialog")).toHaveTextContent("and what needs it?"));
    expect(screen.getByRole("alertdialog")).toHaveTextContent("Documents and transcription need Build terminal");
    fireEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Turn all off" }));
    await waitFor(() => expect(disable).toHaveBeenLastCalledWith("workspace", true));
    // The refusal was a question, never shown as an error.
    expect(screen.queryByTestId("addon-failure-alert")).toBeNull();
  });

  it("shows an inline, translated error and keeps the add-on off when the change is refused", async () => {
    const api = mockAddonsApi();
    const enable = vi.fn<AddonsApi["enable"]>().mockRejectedValue(new AddonApiError("slow down", 429, "rate_limited", {}));
    render(<AddonsPanel api={{ ...api, enable }} />);
    await screen.findByTestId("addon-search");
    fireEvent.click(turnOn("Web search"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Turn on" }));
    const alert = await screen.findByTestId("addon-failure-alert");
    expect(alert).toHaveTextContent("Could not change Web search");
    expect(alert).toHaveTextContent("Too many changes in a short time. Wait a minute and try again.");
    expect(within(card("search")).getByTestId("addon-status")).toHaveTextContent("Off");
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    fireEvent.click(within(alert).getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByTestId("addon-failure-alert")).toBeNull();
  });

  it("says a permission problem is a permission problem", async () => {
    const api = mockAddonsApi();
    const enable = vi.fn<AddonsApi["enable"]>().mockRejectedValue(new AddonApiError("Forbidden", 403, null, {}));
    render(<AddonsPanel api={{ ...api, enable }} />);
    await screen.findByTestId("addon-search");
    fireEvent.click(turnOn("Web search"));
    fireEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Turn on" }));
    expect(await screen.findByTestId("addon-failure-alert")).toHaveTextContent("You do not have permission to change add-ons here.");
  });
});

describe("AddonsPanel model packs", () => {
  it("shows download progress for a pull core is doing itself, and polls until it is ready", async () => {
    const api = mockAddonsApi();
    api.patch("model-qwen35-2b", { desired: true, status: "pulling", progress: 42, message: "pulling manifest" });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const list = vi.spyOn(api, "list");
    render(<AddonsPanel api={api} />);
    await screen.findByTestId("addon-model-qwen35-2b");

    const bar = within(card("model-qwen35-2b")).getByRole("progressbar", { name: "Download progress for Qwen 3.5 2B" });
    expect(bar).toHaveAttribute("aria-valuenow", "42");
    expect(within(card("model-qwen35-2b")).getByText(/Downloading: 42%\. pulling manifest/)).toBeInTheDocument();
    // A download cannot be cancelled from here.
    expect(turnOff("Qwen 3.5 2B")).toBeDisabled();

    const calls = list.mock.calls.length;
    api.patch("model-qwen35-2b", { status: "on", progress: null, message: null, installed: true });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2100);
    });
    expect(list.mock.calls.length).toBeGreaterThan(calls);
    await waitFor(() => expect(within(card("model-qwen35-2b")).getByTestId("addon-status")).toHaveTextContent("On"));
    expect(within(card("model-qwen35-2b")).queryByRole("progressbar")).toBeNull();
    expect(within(card("model-qwen35-2b")).getByText("Downloaded and ready.")).toBeInTheDocument();

    // Nothing in flight any more: polling stops.
    const settled = list.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(12000);
    });
    expect(list.mock.calls.length).toBe(settled);
  });

  it("points at the local-model setup when the model server is down", async () => {
    const addons = catalogFixture().map((a) =>
      a.kind === "model-pull" ? { ...a, canEnable: false, blockedReason: "ollama_unreachable" as const } : a
    );
    render(<AddonsPanel api={mockAddonsApi({ addons, ollama: false })} />);
    await screen.findByTestId("addon-model-qwen35-2b");
    expect(screen.getByTestId("addons-ollama-down")).toHaveTextContent("The local model server is not reachable");
    expect(turnOn("Qwen 3.5 2B")).toBeDisabled();
    const blocked = within(card("model-qwen35-2b")).getByTestId("addon-ollama-blocked");
    expect(within(blocked).getByRole("link", { name: "Set up local models" })).toHaveAttribute("href", "/providers/local-model");
  });

  it("says plainly that turning a model off keeps its files, and offers a retry after a failed download", async () => {
    const api = mockAddonsApi();
    api.patch("model-qwen35-2b", { desired: true, status: "failed", message: "disk is full" });
    const enable = vi.spyOn(api, "enable");
    render(<AddonsPanel api={api} />);
    await screen.findByTestId("addon-model-qwen35-2b");
    const m = within(card("model-qwen35-2b"));
    expect(m.getByTestId("addon-failure")).toHaveTextContent("It did not work: disk is full");

    fireEvent.click(m.getByRole("button", { name: /^Try again/ }));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("Downloads about 1.5 GB and wants about 6 GB of free memory to run.");
    expect(dialog).toHaveTextContent("VegaDūta downloads it now, so keep this computer online.");
    fireEvent.click(within(dialog).getByRole("button", { name: "Turn on" }));
    await waitFor(() => expect(enable).toHaveBeenCalledWith("model-qwen35-2b"));

    fireEvent.click(turnOff("Qwen 3.5 2B"));
    expect(await screen.findByRole("alertdialog")).toHaveTextContent("The downloaded files stay on disk and are not deleted.");
  });
});

describe("HostApplyPanel", () => {
  function Harness({ userAgent }: { userAgent: string }) {
    const text = useAddonText();
    const pending = [addon({ id: "search", name: "Web search", status: "enable_requested", desired: true })];
    const apply = {
      ...applyFor(pending),
      command: "./scripts/vegaduta personal --profile rag --search",
      profiles: ["rag"],
      flags: ["--search"],
    };
    return <HostApplyPanel apply={apply} bridgeConfigured pending={pending} text={text} userAgent={userAgent} />;
  }

  it("leads with the PowerShell form on Windows and says which option PowerShell lacks", () => {
    render(<Harness userAgent="Mozilla/5.0 (Windows NT 10.0; Win64; x64)" />);
    expect(screen.getByLabelText("Run in PowerShell, in the install folder")).toHaveTextContent(
      ".\\scripts\\vegaduta.ps1 personal -Profile rag"
    );
    expect(screen.getByTestId("addon-host-ps-note")).toHaveTextContent("PowerShell has no option for --search.");
    expect(screen.queryByLabelText("Run in the install folder")).toBeNull();
  });

  it("shows the POSIX command elsewhere", () => {
    render(<Harness userAgent="Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)" />);
    expect(screen.getByLabelText("Run in the install folder")).toHaveTextContent("./scripts/vegaduta personal --profile rag --search");
    expect(screen.queryByTestId("addon-host-ps-note")).toBeNull();
  });

  it("renders nothing when nothing waits for the host", () => {
    function Empty() {
      const text = useAddonText();
      return <HostApplyPanel apply={applyFor([])} bridgeConfigured pending={[]} text={text} userAgent="x" />;
    }
    const { container } = render(<Empty />);
    expect(container).toBeEmptyDOMElement();
  });
});

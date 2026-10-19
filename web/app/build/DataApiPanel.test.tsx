// DataApiPanel: the owner-facing controls for the public data API. The client is
// mocked at its module boundary; what is asserted is what the owner sees and what
// gets sent - especially that nothing is exposed until they switch it on, the
// service key is shown once, and risky operations carry a warning.

import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  dataConfig: vi.fn(),
  dataEnable: vi.fn(),
  dataRotateKey: vi.fn(),
  dataSetPolicies: vi.fn(),
  dataDisable: vi.fn(),
}));

vi.mock("./lib", () => ({ appBuilder: api }));

import DataApiPanel from "./DataApiPanel";

const ANON = "vdk_0123456789abcdef_" + "A".repeat(43);
const SVC = "vdk_fedcba9876543210_" + "B".repeat(43);

const tbl = (name: string, policy: Partial<Record<"select" | "insert" | "update" | "delete", boolean>> = {}) => ({
  name,
  columns: [{ name: "id", type: "integer", nullable: false }],
  policy: { select: false, insert: false, update: false, delete: false, ...policy },
});

const enabled = (over = {}) => ({
  enabled: true,
  available: true,
  base_url: "https://builder.example/data/v1",
  anon_key: ANON,
  has_service_key: true,
  allowed_origins: [] as string[],
  tables: [tbl("notes"), tbl("secrets")],
  ...over,
});

const onError = vi.fn();
const renderPanel = () => render(<DataApiPanel project={{ project_id: "demo" }} onError={onError} />);

beforeEach(() => {
  vi.clearAllMocks();
  Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
});

describe("DataApiPanel", () => {
  it("offers to enable and exposes nothing while it is off", async () => {
    api.dataConfig.mockResolvedValue({ enabled: false, available: true });
    renderPanel();
    expect(await screen.findByRole("button", { name: /Enable Data API/ })).toBeInTheDocument();
    expect(screen.queryByText(/Public key/)).not.toBeInTheDocument();
    expect(screen.getByText(/only works inside Azure/)).toBeInTheDocument();
  });

  it("enabling shows the keys, and the service key exactly once with a copy-now warning", async () => {
    api.dataConfig.mockResolvedValue({ enabled: false, available: true });
    api.dataEnable.mockResolvedValue(enabled({ service_key: SVC }));
    renderPanel();
    fireEvent.click(await screen.findByRole("button", { name: /Enable Data API/ }));

    expect(await screen.findByText(/copy it now, it won't be shown again/)).toBeInTheDocument();
    expect(screen.getByText("https://builder.example/data/v1")).toBeInTheDocument();
    // keys are masked on screen but copyable in full
    expect(screen.queryByText(ANON)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Copy Service key" }));
    await waitFor(() => expect(navigator.clipboard.writeText).toHaveBeenCalledWith(SVC));

    fireEvent.click(screen.getByRole("button", { name: /I've saved it/ }));
    expect(screen.queryByText(/copy it now/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Copy Service key" })).not.toBeInTheDocument();
  });

  it("an existing project never re-shows the service key", async () => {
    api.dataConfig.mockResolvedValue(enabled());
    renderPanel();
    expect(await screen.findByText("https://builder.example/data/v1")).toBeInTheDocument();
    expect(screen.queryByText(/copy it now/)).not.toBeInTheDocument();
  });

  it("every table starts closed; Save is disabled until something changes", async () => {
    api.dataConfig.mockResolvedValue(enabled());
    renderPanel();
    await screen.findByText("notes");
    for (const box of screen.getAllByRole("checkbox")) expect(box).not.toBeChecked();
    expect(screen.getByRole("button", { name: /Save access/ })).toBeDisabled();
  });

  it("toggling a policy marks it unsaved and saves the full policy map", async () => {
    api.dataConfig.mockResolvedValue(enabled());
    api.dataSetPolicies.mockResolvedValue(enabled({ tables: [tbl("notes", { select: true, insert: true }), tbl("secrets")] }));
    renderPanel();
    await screen.findByText("notes");
    fireEvent.click(screen.getByLabelText("Read rows in notes"));
    fireEvent.click(screen.getByLabelText("Create rows in notes"));
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /Save access/ }));
    await waitFor(() => expect(api.dataSetPolicies).toHaveBeenCalledTimes(1));
    expect(api.dataSetPolicies).toHaveBeenCalledWith("demo", {
      policies: { notes: { select: true, insert: true, update: false, delete: false } },
      allowed_origins: [] as string[],
    });
    await waitFor(() => expect(screen.queryByText("Unsaved changes")).not.toBeInTheDocument());
  });

  it("warns plainly when anyone could edit or delete rows", async () => {
    api.dataConfig.mockResolvedValue(enabled());
    renderPanel();
    await screen.findByText("notes");
    expect(screen.queryByText(/can delete rows/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Delete rows in notes"));
    expect(screen.getByText(/Anyone who opens your app can delete rows from this table/)).toBeInTheDocument();
  });

  it("refuses to save an invalid origin and tells the owner why", async () => {
    api.dataConfig.mockResolvedValue(enabled());
    renderPanel();
    await screen.findByText("notes");
    fireEvent.change(screen.getByLabelText(/Only allow these websites/), { target: { value: "javascript:alert(1)" } });
    fireEvent.click(screen.getByRole("button", { name: /Save access/ }));
    expect(onError).toHaveBeenCalledWith(expect.stringContaining("javascript:alert(1)"));
    expect(api.dataSetPolicies).not.toHaveBeenCalled();
  });

  it("sends normalised origins", async () => {
    api.dataConfig.mockResolvedValue(enabled());
    api.dataSetPolicies.mockResolvedValue(enabled({ allowed_origins: ["https://my.app"] }));
    renderPanel();
    await screen.findByText("notes");
    fireEvent.change(screen.getByLabelText(/Only allow these websites/), { target: { value: "https://My.App/" } });
    fireEvent.click(screen.getByRole("button", { name: /Save access/ }));
    await waitFor(() => expect(api.dataSetPolicies).toHaveBeenCalled());
    expect(api.dataSetPolicies.mock.calls[0][1].allowed_origins).toEqual(["https://my.app"]);
  });

  it("rotating the service key needs a confirmation and then shows the new key once", async () => {
    api.dataConfig.mockResolvedValue(enabled());
    api.dataRotateKey.mockResolvedValue(enabled({ service_key: SVC }));
    renderPanel();
    await screen.findByText("notes");
    fireEvent.click(screen.getByRole("button", { name: /Rotate service key/ }));
    expect(api.dataRotateKey).not.toHaveBeenCalled();
    expect(screen.getByText(/old service key stops working/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Rotate it" }));
    await waitFor(() => expect(api.dataRotateKey).toHaveBeenCalledWith("demo", "service"));
    expect(await screen.findByText(/copy it now/)).toBeInTheDocument();
  });

  it("turning it off needs a confirmation and returns to the off state", async () => {
    api.dataConfig.mockResolvedValue(enabled());
    api.dataDisable.mockResolvedValue({ enabled: false, keys_revoked: 2 });
    renderPanel();
    await screen.findByText("notes");
    fireEvent.click(screen.getByRole("button", { name: "Turn off" }));
    expect(api.dataDisable).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Turn off Data API" }));
    await waitFor(() => expect(api.dataDisable).toHaveBeenCalledWith("demo"));
    expect(await screen.findByRole("button", { name: /Enable Data API/ })).toBeInTheDocument();
  });

  it("says so when the operator switched the API off", async () => {
    api.dataConfig.mockResolvedValue({ enabled: false, available: false });
    renderPanel();
    expect(await screen.findByText(/switched off on this deployment/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Enable Data API/ })).not.toBeInTheDocument();
  });

  it("surfaces a load failure through onError", async () => {
    api.dataConfig.mockRejectedValue(new Error("provision this project's backend first"));
    renderPanel();
    await waitFor(() => expect(onError).toHaveBeenCalledWith("provision this project's backend first"));
  });
});

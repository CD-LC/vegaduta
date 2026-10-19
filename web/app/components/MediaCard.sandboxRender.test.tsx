// A run_code chart renders inline as an <img>; every other sandbox output is a file row; a url the
// model made up (not in the turn's tool log) is verified against the server and shown as never generated on 404.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

const downloadMock = vi.hoisted(() => vi.fn());
vi.mock("../lib/api", () => ({
  api: { downloadSandboxArtifact: downloadMock },
  triggerBrowserDownload: vi.fn(),
}));

import MediaCard from "./MediaCard";

const ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
const OTHER = "1a8fad5b-d9cb-469f-a165-70867728950f";
const URL_PATH = `/api/sandbox/artifacts/${ID}`;

describe("MediaCard sandbox artifacts", () => {
  beforeEach(() => {
    downloadMock.mockReset();
    (URL as any).createObjectURL = vi.fn(() => "blob:test");
    (URL as any).revokeObjectURL = vi.fn();
  });
  afterEach(() => cleanup());

  it("renders an image blob inline", async () => {
    downloadMock.mockResolvedValue({ blob: new Blob(["x"], { type: "image/png" }), filename: "chart.png" });
    const { container } = render(
      <MediaCard text={`Here:\n${URL_PATH}`} trace={[{ toolOutput: `{"files":[{"url":"${URL_PATH}"}]}` }]} />
    );
    await waitFor(() => expect(container.querySelector("img")).not.toBeNull());
    expect(container.querySelector("img")!.getAttribute("alt")).toBe("chart.png");
    expect(downloadMock).toHaveBeenCalledWith(ID);
  });

  it("does not render a non-image output as an <img>", async () => {
    downloadMock.mockResolvedValue({ blob: new Blob(["a,b"], { type: "text/csv" }), filename: "result.csv" });
    const { container } = render(<MediaCard text={URL_PATH} trace={[{ toolOutput: URL_PATH }]} />);
    await waitFor(() => expect(screen.getByText("Download")).toBeTruthy());
    expect(container.querySelector("img")).toBeNull();
  });

  it("verifies an artifact url no tool call produced against the server and shows the never-generated notice on 404", async () => {
    const notFound: any = new Error("not found");
    notFound.status = 404;
    downloadMock.mockRejectedValue(notFound);
    render(<MediaCard text={`/api/sandbox/artifacts/${OTHER}`} trace={[{ toolOutput: URL_PATH }]} />);
    expect(await screen.findByTestId("media-unverified")).toBeTruthy();
    expect(downloadMock).toHaveBeenCalledTimes(1);
    expect(screen.queryByText("Download")).toBeNull();
  });
});

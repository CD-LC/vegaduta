import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import InspectorErrorBoundary from "./InspectorErrorBoundary";

function Boom({ explode }: { explode: boolean }) {
  if (explode) throw new Error("inspector exploded");
  return <div>fine</div>;
}

afterEach(cleanup);

describe("InspectorErrorBoundary", () => {
  it("shows the failure inline with a retry instead of throwing to the page", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    render(
      <InspectorErrorBoundary resetKey="whatsapp" title="Could not display" retryLabel="Try again">
        <Boom explode />
      </InspectorErrorBoundary>,
    );
    expect(screen.getByTestId("channels-inspector-crash").textContent).toContain("Could not display");
    expect(screen.getByTestId("channels-inspector-crash").textContent).toContain("inspector exploded");
    expect(screen.getByRole("button", { name: "Try again" })).toBeTruthy();
  });

  it("clears the error when another channel is selected", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { rerender } = render(
      <InspectorErrorBoundary resetKey="whatsapp" title="t" retryLabel="r">
        <Boom explode />
      </InspectorErrorBoundary>,
    );
    expect(screen.queryByTestId("channels-inspector-crash")).not.toBeNull();
    rerender(
      <InspectorErrorBoundary resetKey="telegram" title="t" retryLabel="r">
        <Boom explode={false} />
      </InspectorErrorBoundary>,
    );
    expect(screen.queryByTestId("channels-inspector-crash")).toBeNull();
    expect(screen.getByText("fine")).toBeTruthy();
  });

  it("retry re-renders the children", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    let explode = true;
    function Flaky() {
      return <Boom explode={explode} />;
    }
    render(
      <InspectorErrorBoundary resetKey="x" title="t" retryLabel="Retry">
        <Flaky />
      </InspectorErrorBoundary>,
    );
    explode = false;
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(screen.getByText("fine")).toBeTruthy();
  });
});

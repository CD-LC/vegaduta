import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import PaginationControls from "./PaginationControls";

function setup(over: Record<string, any> = {}) {
  const props = {
    page: 2,
    totalPages: 3,
    totalItems: 25,
    pageSize: 10,
    onPageChange: vi.fn(),
    onPageSizeChange: vi.fn(),
    ...over,
  };
  render(<PaginationControls {...(props as any)} />);
  return props;
}

describe("PaginationControls", () => {
  it("renders nothing when there are no items", () => {
    const { container } = render(
      <PaginationControls page={1} totalPages={1} totalItems={0} pageSize={10} onPageChange={vi.fn()} onPageSizeChange={vi.fn()} />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the visible range and item label", () => {
    setup({ itemLabel: "users" });
    expect(screen.getByText(/11–20 of 25 users/)).toBeInTheDocument();
  });

  it("clamps the range end on the last page", () => {
    setup({ page: 3 });
    expect(screen.getByText(/21–25 of 25 records/)).toBeInTheDocument();
  });

  it("navigates prev/next", () => {
    const p = setup();
    fireEvent.click(screen.getByLabelText("Previous page"));
    expect(p.onPageChange).toHaveBeenCalledWith(1);
    fireEvent.click(screen.getByLabelText("Next page"));
    expect(p.onPageChange).toHaveBeenCalledWith(3);
  });

  it("disables prev on the first page", () => {
    setup({ page: 1 });
    expect(screen.getByLabelText("Previous page")).toBeDisabled();
    expect(screen.getByLabelText("Next page")).not.toBeDisabled();
  });

  it("disables next on the last page", () => {
    setup({ page: 3 });
    expect(screen.getByLabelText("Next page")).toBeDisabled();
  });

  it("hides prev/next when there is a single page", () => {
    setup({ page: 1, totalPages: 1, totalItems: 5 });
    expect(screen.queryByLabelText("Next page")).toBeNull();
  });

  it("reports page-size changes", () => {
    const p = setup();
    fireEvent.click(screen.getByRole("button", { name: "50" }));
    expect(p.onPageSizeChange).toHaveBeenCalledWith(50);
  });
});

describe("PaginationControls server mode", () => {
  it("reads the range and a more-available hint without a total", () => {
    setup({ totalItems: undefined, totalPages: undefined, hasMore: true, pageItemCount: 10, itemLabel: "users" });
    expect(screen.getByText(/11–20 users \(more available\)/)).toBeInTheDocument();
    expect(screen.getByLabelText("Next page")).not.toBeDisabled();
  });

  it("disables Next on the last page when no more rows exist", () => {
    setup({ totalItems: undefined, totalPages: undefined, hasMore: false, pageItemCount: 5 });
    expect(screen.getByLabelText("Next page")).toBeDisabled();
  });

  it("shows the capped notice once the cap is reached", () => {
    setup({ page: 1, totalPages: 1, totalItems: 200, capped: 200 });
    expect(screen.getByText(/Showing the first 200/)).toBeInTheDocument();
  });
});

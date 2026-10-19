import { describe, it, expect, vi } from "vitest";
import { act, fireEvent, render, renderHook, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { I18nProvider } from "../lib/i18n";
import { pageForSizeChange, usePagination, PAGE_SIZE_OPTIONS } from "../lib/usePagination";
import messages from "../../messages/en.json";
import PaginationControls from "./PaginationControls";
import LoadMoreBar from "./LoadMoreBar";

const wrap = (ui: ReactNode) => (
  <I18nProvider locale="en" messages={messages} enMessages={messages}>
    {ui}
  </I18nProvider>
);

function setup(over: Record<string, unknown> = {}) {
  const props = {
    page: 2,
    totalPages: 3,
    totalItems: 25,
    pageSize: 10,
    onPageChange: vi.fn(),
    onPageSizeChange: vi.fn(),
    ...over,
  };
  render(wrap(<PaginationControls {...props} />));
  return props;
}

describe("PaginationControls client mode", () => {
  it("renders nothing for 0 items", () => {
    const { container } = render(wrap(<PaginationControls page={1} totalPages={1} totalItems={0} pageSize={10} onPageChange={vi.fn()} onPageSizeChange={vi.fn()} />));
    expect(container).toBeEmptyDOMElement();
  });

  it("shows the range with the item label in a live status region", () => {
    setup({ itemLabel: "users" });
    const status = screen.getByRole("status");
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(status).toHaveTextContent("11–20 of 25 users");
  });

  it("clamps the end on a partial last page", () => {
    setup({ page: 3 });
    expect(screen.getByRole("status")).toHaveTextContent("21–25 of 25 records");
  });

  it("hides navigation on a single page but keeps the size picker", () => {
    setup({ page: 1, totalPages: 1, totalItems: 4 });
    expect(screen.queryByLabelText("Next page")).toBeNull();
    expect(screen.getByLabelText("Rows per page")).toBeInTheDocument();
  });

  it("navigates first/prev/next/last", () => {
    const p = setup();
    fireEvent.click(screen.getByLabelText("First page"));
    expect(p.onPageChange).toHaveBeenLastCalledWith(1);
    fireEvent.click(screen.getByLabelText("Previous page"));
    expect(p.onPageChange).toHaveBeenLastCalledWith(1);
    fireEvent.click(screen.getByLabelText("Next page"));
    expect(p.onPageChange).toHaveBeenLastCalledWith(3);
    fireEvent.click(screen.getByLabelText("Last page"));
    expect(p.onPageChange).toHaveBeenLastCalledWith(3);
  });

  it("disables back buttons on page 1 and forward buttons on the last page", () => {
    setup({ page: 1 });
    expect(screen.getByLabelText("First page")).toBeDisabled();
    expect(screen.getByLabelText("Previous page")).toBeDisabled();
    expect(screen.getByLabelText("Next page")).toBeEnabled();
  });

  it("disables forward buttons on the last page", () => {
    setup({ page: 3 });
    expect(screen.getByLabelText("Next page")).toBeDisabled();
    expect(screen.getByLabelText("Last page")).toBeDisabled();
  });

  it("offers 10/25/50/100 and reports a size change", () => {
    const p = setup();
    const select = screen.getByLabelText("Rows per page") as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(["10", "25", "50", "100"]);
    fireEvent.change(select, { target: { value: "50" } });
    expect(p.onPageSizeChange).toHaveBeenCalledWith(50);
  });

  it("keeps a non-standard current size selectable", () => {
    setup({ pageSize: 20, totalItems: 100, totalPages: 5 });
    expect((screen.getByLabelText("Rows per page") as HTMLSelectElement).value).toBe("20");
  });

  it("disables everything while loading", () => {
    setup({ loading: true });
    expect(screen.getByLabelText("Next page")).toBeDisabled();
    expect(screen.getByLabelText("Rows per page")).toBeDisabled();
  });

  it("jumps to a typed page, clamped to the range, when there are many pages", () => {
    const p = setup({ page: 1, totalPages: 20, totalItems: 200 });
    const input = screen.getByLabelText("Jump to page");
    fireEvent.change(input, { target: { value: "99" } });
    fireEvent.submit(input.closest("form") as HTMLFormElement);
    expect(p.onPageChange).toHaveBeenCalledWith(20);
  });

  it("does not show the jump box for few pages", () => {
    setup();
    expect(screen.queryByLabelText("Jump to page")).toBeNull();
  });
});

describe("PaginationControls server mode", () => {
  it("derives pages from total when totalPages is omitted", () => {
    const p = setup({ totalPages: undefined, totalItems: 95, page: 9, pageSize: 10 });
    expect(screen.getByRole("status")).toHaveTextContent("81–90 of 95");
    fireEvent.click(screen.getByLabelText("Last page"));
    expect(p.onPageChange).toHaveBeenCalledWith(10);
  });

  it("shows x-y with a more-available hint when the total is unknown", () => {
    const p = setup({ totalItems: undefined, totalPages: undefined, hasMore: true, page: 2, pageItemCount: 10 });
    expect(screen.getByRole("status")).toHaveTextContent("11–20 records (more available)");
    expect(screen.getByLabelText("Next page")).toBeEnabled();
    expect(screen.queryByLabelText("Last page")).toBeNull();
    fireEvent.click(screen.getByLabelText("Next page"));
    expect(p.onPageChange).toHaveBeenCalledWith(3);
  });

  it("uses the real row count for a partial final page with unknown total", () => {
    setup({ totalItems: undefined, totalPages: undefined, hasMore: false, page: 3, pageItemCount: 4 });
    expect(screen.getByRole("status")).toHaveTextContent("21–24 records");
    expect(screen.getByRole("status")).not.toHaveTextContent("more available");
    expect(screen.getByLabelText("Next page")).toBeDisabled();
  });

  it("renders nothing for an empty first page with unknown total", () => {
    const { container } = render(
      wrap(<PaginationControls page={1} pageSize={10} pageItemCount={0} onPageChange={vi.fn()} onPageSizeChange={vi.fn()} />)
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("shows no navigation for a single short page with unknown total", () => {
    setup({ totalItems: undefined, totalPages: undefined, hasMore: false, page: 1, pageItemCount: 3 });
    expect(screen.queryByLabelText("Next page")).toBeNull();
  });
});

describe("PaginationControls capped notice", () => {
  it("shows the truncation notice when the total reaches the cap", () => {
    setup({ capped: 200, totalItems: 200, totalPages: 20, page: 1 });
    expect(screen.getByRole("note")).toHaveTextContent("Showing the first 200. Narrow the filters to see more.");
  });

  it("hides it when the list is under the cap", () => {
    setup({ capped: 200, totalItems: 40, totalPages: 4, page: 1 });
    expect(screen.queryByRole("note")).toBeNull();
  });

  it("shows it when the total is unknown", () => {
    setup({ capped: 50, totalItems: undefined, totalPages: undefined, hasMore: false, page: 1, pageItemCount: 50 });
    expect(screen.getByRole("note")).toBeInTheDocument();
  });
});

describe("usePagination", () => {
  const rows = Array.from({ length: 95 }, (_, i) => i + 1);

  it("slices a partial last page and reports the standard sizes", () => {
    const { result } = renderHook(() => usePagination(rows));
    expect(PAGE_SIZE_OPTIONS).toEqual([10, 25, 50, 100]);
    act(() => result.current.setPage(10));
    expect(result.current.pageItems).toEqual([91, 92, 93, 94, 95]);
    expect(result.current.totalPages).toBe(10);
  });

  it("keeps the first visible item in view when the page size changes", () => {
    const { result } = renderHook(() => usePagination(rows));
    act(() => result.current.setPage(3)); // items 21-30
    act(() => result.current.setPageSize(25));
    expect(result.current.pageItems[0]).toBeLessThanOrEqual(21);
    expect(result.current.pageItems).toContain(21);
    expect(result.current.page).toBe(1);
    act(() => result.current.setPage(2)); // items 26-50
    act(() => result.current.setPageSize(10)); // first visible 26 -> page 3 (21-30)
    expect(result.current.pageItems).toContain(26);
  });

  it("clamps when the list shrinks and handles an empty list", () => {
    const { result, rerender } = renderHook(({ items }) => usePagination(items), { initialProps: { items: rows } });
    act(() => result.current.setPage(10));
    rerender({ items: rows.slice(0, 12) });
    expect(result.current.page).toBe(2);
    rerender({ items: [] });
    expect(result.current.page).toBe(1);
    expect(result.current.totalPages).toBe(1);
    expect(result.current.pageItems).toEqual([]);
  });

  it("pageForSizeChange math", () => {
    expect(pageForSizeChange(3, 10, 50)).toBe(1);
    expect(pageForSizeChange(3, 10, 5)).toBe(5);
    expect(pageForSizeChange(1, 10, 100)).toBe(1);
  });
});

describe("LoadMoreBar", () => {
  it("renders nothing when everything is loaded", () => {
    const { container } = render(wrap(<LoadMoreBar loaded={5} total={5} hasMore={false} loading={false} onLoadMore={vi.fn()} itemLabel="runs" />));
    expect(container).toBeEmptyDOMElement();
  });

  it("shows progress and loads more", () => {
    const onLoadMore = vi.fn();
    render(wrap(<LoadMoreBar loaded={50} total={120} hasMore loading={false} onLoadMore={onLoadMore} itemLabel="runs" />));
    expect(screen.getByRole("status")).toHaveTextContent("Showing the newest 50 of 120 runs");
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    expect(onLoadMore).toHaveBeenCalled();
  });

  it("disables the button while loading", () => {
    render(wrap(<LoadMoreBar loaded={50} total={null} hasMore loading onLoadMore={vi.fn()} itemLabel="runs" />));
    expect(screen.getByRole("button")).toBeDisabled();
  });
});

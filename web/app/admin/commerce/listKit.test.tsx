import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});

import { ErrorRetry, FilterPills, PagedList } from "./listKit";

describe("listKit", () => {
  it("PagedList renders one page at a time", () => {
    const items = Array.from({ length: 25 }, (_, i) => `item-${i}`);
    render(<PagedList items={items} itemLabel="wallets" renderItem={(x) => <div key={x}>{x}</div>} />);
    expect(screen.getByText("item-0")).toBeTruthy();
    expect(screen.queryByText("item-10")).toBeNull();
  });

  it("ErrorRetry calls onRetry", () => {
    const onRetry = vi.fn();
    render(<ErrorRetry message="boom" onRetry={onRetry} />);
    fireEvent.click(screen.getByText("Try again"));
    expect(onRetry).toHaveBeenCalled();
  });

  it("FilterPills reports the chosen status", () => {
    const onChange = vi.fn();
    render(<FilterPills options={["ACTIVE", "REVOKED"]} value="" onChange={onChange} />);
    fireEvent.click(screen.getByText("REVOKED"));
    expect(onChange).toHaveBeenCalledWith("REVOKED");
  });
});

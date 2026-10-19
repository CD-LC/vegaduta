import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import en from "../../messages/en.json";

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, string>) => {
    let v: any = en.featureUnavailable;
    for (const part of key.split(".")) v = v[part];
    return Object.entries(vars ?? {}).reduce((s, [k, x]) => s.replace(`{${k}}`, x), v as string);
  },
}));
import FeatureUnavailable from "./FeatureUnavailable";

describe("FeatureUnavailable", () => {
  it("names the feature, the reason, and how to get it", () => {
    render(<FeatureUnavailable feature="Single sign-on" reason="identity" />);
    expect(screen.getByText("Single sign-on is not available in the personal installation")).toBeTruthy();
    expect(screen.getByText(/Keycloak/)).toBeTruthy();
    expect(screen.getByText(/Install the Lite or Full edition/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Installation guide" }).getAttribute("href")).toMatch(/installation/);
  });

  it("uses deployment wording when a service is missing", () => {
    render(<FeatureUnavailable feature="Code Intel" reason="service" edition="deployment" />);
    expect(screen.getByText("Code Intel is not available in this deployment")).toBeTruthy();
  });
});

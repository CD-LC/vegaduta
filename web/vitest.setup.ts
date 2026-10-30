import "@testing-library/jest-dom/vitest";
import { vi } from "vitest";

// Components read copy through useTranslations(). Unit tests that render them
// without an <I18nProvider> get the real English catalog instead of a throw,
// so assertions keep matching the visible English text. A test that mocks
// "lib/i18n" itself, or wraps in I18nProvider, is unaffected.
vi.mock("./app/lib/i18n", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./app/lib/i18n")>();
  const { useMemo } = await import("react");
  const en = (await import("./messages/en.json")).default as Record<string, unknown>;
  const lookup = (path: string): string | undefined => {
    let cur: unknown = en;
    for (const part of path.split(".")) {
      if (cur && typeof cur === "object") cur = (cur as Record<string, unknown>)[part];
      else return undefined;
    }
    return typeof cur === "string" ? cur : undefined;
  };
  const fallbackT = (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const path = `${namespace}.${key}`;
    const template = lookup(path);
    if (template == null) return path;
    return values
      ? template.replace(/\{(\w+)\}/g, (m, k: string) => (k in values ? String(values[k]) : m))
      : template;
  };
  return {
    ...actual,
    useTranslations: (namespace: string) => {
      try {
        return actual.useTranslations(namespace);
      } catch {
        // eslint-disable-next-line react-hooks/rules-of-hooks
        return useMemo(() => fallbackT(namespace), [namespace]);
      }
    },
    useLocale: () => {
      try {
        return actual.useLocale();
      } catch {
        return "en";
      }
    },
  };
});

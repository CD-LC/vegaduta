// Test helper: a useTranslations that reads the real messages/en.json. The
// translator is cached per namespace so effects that depend on `t` stay stable.
import en from "../../../messages/en.json";

type Vars = Record<string, string | number>;
const cache = new Map<string, (key: string, vars?: Vars) => string>();

export const i18nMock = {
  useTranslations: (ns: string) => {
    let fn = cache.get(ns);
    if (!fn) {
      fn = (key, vars) => {
        const raw = (en as any)[ns]?.[key];
        if (typeof raw !== "string") return `${ns}.${key}`;
        return raw.replace(/\{(\w+)\}/g, (_, k) => String(vars?.[k] ?? `{${k}}`));
      };
      cache.set(ns, fn);
    }
    return fn;
  },
};

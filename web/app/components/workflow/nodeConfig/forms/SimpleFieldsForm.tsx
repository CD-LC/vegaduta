"use client";

import { useTranslations } from "../../../../lib/i18n";
import { Input } from "../../../ui/input";
import { Label } from "../../../ui/label";
import { SIMPLE_FORMS } from "../descriptors";
import type { NodeFormProps } from "../registryTypes";

/** Renders a node's fields from its SIMPLE_FORMS descriptor. */
export default function SimpleFieldsForm({ nodeType, config, setConfig }: NodeFormProps) {
  const t = useTranslations("nodeConfigDrawer");
  const desc = SIMPLE_FORMS[nodeType];
  if (!desc) return null;
  if (desc.fields.length === 0) return desc.hintKey ? <p className="ax-wf-empty">{t(desc.hintKey)}</p> : null;
  return (
    <div className="field">
      {desc.fields.map((f, i) => {
        const raw = config[f.key];
        return (
          <div key={String(f.key)}>
            <Label className={i > 0 ? "mt-2" : undefined}>{t(f.labelKey)}</Label>
            {f.kind === "text" ? (
              <Input
                type="text"
                value={typeof raw === "string" ? raw : ""}
                onChange={(e) => setConfig({ [f.key]: e.target.value })}
                placeholder={f.placeholderKey ? t(f.placeholderKey) : undefined}
              />
            ) : (
              <Input
                type="number"
                min={f.min}
                max={f.max}
                value={typeof raw === "number" ? raw : f.defaultValue}
                onChange={(e) => setConfig({ [f.key]: Number(e.target.value) })}
              />
            )}
          </div>
        );
      })}
      {desc.hintKey && <p className="ax-wf-empty">{t(desc.hintKey)}</p>}
    </div>
  );
}

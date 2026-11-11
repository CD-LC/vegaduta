"use client";

// The field list of an AWAITING_INPUT pause: one control per form-schema field
// (select/boolean as a Select, number as a numeric input, anything else as a
// textarea), all writing into the card's shared `values` map.
import type { Dispatch, SetStateAction } from "react";
import { Select } from "../ui/select";
import type { InputField } from "./approvalPauseModel";
import { useTranslations } from "../../lib/i18n";

type InputRequestFieldsProps = {
  fields: InputField[];
  /** Id prefix; each control's id is `${idPrefix}-${field.key}`. */
  idPrefix: string;
  values: Record<string, string>;
  setValues: Dispatch<SetStateAction<Record<string, string>>>;
};

export default function InputRequestFields({ fields, idPrefix, values, setValues }: InputRequestFieldsProps) {
  const tHC = useTranslations("hcComponentsWorkflowApprovalInputFields");
  return (
    <div className="apc-fields">
      {fields.map((f) => {
        const id = `${idPrefix}-${f.key}`;
        const set = (v: string) => setValues((p) => ({ ...p, [f.key]: v }));
        const common = {
          id,
          className: "apc-input",
          value: values[f.key] ?? "",
          required: f.required,
          "aria-required": f.required,
        };
        return (
          <div key={f.key} className="apc-field">
            <label htmlFor={id}>
              {f.label}
              {f.required && <span aria-hidden="true"> *</span>}
            </label>
            {f.type === "select" || f.type === "boolean" ? (
              <Select
                id={id}
                value={values[f.key] ?? ""}
                onValueChange={set}
                placeholder={tHC("s1")}
                options={(f.type === "boolean" ? ["true", "false"] : f.options).map((o) => ({
                  value: o,
                  label: f.type === "boolean" ? (o === "true" ? "Yes" : "No") : o,
                }))}
              />
            ) : f.type === "number" ? (
              <input {...common} type="number" inputMode="decimal" onChange={(e) => set(e.target.value)} />
            ) : (
              <textarea {...common} className="apc-input apc-textarea" rows={2} onChange={(e) => set(e.target.value)} />
            )}
          </div>
        );
      })}
    </div>
  );
}

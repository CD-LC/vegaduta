"use client";

// Config forms for the eight data nodes (set, filter, sort, aggregate, limit, dedupe, switch, merge). Kept out of
// NodeConfigDrawer.tsx on purpose (that file is already past its size baseline): the drawer renders
// <DataNodeConfig/> for these types and nothing else. All strings live in the self-contained "workflowNodesData"
// en.json namespace; everything that decides what to show or store is a pure helper in dataNodes.ts.
//
// Fields that take expressions use ScopedExpressionField with the CONCRETE path of the row being edited
// (e.g. "cases[1].conditions[0].value"), matching core's ExpressionFields grammar and nodeDefs.ts's
// EXPRESSION_FIELDS - the field paths and operators themselves are structure, never templated.

import { Plus, Trash2 } from "lucide-react";
import Select from "../Select";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { useTranslations } from "../../lib/i18n";
import SwitchNodeExplainer from "../explainers/SwitchNodeExplainer";
import { ScopedExpressionField } from "./ExpressionField";
import type { WorkflowNodeConfig } from "./nodeDefs";
import {
  AGGREGATE_OPERATIONS,
  COMBINATORS,
  CONDITION_OPERATORS,
  JOIN_TYPES,
  KEEP_CHOICES,
  MERGE_MODES,
  SET_VALUE_TYPES,
  SORT_DIRECTIONS,
  SORT_TYPES,
  caseNameProblem,
  isDataNodeType,
  newAggregate,
  newAssignment,
  newCase,
  newCondition,
  newSortKey,
  operatorTakesValue,
  parseCountInput,
  patchAt,
  removeAt,
  type DataCondition,
} from "./dataNodes";

type Translator = (key: string, values?: Record<string, string | number>) => string;
type SetConfig = (patch: Partial<WorkflowNodeConfig>) => void;

const ROW_STYLE = { display: "flex", gap: "0.4rem", alignItems: "center" } as const;
// Wrapping variant for rows whose value box needs real width: in the narrow node
// drawer a single line squeezed the value input to a few characters (placeholder
// wrapped one word per line). The value field takes its own full-width line.
const WRAP_ROW_STYLE = { ...ROW_STYLE, flexWrap: "wrap" } as const;
const CARD_STYLE = {
  border: "1px solid var(--surface-border)",
  borderRadius: "0.5rem",
  padding: "0.5rem",
  marginBottom: "0.5rem",
  display: "flex",
  flexDirection: "column",
  gap: "0.4rem",
} as const;

function options(prefix: string, values: readonly string[], t: Translator) {
  return values.map((v) => ({ value: v, label: t(`${prefix}${v}`) }));
}

function CheckRow({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <label
      className="row"
      style={{ gap: "0.4rem", fontSize: "0.85rem", color: "var(--text-secondary)", marginTop: "0.25rem" }}
    >
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      {label}
    </label>
  );
}

function RemoveButton({ onClick, label }: { onClick: () => void; label: string }) {
  return (
    <Button type="button" variant="ghost" size="icon" onClick={onClick} title={label} aria-label={label}>
      <Trash2 size={14} aria-hidden="true" />
    </Button>
  );
}

function AddButton({ onClick, label }: { onClick: () => void; label: string }) {
  return (
    <div>
      <Button type="button" variant="outline" size="sm" onClick={onClick}>
        <Plus size={14} aria-hidden="true" />
        {label}
      </Button>
    </div>
  );
}

/** The optional "list lives in this field of the input object" box shared by the five list nodes. */
function ItemsFieldInput({
  t,
  config,
  setConfig,
}: {
  t: Translator;
  config: WorkflowNodeConfig;
  setConfig: SetConfig;
}) {
  return (
    <div className="field">
      <Label>{t("itemsFieldLabel")}</Label>
      <Input
        type="text"
        value={config.itemsField || ""}
        placeholder={t("itemsFieldPlaceholder")}
        onChange={(e) => setConfig({ itemsField: e.target.value })}
      />
      <p className="ax-wf-empty">{t("itemsFieldHint")}</p>
    </div>
  );
}

/**
 * The field / operator / value rows shared by Filter and each Switch case. `fieldPrefix` is the config path of the
 * list ("conditions" or "cases[2].conditions") so the value box's expression path is concrete.
 */
function ConditionRows({
  t,
  conditions,
  fieldPrefix,
  onChange,
}: {
  t: Translator;
  conditions: DataCondition[];
  fieldPrefix: string;
  onChange: (next: DataCondition[]) => void;
}) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
      {conditions.map((c, i) => (
        <div key={i} style={WRAP_ROW_STYLE}>
          <Input
            type="text"
            value={c.field || ""}
            placeholder={t("fieldPlaceholder")}
            aria-label={t("fieldAria")}
            style={{ flex: "1 1 7rem", minWidth: 0 }}
            onChange={(e) => onChange(patchAt(conditions, i, { field: e.target.value }))}
          />
          <div style={{ flex: "1 1 8rem", minWidth: 0 }}>
            <Select
              value={c.operator || "eq"}
              aria-label={t("operatorAria")}
              onChange={(e: any) => onChange(patchAt(conditions, i, { operator: e.target.value }))}
              options={options("op_", CONDITION_OPERATORS, t)}
            />
          </div>
          <RemoveButton label={t("removeCondition")} onClick={() => onChange(removeAt(conditions, i))} />
          {operatorTakesValue(c.operator) && (
            <div style={{ flex: "1 1 100%", minWidth: 0 }}>
              <ScopedExpressionField
                field={`${fieldPrefix}[${i}].value`}
                value={c.value || ""}
                placeholder={t("valuePlaceholder")}
                ariaLabel={t("valueAria")}
                onChange={(v) => onChange(patchAt(conditions, i, { value: v }))}
              />
            </div>
          )}
        </div>
      ))}
      <AddButton label={t("addCondition")} onClick={() => onChange([...conditions, newCondition()])} />
      {conditions.some((c) => c.operator === "regex") && <p className="ax-wf-empty">{t("regexHint")}</p>}
    </div>
  );
}

function SetConfigForm({ t, config, setConfig }: { t: Translator; config: WorkflowNodeConfig; setConfig: SetConfig }) {
  const rows = config.assignments || [];
  return (
    <>
      <p className="ax-wf-empty">{t("setHint")}</p>
      <div className="field">
        <Label>{t("assignmentsLabel")}</Label>
        <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
          {rows.map((a, i) => (
            <div key={i} style={WRAP_ROW_STYLE}>
              <Input
                type="text"
                value={a.name || ""}
                placeholder={t("assignmentNamePlaceholder")}
                aria-label={t("assignmentNameAria")}
                style={{ flex: "1 1 7rem", minWidth: 0 }}
                onChange={(e) => setConfig({ assignments: patchAt(rows, i, { name: e.target.value }) })}
              />
              <div style={{ flex: "1 1 6rem", minWidth: 0 }}>
                <Select
                  value={a.type || "auto"}
                  aria-label={t("assignmentTypeAria")}
                  onChange={(e: any) => setConfig({ assignments: patchAt(rows, i, { type: e.target.value }) })}
                  options={options("type_", SET_VALUE_TYPES, t)}
                />
              </div>
              <RemoveButton
                label={t("removeAssignment")}
                onClick={() => setConfig({ assignments: removeAt(rows, i) })}
              />
              <div style={{ flex: "1 1 100%", minWidth: 0 }}>
                <ScopedExpressionField
                  field={`assignments[${i}].value`}
                  value={a.value || ""}
                  placeholder={t("assignmentValuePlaceholder")}
                  ariaLabel={t("valueAria")}
                  onChange={(v) => setConfig({ assignments: patchAt(rows, i, { value: v }) })}
                />
              </div>
            </div>
          ))}
          <AddButton
            label={t("addAssignment")}
            onClick={() => setConfig({ assignments: [...rows, newAssignment()] })}
          />
        </div>
        <p className="ax-wf-empty">{t("setTypeHint")}</p>
      </div>
      <CheckRow
        checked={!!config.keepOnlySet}
        onChange={(v) => setConfig({ keepOnlySet: v })}
        label={t("keepOnlySetLabel")}
      />
    </>
  );
}

function FilterConfigForm({
  t,
  config,
  setConfig,
}: {
  t: Translator;
  config: WorkflowNodeConfig;
  setConfig: SetConfig;
}) {
  const conditions = config.conditions || [];
  return (
    <>
      <p className="ax-wf-empty">{t("filterHint")}</p>
      <ItemsFieldInput t={t} config={config} setConfig={setConfig} />
      <div className="field">
        <Label>{t("conditionsLabel")}</Label>
        {conditions.length > 1 && (
          <Select
            value={config.combinator || "and"}
            aria-label={t("combinatorLabel")}
            onChange={(e: any) => setConfig({ combinator: e.target.value })}
            options={options("combinator_", COMBINATORS, t)}
          />
        )}
        <ConditionRows
          t={t}
          conditions={conditions}
          fieldPrefix="conditions"
          onChange={(next) => setConfig({ conditions: next })}
        />
      </div>
      <CheckRow
        checked={!!config.ignoreCase}
        onChange={(v) => setConfig({ ignoreCase: v })}
        label={t("ignoreCaseLabel")}
      />
    </>
  );
}

function SortConfigForm({ t, config, setConfig }: { t: Translator; config: WorkflowNodeConfig; setConfig: SetConfig }) {
  const keys = config.sortKeys || [];
  return (
    <>
      <p className="ax-wf-empty">{t("sortHint")}</p>
      <ItemsFieldInput t={t} config={config} setConfig={setConfig} />
      <div className="field">
        <Label>{t("sortKeysLabel")}</Label>
        <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
          {keys.map((k, i) => (
            <div key={i} style={ROW_STYLE}>
              <Input
                type="text"
                value={k.field || ""}
                placeholder={t("sortFieldPlaceholder")}
                aria-label={t("fieldAria")}
                style={{ flex: "1 1 34%", minWidth: 0 }}
                onChange={(e) => setConfig({ sortKeys: patchAt(keys, i, { field: e.target.value }) })}
              />
              <div style={{ flex: "0 0 29%", minWidth: 0 }}>
                <Select
                  value={k.direction || "asc"}
                  aria-label={t("sortDirectionAria")}
                  onChange={(e: any) => setConfig({ sortKeys: patchAt(keys, i, { direction: e.target.value }) })}
                  options={options("dir_", SORT_DIRECTIONS, t)}
                />
              </div>
              <div style={{ flex: "0 0 24%", minWidth: 0 }}>
                <Select
                  value={k.type || "auto"}
                  aria-label={t("sortTypeAria")}
                  onChange={(e: any) => setConfig({ sortKeys: patchAt(keys, i, { type: e.target.value }) })}
                  options={options("sortType_", SORT_TYPES, t)}
                />
              </div>
              <RemoveButton label={t("removeSortKey")} onClick={() => setConfig({ sortKeys: removeAt(keys, i) })} />
            </div>
          ))}
          <AddButton label={t("addSortKey")} onClick={() => setConfig({ sortKeys: [...keys, newSortKey()] })} />
        </div>
      </div>
      <CheckRow
        checked={!!config.caseSensitive}
        onChange={(v) => setConfig({ caseSensitive: v })}
        label={t("caseSensitiveLabel")}
      />
    </>
  );
}

function AggregateConfigForm({
  t,
  config,
  setConfig,
}: {
  t: Translator;
  config: WorkflowNodeConfig;
  setConfig: SetConfig;
}) {
  const specs = config.aggregations || [];
  return (
    <>
      <p className="ax-wf-empty">{t("aggregateHint")}</p>
      <ItemsFieldInput t={t} config={config} setConfig={setConfig} />
      <div className="field">
        <Label>{t("groupByLabel")}</Label>
        <Input
          type="text"
          value={config.groupBy || ""}
          placeholder={t("groupByPlaceholder")}
          onChange={(e) => setConfig({ groupBy: e.target.value })}
        />
      </div>
      <div className="field">
        <Label>{t("aggregationsLabel")}</Label>
        <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
          {specs.map((s, i) => (
            <div key={i} style={CARD_STYLE}>
              <div style={ROW_STYLE}>
                <div style={{ flex: "0 0 40%", minWidth: 0 }}>
                  <Select
                    value={s.operation || "count"}
                    aria-label={t("aggOperationAria")}
                    onChange={(e: any) => setConfig({ aggregations: patchAt(specs, i, { operation: e.target.value }) })}
                    options={options("agg_", AGGREGATE_OPERATIONS, t)}
                  />
                </div>
                <Input
                  type="text"
                  value={s.field || ""}
                  placeholder={t("aggFieldPlaceholder")}
                  aria-label={t("fieldAria")}
                  style={{ flex: 1, minWidth: 0 }}
                  onChange={(e) => setConfig({ aggregations: patchAt(specs, i, { field: e.target.value }) })}
                />
                <RemoveButton
                  label={t("removeAggregation")}
                  onClick={() => setConfig({ aggregations: removeAt(specs, i) })}
                />
              </div>
              <div style={ROW_STYLE}>
                <Input
                  type="text"
                  value={s.as || ""}
                  placeholder={t("aggAsPlaceholder")}
                  aria-label={t("aggAsAria")}
                  style={{ flex: 1, minWidth: 0 }}
                  onChange={(e) => setConfig({ aggregations: patchAt(specs, i, { as: e.target.value }) })}
                />
                {s.operation === "concat" && (
                  <Input
                    type="text"
                    value={s.separator ?? ", "}
                    placeholder={t("aggSeparatorPlaceholder")}
                    aria-label={t("aggSeparatorPlaceholder")}
                    style={{ flex: "0 0 30%", minWidth: 0 }}
                    onChange={(e) => setConfig({ aggregations: patchAt(specs, i, { separator: e.target.value }) })}
                  />
                )}
              </div>
            </div>
          ))}
          <AddButton
            label={t("addAggregation")}
            onClick={() => setConfig({ aggregations: [...specs, newAggregate()] })}
          />
        </div>
      </div>
    </>
  );
}

function LimitConfigForm({
  t,
  config,
  setConfig,
}: {
  t: Translator;
  config: WorkflowNodeConfig;
  setConfig: SetConfig;
}) {
  return (
    <>
      <p className="ax-wf-empty">{t("limitHint")}</p>
      <ItemsFieldInput t={t} config={config} setConfig={setConfig} />
      <div className="field">
        <Label>{t("limitCountLabel")}</Label>
        <ScopedExpressionField
          field="count"
          value={config.count === undefined || config.count === null ? "" : String(config.count)}
          placeholder="10"
          ariaLabel={t("limitCountLabel")}
          onChange={(v) => setConfig({ count: parseCountInput(v) })}
        />
      </div>
      <div className="field">
        <Label>{t("limitModeLabel")}</Label>
        <Select
          value={config.mode === "last" ? "last" : "first"}
          aria-label={t("limitModeLabel")}
          onChange={(e: any) => setConfig({ mode: e.target.value })}
          options={options("mode_", KEEP_CHOICES, t)}
        />
      </div>
    </>
  );
}

function DedupeConfigForm({
  t,
  config,
  setConfig,
}: {
  t: Translator;
  config: WorkflowNodeConfig;
  setConfig: SetConfig;
}) {
  return (
    <>
      <p className="ax-wf-empty">{t("dedupeHint")}</p>
      <ItemsFieldInput t={t} config={config} setConfig={setConfig} />
      <div className="field">
        <Label>{t("dedupeByLabel")}</Label>
        <Input
          type="text"
          value={config.dedupeBy || ""}
          placeholder={t("dedupeByPlaceholder")}
          onChange={(e) => setConfig({ dedupeBy: e.target.value })}
        />
      </div>
      <div className="field">
        <Label>{t("keepLabel")}</Label>
        <Select
          value={config.keep === "last" ? "last" : "first"}
          aria-label={t("keepLabel")}
          onChange={(e: any) => setConfig({ keep: e.target.value })}
          options={options("keep_", KEEP_CHOICES, t)}
        />
      </div>
      <CheckRow
        checked={!!config.ignoreCase}
        onChange={(v) => setConfig({ ignoreCase: v })}
        label={t("ignoreCaseLabel")}
      />
    </>
  );
}

function SwitchConfigForm({
  t,
  config,
  setConfig,
}: {
  t: Translator;
  config: WorkflowNodeConfig;
  setConfig: SetConfig;
}) {
  const cases = config.cases || [];
  return (
    <>
      <p className="ax-wf-empty">{t("switchHint")}</p>
      <SwitchNodeExplainer className="mb-3" />
      <div className="field">
        <Label>{t("casesLabel")}</Label>
        {cases.map((c, i) => {
          const problem = caseNameProblem(cases, i);
          return (
            <div key={i} style={CARD_STYLE}>
              <div style={ROW_STYLE}>
                <Input
                  type="text"
                  value={c.name || ""}
                  placeholder={t("caseNamePlaceholder")}
                  aria-label={t("caseNameAria")}
                  aria-invalid={problem ? true : undefined}
                  style={{ flex: 1, minWidth: 0 }}
                  onChange={(e) => setConfig({ cases: patchAt(cases, i, { name: e.target.value }) })}
                />
                {(c.conditions || []).length > 1 && (
                  <div style={{ flex: "0 0 44%", minWidth: 0 }}>
                    <Select
                      value={c.combinator || "and"}
                      aria-label={t("combinatorLabel")}
                      onChange={(e: any) => setConfig({ cases: patchAt(cases, i, { combinator: e.target.value }) })}
                      options={options("combinator_", COMBINATORS, t)}
                    />
                  </div>
                )}
                <RemoveButton label={t("removeCase")} onClick={() => setConfig({ cases: removeAt(cases, i) })} />
              </div>
              {problem && (
                <p className="ax-wf-empty" role="alert">
                  {t(problem === "reserved" ? "caseReservedWarn" : "caseDuplicateWarn")}
                </p>
              )}
              <ConditionRows
                t={t}
                conditions={c.conditions || []}
                fieldPrefix={`cases[${i}].conditions`}
                onChange={(next) => setConfig({ cases: patchAt(cases, i, { conditions: next }) })}
              />
            </div>
          );
        })}
        <AddButton label={t("addCase")} onClick={() => setConfig({ cases: [...cases, newCase(cases)] })} />
      </div>
      <CheckRow
        checked={!!config.ignoreCase}
        onChange={(v) => setConfig({ ignoreCase: v })}
        label={t("ignoreCaseLabel")}
      />
    </>
  );
}

function MergeConfigForm({
  t,
  config,
  setConfig,
}: {
  t: Translator;
  config: WorkflowNodeConfig;
  setConfig: SetConfig;
}) {
  const mode = (MERGE_MODES as readonly string[]).includes(config.mode || "") ? (config.mode as string) : "append";
  const sources = config.sources || [];
  return (
    <>
      <p className="ax-wf-empty">{t("mergeHint")}</p>
      <div className="field">
        <Label>{t("mergeModeLabel")}</Label>
        <Select
          value={mode}
          aria-label={t("mergeModeLabel")}
          onChange={(e: any) => setConfig({ mode: e.target.value })}
          options={options("mergeMode_", MERGE_MODES, t)}
        />
      </div>
      {mode === "join" && (
        <>
          <div className="field">
            <Label>{t("joinKeyLabel")}</Label>
            <Input
              type="text"
              value={config.key || ""}
              placeholder={t("joinKeyPlaceholder")}
              onChange={(e) => setConfig({ key: e.target.value })}
            />
          </div>
          <div className="field">
            <Label>{t("joinTypeLabel")}</Label>
            <Select
              value={config.joinType || "left"}
              aria-label={t("joinTypeLabel")}
              onChange={(e: any) => setConfig({ joinType: e.target.value })}
              options={options("join_", JOIN_TYPES, t)}
            />
          </div>
        </>
      )}
      <div className="field">
        <Label>{t("sourcesLabel")}</Label>
        <div style={{ display: "flex", flexDirection: "column", gap: "0.4rem" }}>
          {sources.map((s, i) => (
            <div key={i} style={ROW_STYLE}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <ScopedExpressionField
                  field={`sources[${i}]`}
                  value={s || ""}
                  placeholder={t("sourcePlaceholder")}
                  ariaLabel={t("sourcesLabel")}
                  onChange={(v) => setConfig({ sources: sources.map((x, k) => (k === i ? v : x)) })}
                />
              </div>
              <RemoveButton label={t("removeSource")} onClick={() => setConfig({ sources: removeAt(sources, i) })} />
            </div>
          ))}
          <AddButton label={t("addSource")} onClick={() => setConfig({ sources: [...sources, ""] })} />
        </div>
      </div>
      <div className="field">
        <Label>{t("minInputsLabel")}</Label>
        <Input
          type="number"
          min={0}
          value={config.minInputs === undefined || config.minInputs === null ? "" : String(config.minInputs)}
          onChange={(e) => setConfig({ minInputs: e.target.value === "" ? 0 : Number(e.target.value) })}
        />
        <p className="ax-wf-empty">{t("minInputsHint")}</p>
      </div>
    </>
  );
}

/** The drawer's one entry point: the form for whichever data node is selected (null for any other type). */
// `config` / `setConfig` are typed loosely on purpose: the drawer owns its own (older, narrower) WorkflowNodeConfig
// interface, and every key this form touches is optional and checked by dataNodes.ts, so a structural mismatch
// between the two declarations must not become a compile error here.
export default function DataNodeConfig({
  type,
  config,
  setConfig,
}: {
  type: string;
  config: any;
  setConfig: (patch: any) => void;
}) {
  const t = useTranslations("workflowNodesData") as Translator;
  if (!isDataNodeType(type)) return null;
  const props = { t, config, setConfig };
  switch (type) {
    case "set":
      return <SetConfigForm {...props} />;
    case "filter":
      return <FilterConfigForm {...props} />;
    case "sort":
      return <SortConfigForm {...props} />;
    case "aggregate":
      return <AggregateConfigForm {...props} />;
    case "limit":
      return <LimitConfigForm {...props} />;
    case "dedupe":
      return <DedupeConfigForm {...props} />;
    case "switch":
      return <SwitchConfigForm {...props} />;
    default:
      return <MergeConfigForm {...props} />;
  }
}

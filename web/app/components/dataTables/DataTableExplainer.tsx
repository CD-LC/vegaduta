"use client";

import { useTranslations } from "../../lib/i18n";
import FlowExplainer from "../explainers/FlowExplainer";

/**
 * "How this works" for /data-tables, written against core:
 *   1. WorkflowDataTableNodeHandler - the workflow "Data Table" step reads (get_rows, count) or writes (insert_row,
 *      update_rows, upsert_row, delete_rows); an update or delete with no filter is refused unless the step says
 *      it may touch every row. Agents get data_table_query / data_table_write only when each is added to the
 *      agent's tool list (AgentDataTableToolResolver) - read and write are opted into separately.
 *   2. Tables belong to the workspace and are not cleared when a run ends - that is the point of the node.
 *   3. This page reads for everyone; writing needs the workflow-admin permission (the panel hides those controls
 *      otherwise). Export CSV downloads the whole table, declared columns only, and is refused above 50,000 rows
 *      (DataTableService.EXPORT_MAX_ROWS).
 */
export default function DataTableExplainer({ className }: { className?: string }) {
  const t = useTranslations("dataTables");
  return (
    <FlowExplainer
      className={className}
      intro={t("explainIntro")}
      steps={[
        {
          id: "use",
          title: t("explainUseTitle"),
          description: t("explainUseBody"),
          outcomes: [
            { key: "read", label: t("explainReadLabel"), description: t("explainReadBody"), state: "running" },
            { key: "write", label: t("explainWriteLabel"), description: t("explainWriteBody"), state: "warning" },
          ],
        },
        { id: "keep", title: t("explainKeepTitle"), description: t("explainKeepBody") },
        { id: "export", title: t("explainExportTitle"), description: t("explainExportBody") },
      ]}
    />
  );
}

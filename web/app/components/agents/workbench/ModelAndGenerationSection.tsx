"use client";

import { SlidersHorizontal } from "lucide-react";
import { useWorkbench } from "./context";
import { WorkbenchSection } from "./WorkbenchSection";
import DraftModelFields from "./model/DraftModelFields";
import ModelEditor from "./model/ModelEditor";
import PoolStatusNotes from "./model/PoolStatusNotes";
import ProviderModelHeader from "./model/ProviderModelHeader";

// The workbench "Model & generation" section. Each block reads the workbench context itself:
// the provider/model header, the pool vs own-key notes, the creation-form model fields and the
// saved agent's model editor (see ./model).
export default function ModelAndGenerationSection() {
  const { t } = useWorkbench();
  return (
    <>
      <WorkbenchSection
        icon={<SlidersHorizontal size={13} />}
        label={t("modelGenerationLabel")}
        cat="var(--cat-comms)"
        tourId="workbench-model"
      >
        <ProviderModelHeader />
        <PoolStatusNotes />
        <DraftModelFields />
        <ModelEditor />
      </WorkbenchSection>
    </>
  );
}

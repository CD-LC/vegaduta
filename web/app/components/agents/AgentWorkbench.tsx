"use client";

import AutonomySection from "./workbench/AutonomySection";
import CachingAndTeamSection from "./workbench/CachingAndTeamSection";
import CapabilitiesSection from "./workbench/CapabilitiesSection";
import { WorkbenchContext } from "./workbench/context";
import GenerationTuningSection from "./workbench/GenerationTuningSection";
import IdentitySection from "./workbench/IdentitySection";
import KnowledgeSection from "./workbench/KnowledgeSection";
import LabSection from "./workbench/LabSection";
import ModelAndGenerationSection from "./workbench/ModelAndGenerationSection";
import ReliabilitySection from "./workbench/ReliabilitySection";
import RoutingSection from "./workbench/RoutingSection";
import ShareSection from "./workbench/ShareSection";
import ToolsSection from "./workbench/ToolsSection";
import TranscriptionSection from "./workbench/TranscriptionSection";
import type { WorkbenchProps } from "./workbench/types";
import { useWorkbenchController } from "./workbench/useWorkbenchController";
import VersionsSection from "./workbench/VersionsSection";
import WorkbenchBanners from "./workbench/WorkbenchBanners";
import WorkbenchToolbar from "./workbench/WorkbenchToolbar";
export type {
  AgentReliabilityFailure,
  AgentReliabilitySummary,
  ResolvedTool,
  WorkbenchAgent,
  WorkbenchCollection,
  WorkbenchProvider,
} from "./AgentWorkbench/types";

/**
 * Agents workbench: build-and-test screen for ONE agent at a time (config
 * panel on the left, live Playground on the right).
 *
 * This file is only the shell. State and handlers live in feature hooks under
 * ./workbench (composed by useWorkbenchController; the new-agent draft is one
 * reducer, state/agentDraftReducer.ts), and every panel is a section component
 * in the same folder that reads the controller through useWorkbench().
 * Identity/persona and name are read-only after creation (no PATCH endpoint);
 * generation, tools, knowledge and mode apply immediately through their own
 * endpoints. The honest per-section behaviour notes sit next to the code.
 */
export default function AgentWorkbench(props: WorkbenchProps) {
  const controller = useWorkbenchController(props);
  return (
    <WorkbenchContext.Provider value={controller}>
      <div className="flex min-w-0 flex-col gap-4">
        <WorkbenchToolbar />

        <WorkbenchBanners />

        <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(360px,460px)]">
          {/* ---- CONFIG PANEL ---- */}
          <div className="flex min-w-0 flex-col gap-4">
            <IdentitySection />
            <ModelAndGenerationSection />

            <RoutingSection />

            <GenerationTuningSection />

            <TranscriptionSection />

            <AutonomySection />

            <ReliabilitySection />

            <CachingAndTeamSection />

            <KnowledgeSection />

            <ToolsSection />

            <CapabilitiesSection />

            <ShareSection />

            <VersionsSection />
          </div>

          <LabSection />
        </div>
      </div>
    </WorkbenchContext.Provider>
  );
}

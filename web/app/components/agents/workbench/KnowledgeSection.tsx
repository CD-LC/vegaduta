"use client";

import KnowledgeCollectionsSection from "../KnowledgeCollectionsSection";
import { useWorkbench } from "./context";

export default function KnowledgeSection() {
  const {
    selectedAgentId,
    t,
    isDraft,
    locked,
    collections,
    shownCollections,
    selectedAgent,
    newAgentMode,
    knowledgeBaseEnabled,
    setKnowledgeBaseEnabled,
    editingKnowledge,
    setEditingKnowledge,
    knowledgeDraft,
    setKnowledgeDraft,
    toggleKnowledgeDraft,
    saveKnowledge,
    knowledgeBusy,
  } = useWorkbench();
  return (
    <>
      {/* Knowledge collections — collapsible; chips/buttons standardized
              onto the shadcn Badge/Button primitives for consistency. */}
      <KnowledgeCollectionsSection
        key={selectedAgentId ?? "draft"}
        t={t}
        isDraft={isDraft}
        locked={!!locked}
        collections={collections}
        shownCollections={shownCollections}
        allowedCollectionIds={selectedAgent?.allowedKnowledgeCollectionIds}
        newAgentMode={newAgentMode}
        knowledgeBaseEnabled={knowledgeBaseEnabled}
        setKnowledgeBaseEnabled={setKnowledgeBaseEnabled}
        editingKnowledge={editingKnowledge}
        setEditingKnowledge={setEditingKnowledge}
        knowledgeDraft={knowledgeDraft}
        setKnowledgeDraft={setKnowledgeDraft}
        toggleKnowledgeDraft={toggleKnowledgeDraft}
        saveKnowledge={saveKnowledge}
        knowledgeBusy={knowledgeBusy}
      />
    </>
  );
}

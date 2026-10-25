"use client";

import KnowledgeCollectionsSection from "../KnowledgeCollectionsSection";
import KnowledgeWriteSection from "./KnowledgeWriteSection";
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
      {/* The write side (add_to_knowledge_base): its own opt-in + deny-by-default grant
          list on /knowledge-write-access - fetched per agent inside the component; drafts
          have no id yet and see nothing here. */}
      <KnowledgeWriteSection
        key={`write-${selectedAgentId ?? "draft"}`}
        t={t}
        locked={!!locked}
        agentId={isDraft ? null : (selectedAgentId ?? null)}
        collections={collections}
      />
    </>
  );
}

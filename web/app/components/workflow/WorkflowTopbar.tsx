"use client";

import { Button } from "../ui/button";
import { ShieldAlert, PlayCircle, BellRing, Maximize2, Minimize2, Menu, PanelRight, Route, Network, Share2, Gauge, Undo2, Redo2, ArrowLeft, MoreHorizontal, PanelLeft, Scan, Save, Rocket, History, MessageSquare } from "lucide-react";
import { Badge } from "../ui/badge";
import { ActionGroup, IconAction } from "./StudioControls";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";
import { motion, AnimatePresence } from "motion/react";
import CoachButton from "../brief/CoachDrawer";
import { canRunDraft } from "./canvasRun";
import { useTranslations } from "../../lib/i18n";

export default function WorkflowTopbar({
  alerts,
  alertsPopoverRef,
  canComment,
  commentsOpen,
  autosaveState,
  busy,
  exitTour,
  focusMode,
  handleDuplicate,
  historyRef,
  inspectorOpen,
  isDirty,
  nodes,
  onBack,
  onToggleComments,
  onToggleHistory,
  historyOpen,
  openDiagramModal,
  openIntelligenceModal,
  openRunModal,
  paletteOpen,
  readOnly,
  redo,
  save,
  setInspectorOpen,
  setPaletteOpen,
  setShowAlertsPopover,
  setShowShareModal,
  showAlertsPopover,
  startTour,
  studio,
  t,
  tourOpen,
  undo,
  validate,
  workflow,
  workflowId,
}: any) {
  // Studio layout (2026-09-29): ONE row, three groups - identity (back,
  // name, status), editing (undo/redo/save state), shipping (save, validate,
  // activate, RUN). Rarely-used actions (focus, tour, diagram, intelligence)
  // live in "More". Controls follow the console's button contract (shadcn
  // Button: primary sm / outline sm / ghost icon + Tooltip, 14px icons) so the
  // designer reads like every other console page. Labels collapse to icons via
  // container queries on .ax-wf-topbar (workflow-studio.css) - never wraps.
  const tu = useTranslations("workflowStudioUi");
  const label = (text: string) => <span className="ax-wf-tb-label">{text}</span>;
  const statusActive = workflow.status === "ACTIVE";
  return (
    <>
      <div className="ax-wf-topbar" data-toast-avoid="">
        <div className="ax-wf-tb-group ax-wf-tb-identity">
          <IconAction label={t("back")} onClick={onBack}>
            <ArrowLeft size={16} aria-hidden="true" />
          </IconAction>
          {/* Below 900px the panels are drawers - these open them. */}
          <IconAction
            label={t("nodePaletteAriaLabel")}
            className="ax-wf-drawer-toggle"
            onClick={() => setPaletteOpen((v: any) => !v)}
            aria-expanded={paletteOpen}
          >
            <Menu size={16} aria-hidden="true" />
          </IconAction>
          <IconAction
            label={t("runInspectorAriaLabel")}
            className="ax-wf-drawer-toggle"
            onClick={() => setInspectorOpen((v: any) => !v)}
            aria-expanded={inspectorOpen}
          >
            <PanelRight size={16} aria-hidden="true" />
          </IconAction>
          <div className="ax-wf-tb-title" title={workflow.name}>
            <span className="ax-wf-tb-crumb text-xs text-muted-foreground">{t("workflowsBreadcrumb")} /</span>
            <span className="ax-wf-tb-name text-sm font-semibold text-foreground">{workflow.name}</span>
          </div>
          <Badge
            variant={statusActive ? "accent" : "secondary"}
            className="ax-wf-tb-status"
            title={workflow.status}
          >
            <span className={`ax-wf-tb-status-dot${statusActive ? " is-active" : ""}`} aria-hidden="true" />
            <span className="ax-wf-tb-status-text">{workflow.status}</span>
          </Badge>
        </div>

        <div className="ax-wf-tb-group ax-wf-tb-edit">
          <IconAction
            label={t("undoTooltip")}
            shortcut="Ctrl+Z"
            onClick={undo}
            disabled={busy || readOnly || historyRef.current.past.length === 0}
          >
            <Undo2 size={16} aria-hidden="true" />
          </IconAction>
          <IconAction
            label={t("redoTooltip")}
            shortcut="Ctrl+Y"
            onClick={redo}
            disabled={busy || readOnly || historyRef.current.future.length === 0}
          >
            <Redo2 size={16} aria-hidden="true" />
          </IconAction>
          <span className="ax-wf-dirty-badge text-xs" data-dirty={isDirty ? "true" : undefined} role="status">
            {isDirty
              ? (autosaveState === "saving" ? t("autosavingLabel") : t("unsavedLabel"))
              : (autosaveState === "saved" ? t("autosavedLabel") : "")}
          </span>
        </div>

        <span className="ax-wf-spacer" />

        {studio && (
          <ActionGroup label={t("studioLayoutGroup")} className="ax-wf-tb-group ax-wf-tb-studio">
            <IconAction
              label={t("studioTogglePalette")}
              shortcut="["
              pressed={studio.leftMode === "open"}
              onClick={studio.toggleLeft}
              disabled={readOnly}
            >
              <PanelLeft size={16} aria-hidden="true" />
            </IconAction>
            <IconAction label={t("studioZen")} shortcut="\" pressed={studio.zen} onClick={studio.toggleZen}>
              <Scan size={16} aria-hidden="true" />
            </IconAction>
            <IconAction
              label={t("studioToggleInspector")}
              shortcut="]"
              pressed={studio.rightMode === "open"}
              onClick={studio.toggleRight}
            >
              <PanelRight size={16} aria-hidden="true" />
            </IconAction>
          </ActionGroup>
        )}

        {alerts.length > 0 && (
          <div className="wf-alerts-root" ref={alertsPopoverRef}>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className={alerts.some((a: any) => a.tone === "warn") ? "border-amber-500/40 bg-amber-500/10 text-amber-600 hover:bg-amber-500/15 dark:text-amber-400" : undefined}
              onClick={() => setShowAlertsPopover((v: any) => !v)}
              title={t("alertsButton", { count: alerts.length })}
              aria-label={t("alertsButton", { count: alerts.length })}
            >
              <BellRing size={14} aria-hidden="true" /> {label(t("alertsButton", { count: alerts.length }))}
            </Button>
            <AnimatePresence>
              {showAlertsPopover && (
                <motion.div
                  className="wf-alerts-popover"
                  initial={{ opacity: 0, scale: 0.96, y: -4 }}
                  animate={{ opacity: 1, scale: 1, y: 0 }}
                  exit={{ opacity: 0, scale: 0.96, y: -4 }}
                  transition={{ duration: 0.12, ease: [0.16, 1, 0.3, 1] }}
                >
                  <h2 className="wf-alerts-title">{t("alertsHeading")}</h2>
                  <ul className="wf-alerts-list">
                    {alerts.map((a: any) => (
                      <li key={a.id} className={`wf-alert-row wf-alert-${a.tone}`}>
                        {a.text}
                      </li>
                    ))}
                  </ul>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        )}

        <div className="ax-wf-tb-group ax-wf-tb-ship">
          {workflow.editable === false ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleDuplicate}
              disabled={busy}
              title={t("editLockedDuplicateTooltip")}
            >
              {t("duplicateToEdit")}
            </Button>
          ) : (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => save("DRAFT")}
              disabled={busy}
              title={workflow.status === "ACTIVE" ? t("revertToDraftTooltip") : t("saveDraft")}
              aria-label={workflow.status === "ACTIVE" ? t("revertToDraft") : t("saveDraft")}
            >
              <Save size={14} aria-hidden="true" />
              {label(workflow.status === "ACTIVE" ? t("revertToDraft") : t("saveDraft"))}
            </Button>
          )}
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={validate}
            disabled={busy}
            title={t("validateSavesDraftTooltip")}
            aria-label={t("validate")}
          >
            <ShieldAlert size={14} aria-hidden="true" /> {label(t("validate"))}
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => save("ACTIVE")}
            disabled={busy || workflow.status === "ACTIVE"}
            upgradeLocked={workflow.editable === false}
            upgradeReason={t("activateLockedTooltip")}
            title={workflow.status === "ACTIVE" ? t("activateAlreadyActiveTooltip") : t("activate")}
            aria-label={t("activate")}
          >
            <Rocket size={14} aria-hidden="true" /> {label(t("activate"))}
          </Button>
          {canRunDraft(workflow) && (
            <Button type="button" size="sm" onClick={openRunModal} disabled={busy} title={t("validateSavesDraftTooltip")}>
              <PlayCircle size={14} aria-hidden="true" /> {t("runNow")}
            </Button>
          )}
        </div>

        <div className="ax-wf-tb-group ax-wf-tb-more">
          <IconAction label={tu("historyButton")} pressed={!!historyOpen} onClick={onToggleHistory} disabled={!workflow?.id}>
            <History size={16} aria-hidden="true" />
          </IconAction>
          {canComment && (
            <IconAction label={tu("commentsButton")} pressed={!!commentsOpen} onClick={onToggleComments}>
              <MessageSquare size={16} aria-hidden="true" />
            </IconAction>
          )}
          <IconAction label={t("studioShare")} onClick={() => setShowShareModal(true)} disabled={busy || !workflow?.id}>
            <Share2 size={16} aria-hidden="true" />
          </IconAction>
          <CoachButton entityId={workflowId} />
          <Popover>
            <PopoverTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="size-8 text-muted-foreground hover:text-foreground"
                title={t("studioMore")}
                aria-label={t("studioMore")}
              >
                <MoreHorizontal size={16} aria-hidden="true" />
              </Button>
            </PopoverTrigger>
            <PopoverContent align="end" sideOffset={8} className="ax-wf-more-menu">
              {focusMode ? (
                <Button asChild variant="ghost" size="sm" className="justify-start" title={t("exitFocusTooltip")}>
                  <a href={`/workflows/${workflowId}`}>
                    <Minimize2 size={14} aria-hidden="true" /> {t("exitFocus")}
                  </a>
                </Button>
              ) : (
                <Button asChild variant="ghost" size="sm" className="justify-start" title={t("focusTooltip")}>
                  <a href={`/workflows/${workflowId}?focus=1`} target="_blank" rel="noopener noreferrer">
                    <Maximize2 size={14} aria-hidden="true" /> {t("focus")}
                  </a>
                </Button>
              )}
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="justify-start"
                onClick={tourOpen ? exitTour : startTour}
                disabled={nodes.length === 0}
                title={t("tourTooltip")}
              >
                <Route size={14} aria-hidden="true" /> {t("tour")}
              </Button>
              <Button type="button" variant="ghost" size="sm" className="justify-start" onClick={openDiagramModal} disabled={busy}>
                <Network size={14} aria-hidden="true" /> {t("diagramBtn")}
              </Button>
              <Button type="button" variant="ghost" size="sm" className="justify-start" onClick={openIntelligenceModal} disabled={busy}>
                <Gauge size={14} aria-hidden="true" /> {t("intelligenceBtn")}
              </Button>
              {studio && (
                <p className="ax-wf-more-hint text-xs text-muted-foreground">
                  <kbd className="wf-icon-action-kbd">[</kbd> <kbd className="wf-icon-action-kbd">]</kbd>{" "}
                  <kbd className="wf-icon-action-kbd">\</kbd> {t("studioShortcutsHint")}
                </p>
              )}
            </PopoverContent>
          </Popover>
        </div>
      </div>
    </>
  );
}

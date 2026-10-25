package com.agenticai.core.agents.tools.classification;

import java.util.Map;

/**
 * Second half of the {@link BuiltInToolClassifications} declaration table (messaging and channels
 * onwards), split out only to keep each file reviewable. The conventions stated on {@link
 * BuiltInToolClassifications} apply to every entry here; insertion order is part of the contract
 * because {@link BuiltInToolClassifications#all()} reports in it.
 */
final class BuiltInToolClassificationsExtended {

    private BuiltInToolClassificationsExtended() {
        // static declaration table
    }

    /** Appends the messaging-onwards declarations to {@code exact}, in declaration order. */
    static void declare(Map<String, ToolClassification> exact) {
        // ---- Messaging / channels ------------------------------------------
        // Inbound channel history: same reasoning as the Gmail read side. Anyone
        // who can message the tenant's Telegram bot can author these bytes.
        exact.put(
                "telegram_read_messages",
                new ToolClassification(
                        "telegram_read_messages",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // Sends a stored artifact to a channel + recipient named in the call
        // (WHATSAPP/SLACK/DISCORD/TELEGRAM/EMAIL, plus an address in that
        // channel's own form). Caller-chosen destination = EGRESS, and a delivered
        // message cannot be recalled = irreversible. Note the tool already refuses
        // fabricated URLs server-side; that closes a hallucination hole, not this
        // one - a REAL generated artifact forwarded to an attacker-chosen address
        // is a complete exfiltration path and is what the taint gate is for.
        exact.put(
                "forward_attachment",
                new ToolClassification(
                        "forward_attachment",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
        // Escalation to a human operator inside the platform's own handoff
        // subsystem - no external destination, no tenant data leaves. Notifying a
        // human is also the one action that is safe to take while tainted, since
        // the outcome is "a person looks at it", which is what the gate wants
        // anyway.
        exact.put(
                "request_human",
                new ToolClassification(
                        "request_human",
                        ToolTrustTier.TRUSTED_INTERNAL,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        // Channel command surface (agents control channels, 2026-09-23).
        // channels_overview and channel_list_sessions read the tenant's own
        // channel/session metadata - connectivity state, counts, routing -
        // none of it stranger-authored, so TENANT_SCOPED reads.
        exact.put(
                "channels_overview",
                new ToolClassification(
                        "channels_overview",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // Session rows carry external ids and message previews - still the
        // tenant's own data (READ_TENANT), but the preview text itself is
        // stranger-authored content replayed to the model, so UNTRUSTED_EXTERNAL
        // like every other inbound-channel read.
        exact.put(
                "channel_list_sessions",
                new ToolClassification(
                        "channel_list_sessions",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // channel_read_messages is channel-generic telegram_read_messages:
        // identical source, identical classification - inbound bodies are
        // authored by whoever messaged the tenant's accounts.
        exact.put(
                "channel_read_messages",
                new ToolClassification(
                        "channel_read_messages",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // channel_send_message is the same lethal shape as forward_attachment:
        // caller-chosen channel + caller-chosen recipient + caller-authored
        // body = EGRESS, and a delivered message cannot be recalled =
        // irreversible. The tool additionally routes through the platform's
        // own approval gate (channel.*.send action types), but that gate is a
        // policy layer - the structural risk statement lives here.
        exact.put(
                "channel_send_message",
                new ToolClassification(
                        "channel_send_message",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
        // channel_session_action mutates the trust boundary itself: approving
        // a pairing hands a stranger a live line to the tenant's agents. It is
        // nominally undoable (unpair exists), but the damage window between
        // approve and unpair is exactly what an injected instruction would
        // exploit - declared irreversible so the confirmation gate treats the
        // grant like the trust decision it is, not like a reversible edit.
        exact.put(
                "channel_session_action",
                new ToolClassification(
                        "channel_session_action",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));

        // ---- Autonomous job plumbing ---------------------------------------
        exact.put(
                "report_progress",
                new ToolClassification(
                        "report_progress",
                        ToolTrustTier.TRUSTED_INTERNAL,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));

        // ---- Arbitrary code execution ---------------------------------------
        // run_code executes model-authored source in an E2B sandbox. Declared
        // EGRESS because nothing in this repository establishes that the sandbox
        // has no outbound network, and the fail-closed reading of "unknown egress"
        // is "egress". If outbound network is ever provably blocked, narrow this
        // with a link to the evidence - do not narrow it on the assumption that a
        // sandbox implies no network, which is the assumption that makes container
        // isolation answer the wrong question.
        exact.put(
                "run_code",
                new ToolClassification(
                        "run_code",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));

        // ---- The Assistant's terminal (personal installs; core/agents-tools localshell)
        // -------------
        // local_shell runs ANY command on the host, inside the OS sandbox (read-only filesystem
        // except a
        // workspace folder, credential folders hidden, no network unless the owner allowed it).
        // Same
        // fail-closed reading as run_code: unknown egress is egress. Irreversible, so the
        // classification
        // gate asks the person before every call, and it is on AgentToolGuard.ALWAYS_CONFIRM_TOOLS
        // so a
        // fully autonomous agent still asks. Its output is untrusted (a file or a fetched page can
        // say
        // anything), so it also taints the turn.
        exact.put(
                "local_shell",
                new ToolClassification(
                        "local_shell",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
        // local_shell_read only accepts an allow-list of inspection commands (no redirects,
        // substitution or
        // chaining), so it changes nothing and needs no approval. TENANT_SCOPED rather than
        // UNTRUSTED_EXTERNAL is a judgement call, like google_drive: the files are the owner's own,
        // and the
        // sandbox hides credential folders. Revisit if the Assistant starts reading a folder it
        // downloads
        // into (a downloaded file is prompt content like any other text).
        exact.put(
                "local_shell_read",
                new ToolClassification(
                        "local_shell_read",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));

        // ---- The Assistant's desktop tools (personal installs; core/agents-tools desktop) ----
        // The local_desktop_* actuating tools (press/click/type/key/open/activate) drive the real
        // GUI through the desktop-agent sidecar - UNCONFINED, unlike local_shell. Irreversible and
        // consequential, so the classification gate parks every call; they are additionally pinned
        // by AgentToolGovernance's desktop always-confirm list while
        // app.assistant.desktop.approval-required holds, so even a fully autonomous agent asks.
        for (String t :
                new String[] {
                    "local_desktop_press", "local_desktop_click", "local_desktop_type",
                    "local_desktop_key", "local_desktop_open", "local_desktop_activate"
                }) {
            exact.put(
                    t,
                    new ToolClassification(
                            t,
                            ToolTrustTier.UNTRUSTED_EXTERNAL,
                            ToolSensitivity.WRITE_TENANT,
                            ToolDeterminism.DURABLE,
                            null,
                            true,
                            true));
        }
        // The read side sees whatever apps show on screen - third-party content like a fetched
        // page, so it taints the turn (UNTRUSTED_EXTERNAL) while needing no approval itself.
        exact.put(
                "local_desktop_screen",
                new ToolClassification(
                        "local_desktop_screen",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "local_desktop_elements",
                new ToolClassification(
                        "local_desktop_elements",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // notify and clipboard (read or set) are the owner's own machine surface - writes that
        // change nothing outside it, so no approval, same judgement as workspace_write.
        exact.put(
                "local_desktop_notify",
                new ToolClassification(
                        "local_desktop_notify",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        exact.put(
                "local_desktop_clipboard",
                new ToolClassification(
                        "local_desktop_clipboard",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // The Assistant's file tools (core/agents-tools workspacefiles): confined to the person's
        // projects
        // folder. Reads are the owner's own files (same judgement as local_shell_read). Writes
        // change only
        // that folder, which the person attached for this work, so they are WRITE_TENANT and NOT
        // irreversible: they do not ask before each call (commands still do, through local_shell).
        exact.put(
                "workspace_list",
                new ToolClassification(
                        "workspace_list",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "workspace_read",
                new ToolClassification(
                        "workspace_read",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "workspace_search",
                new ToolClassification(
                        "workspace_search",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "workspace_write",
                new ToolClassification(
                        "workspace_write",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        exact.put(
                "workspace_edit",
                new ToolClassification(
                        "workspace_edit",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // The agent scratchpad (core/agents-tools scratchpad): the agent's own key-value notes,
        // session-scoped or agent-scoped. Internal tenant state - a note only ever touches this
        // agent's pad, so nothing here asks first. Reads/list return note text the agent itself
        // wrote earlier (which can carry injection harvested from earlier tool output), so the
        // results stay on the normal untrusted-content screen, not the platform-answer bypass;
        // TENANT_SCOPED rather than UNTRUSTED_EXTERNAL on the same judgement as workspace_read.
        // The write pair is DURABLE: a note persists past the call.
        exact.put(
                "scratchpad_write",
                new ToolClassification(
                        "scratchpad_write",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        exact.put(
                "scratchpad_read",
                new ToolClassification(
                        "scratchpad_read",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "scratchpad_list",
                new ToolClassification(
                        "scratchpad_list",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "scratchpad_delete",
                new ToolClassification(
                        "scratchpad_delete",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // The Assistant's platform guides: read the bundled docs/catalogue and the generated
        // OpenAPI spec
        // and report setup state; they change nothing and call nothing.
        exact.put(
                "vegaduta_setup_guide",
                new ToolClassification(
                        "vegaduta_setup_guide",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "vegaduta_api_guide",
                new ToolClassification(
                        "vegaduta_api_guide",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));

        // ---- code-intel: read side ------------------------------------------
        // TENANT_SCOPED: a tenant's own indexed repository. Judgement call, and
        // the case that would change it is a repository accepting outside
        // contributions (a public fork, a vendored dependency) - source code in a
        // comment is prompt content like any other text.
        exact.put(
                "search_code",
                new ToolClassification(
                        "search_code",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "code_callers",
                new ToolClassification(
                        "code_callers",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "code_callees",
                new ToolClassification(
                        "code_callees",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "code_impact",
                new ToolClassification(
                        "code_impact",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "code_graph_stats",
                new ToolClassification(
                        "code_graph_stats",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "diff_code_workspace",
                new ToolClassification(
                        "diff_code_workspace",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));

        // ---- code-intel: write / execute side -------------------------------
        // apply_code_changes and run_in_code_workspace are already the two action
        // types ApprovalPolicyService gates by default
        // ("codeintel.workspace.apply", "codeintel.workspace.run"). These rows say
        // the same thing structurally, which is the migration the agenda calls for:
        // the glob pattern and the classification now agree, and the
        // classification survives a rename.
        exact.put(
                "create_code_workspace",
                new ToolClassification(
                        "create_code_workspace",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        exact.put(
                "delete_code_workspace",
                new ToolClassification(
                        "delete_code_workspace",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
        exact.put(
                "apply_code_changes",
                new ToolClassification(
                        "apply_code_changes",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
        // Arbitrary shell in a workspace: same unknown-egress reasoning as
        // run_code above.
        exact.put(
                "run_in_code_workspace",
                new ToolClassification(
                        "run_in_code_workspace",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));

        // ---- Coding harness (app.coding-harness.*, default OFF) --------------------
        // coding_task starts an isolated sandbox, clones a repository the model named from the
        // public web,
        // runs its build/test tooling there and sends repository content to the model provider.
        // EGRESS (the
        // call itself reaches the network: git clone, model calls, sandbox API) and
        // UNTRUSTED_EXTERNAL (the
        // repository and its test output are attacker-authorable, and the tool returns a diff
        // derived from
        // them). DURABLE because every call spends sandbox time and tokens and a blind retry would
        // start a
        // second task. irreversible = true on purpose: with EGRESS that is what makes
        // requiresHumanConfirmation() true, and the tool is also on
        // AgentToolGuard.ALWAYS_CONFIRM_TOOLS, so
        // neither the confirmation-gate opt-out, the exempt list nor a FULLY_AUTONOMOUS tier can
        // skip the
        // prompt. That is only the START of the task: separately, the harness always waits for a
        // person to
        // approve the exact diff on the Coding tasks page, and no tool parameter or setting can
        // answer it.
        exact.put(
                "coding_task",
                new ToolClassification(
                        "coding_task",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));

        BuiltInToolClassificationsTail.declare(exact);
    }
}

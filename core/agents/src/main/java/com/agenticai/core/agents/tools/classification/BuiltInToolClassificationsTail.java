package com.agenticai.core.agents.tools.classification;

import java.util.Map;

/**
 * Last part of the {@link BuiltInToolClassifications} declaration table (media / document
 * generation onwards), split from {@link BuiltInToolClassificationsExtended} to keep each file
 * under the line-count cap. The conventions stated on {@link BuiltInToolClassifications} apply to
 * every entry here; insertion order is part of the contract because {@link
 * BuiltInToolClassifications#all()} reports in it, so this {@code declare} runs straight after the
 * extended table's.
 */
final class BuiltInToolClassificationsTail {

    private BuiltInToolClassificationsTail() {
        // static declaration table
    }

    /** Appends the media-onwards declarations to {@code exact}, in declaration order. */
    static void declare(Map<String, ToolClassification> exact) {
        // ---- Media / document generation ------------------------------------
        // DURABLE because each call spends real provider money and persists an
        // artifact to object storage; a blind retry bills twice. Not irreversible:
        // a generated artifact can be deleted, and it is not delivered anywhere
        // until forward_attachment sends it - which is exactly why that tool, and
        // not these, carries the EGRESS label.
        exact.put(
                "generate_image",
                new ToolClassification(
                        "generate_image",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        exact.put(
                "generate_video",
                new ToolClassification(
                        "generate_video",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        exact.put(
                "generate_document",
                new ToolClassification(
                        "generate_document",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        exact.put(
                "generate_music",
                new ToolClassification(
                        "generate_music",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        exact.put(
                "speak",
                new ToolClassification(
                        "speak",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // ---- Sarvam language tools ------------------------------------------
        // CREDENTIALED by the definition in ToolSensitivity: each call acts with
        // the tenant's stored Sarvam API key. Honest consequence, stated rather
        // than dodged: this makes "fetch a page, then translate it" a taint-gated
        // flow, which is a FALSE POSITIVE for the legitimate use of that pair. It
        // is also precisely why the agenda mandates running the taint gate in
        // shadow mode - logging would-be blocks without enforcing - for two weeks
        // before enforcement, and why the default escalation is human approval
        // rather than hard denial. Do not "fix" this by demoting the label; fix it
        // with the shadow-mode data.
        exact.put(
                "sarvam_translate",
                new ToolClassification(
                        "sarvam_translate",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.CREDENTIALED,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "sarvam_transliterate",
                new ToolClassification(
                        "sarvam_transliterate",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.CREDENTIALED,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "sarvam_transcribe",
                new ToolClassification(
                        "sarvam_transcribe",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.CREDENTIALED,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "sarvam_identify_language",
                new ToolClassification(
                        "sarvam_identify_language",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.CREDENTIALED,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));

        // ---- Mobile device reads --------------------------------------------
        // Screen contents, notifications and SMS are all third-party-authored:
        // whatever app or stranger produced them chose the text. App-usage
        // statistics are platform-computed metadata about which apps ran, with no
        // foreign content, so that one stays TENANT_SCOPED.
        exact.put(
                "read_device_screen",
                new ToolClassification(
                        "read_device_screen",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "read_device_notifications",
                new ToolClassification(
                        "read_device_notifications",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "read_device_sms",
                new ToolClassification(
                        "read_device_sms",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "read_device_app_usage",
                new ToolClassification(
                        "read_device_app_usage",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));

        // ---- Browser-use fallback (2026-09-23) ------------------------------
        // browser_navigate/browser_read_page_text/browser_screenshot are the
        // read side: UNTRUSTED_EXTERNAL for the identical reason fetch_web_page
        // is (whatever the session loads is third-party content, not tenant
        // data) and READ_PUBLIC for the identical reason too - the tool call
        // itself reveals nothing about the tenant, even though the browser
        // reaches destinations the caller chooses. See BrowserUseUrlValidator
        // for the localhost/private-IP egress denylist that bounds where
        // "caller chosen" can point.
        exact.put(
                "browser_navigate",
                new ToolClassification(
                        "browser_navigate",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_PUBLIC,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "browser_read_page_text",
                new ToolClassification(
                        "browser_read_page_text",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_PUBLIC,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "browser_screenshot",
                new ToolClassification(
                        "browser_screenshot",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_PUBLIC,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // browser_click/browser_type are the write side, and get the SAME
        // treatment as channel_send_message/forward_attachment/
        // channel_session_action above: EGRESS + irreversible. A click can
        // submit a form, confirm a purchase, or send a message on whatever
        // site the session is driving, and cannot be undone by this platform;
        // the selector/description argument can itself be steered by page
        // content the session already read via browser_read_page_text
        // (an injected instruction on the page telling the model what to
        // click), which is exactly the taint-sink shape UNTRUSTED_EXTERNAL +
        // EGRESS is declared to catch.
        exact.put(
                "browser_click",
                new ToolClassification(
                        "browser_click",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
        exact.put(
                "browser_type",
                new ToolClassification(
                        "browser_type",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
        // browser_totp (app.browser.totp.enabled): computes the CURRENT 2FA code from a vault TOTP
        // seed. A
        // tenant-scoped read of a credential - the seed never leaves the server, only a 30 s code
        // does -
        // so CREDENTIALED. Declared irreversible on purpose even though nothing is changed: that is
        // the lever
        // that puts it behind the human-confirmation gate (on by default, and deliberately NOT in
        // the exempt
        // list), because a code is a one-shot login credential and the agent should not mint them
        // unattended.
        exact.put(
                "browser_totp",
                new ToolClassification(
                        "browser_totp",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.CREDENTIALED,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        true,
                        true));
        // run_browser_recipe (app.browser.recipes.enabled): replays a recorded click/type sequence
        // against live
        // third-party sites with vault credentials resolved server-side. Same shape as
        // browser_click/browser_type
        // (UNTRUSTED_EXTERNAL EGRESS, DURABLE, irreversible: a replayed step can submit a form)
        // and, unlike them,
        // NOT in the confirmation-gate exempt list - the whole replay is approved once, arguments
        // included, and
        // steps flagged irreversible inside the recipe additionally need confirmIrreversible=true.
        exact.put(
                "run_browser_recipe",
                new ToolClassification(
                        "run_browser_recipe",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
        // run_desktop_recipe (app.assistant.desktop.recipes.enabled): replays a recorded
        // click/type/open
        // sequence on the person's own screen through the desktop-agent sidecar - the
        // local_desktop_* twin of
        // run_browser_recipe, so it is classified IDENTICALLY: a replayed step can submit a form,
        // confirm a
        // purchase or delete a file (EGRESS, DURABLE, irreversible), and it is deliberately NOT in
        // the
        // confirmation-gate exempt list - the whole replay is approved once, arguments included,
        // and steps
        // flagged irreversible inside the recipe additionally need confirmIrreversible=true. It is
        // also on
        // DesktopToolSource.GATED_NAMES, so app.assistant.desktop.approval-required pins it for a
        // fully
        // autonomous agent exactly like the single actuating tools.
        exact.put(
                "run_desktop_recipe",
                new ToolClassification(
                        "run_desktop_recipe",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));

        // ---- Browser agent v2 (app.browser.v2.enabled, default off) ----------
        // Read side: same classification as browser_read_page_text. snapshot/extract_text return
        // third-party page content (tainted); scroll and go_back only move the viewport / history
        // (every
        // request still passes the SSRF guard and the domain policy).
        for (String readTool :
                new String[] {
                    "browser_snapshot", "browser_extract_text", "browser_scroll", "browser_go_back"
                }) {
            exact.put(
                    readTool,
                    new ToolClassification(
                            readTool,
                            ToolTrustTier.UNTRUSTED_EXTERNAL,
                            ToolSensitivity.READ_PUBLIC,
                            ToolDeterminism.SIDE_EFFECT,
                            null,
                            false,
                            true));
        }
        // Write side: a click / typed text / chosen option on a page is EGRESS (it reaches whatever
        // site the
        // session drives) and DURABLE, but NOT irreversible by default - unlike the legacy
        // browser_click, which
        // is irreversible for every click and therefore sits on the confirmation-gate exempt list.
        // The gate
        // works on tool NAMES, so the irreversible case is a separate tool: browser_click_element
        // REFUSES a
        // click whose server-recorded role/name looks like buy|pay|confirm|submit|delete|send (see
        // BrowserIrreversibleClick) and points the model at browser_confirm_click, which is
        // irreversible and
        // therefore requires human confirmation. browser_confirm_click must NOT be added to
        // app.tools.classification.confirmation-gate-exempt-tools.
        for (String writeTool :
                new String[] {
                    "browser_click_element", "browser_type_into", "browser_select_option"
                }) {
            exact.put(
                    writeTool,
                    new ToolClassification(
                            writeTool,
                            ToolTrustTier.UNTRUSTED_EXTERNAL,
                            ToolSensitivity.EGRESS,
                            ToolDeterminism.DURABLE,
                            null,
                            false,
                            true));
        }
        exact.put(
                "browser_confirm_click",
                new ToolClassification(
                        "browser_confirm_click",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));

        // browser_request_user (app.browser.takeover.enabled, default off): asks the PERSON to take
        // over the browser
        // (captcha / 2FA / login). It moves no money, sends nothing and changes nothing on a site -
        // it only pauses the
        // agent and notifies the user - so it is not irreversible and needs no confirmation of its
        // own. Its result
        // carries page text read back after redaction, which is third-party content
        // (UNTRUSTED_EXTERNAL, READ_PUBLIC,
        // the same as browser_read_page_text).
        exact.put(
                "browser_request_user",
                new ToolClassification(
                        "browser_request_user",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_PUBLIC,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));

        // ---- Commerce -------------------------------------------------------
        // create_payment_link moves money. Its own tool description already says
        // "This action moves money and normally requires a human's approval
        // first"; irreversible = true is that sentence expressed as data, so the
        // gate does not depend on the model having read the description. Note it
        // returns the link rather than delivering it, so CREDENTIALED (acts with
        // the tenant's gateway credential) is the accurate label, not EGRESS.
        exact.put(
                "create_payment_link",
                new ToolClassification(
                        "create_payment_link",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.CREDENTIALED,
                        ToolDeterminism.DURABLE,
                        null,
                        true,
                        true));
        exact.put(
                "check_payment_status",
                new ToolClassification(
                        "check_payment_status",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.CREDENTIALED,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));

        // "Crew"-style multi-agent handoff (chat-time, capability/role-based - see
        // com.agenticai.core.agents.crew.HandoffChain's javadoc for how this differs from the
        // ask_agent_* mechanism above). Unlike that one, delegate_to_specialist is a single,
        // fixed tool name (its target is chosen by a "capability" ARGUMENT, not baked into the
        // tool name), so it gets an EXACT entry rather than a wildcard pattern.
        //
        // Same classification as ask_agent_* and for the identical reason stated on that pattern
        // above: a handoff's effective trust is only as good as the specialist's own bound tools,
        // which this class cannot see, so UNTRUSTED_EXTERNAL is the fail-closed assumption, and
        // WRITE_TENANT is the provable floor (a specialist can write via its own memory tools),
        // not a claim about what it might additionally do.
        exact.put(
                "delegate_to_specialist",
                new ToolClassification(
                        "delegate_to_specialist",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // ---- First-party tools that ingest outside content or hand work to sub-agents -----------
        // Without an entry these were "unclassified": a SINK but never a SOURCE, so search
        // snippets, a browser agent's summary of hostile pages and sub-agent output never tainted a
        // turn (and a skill read after a web fetch asked for approval as an unknown tool).
        //
        // web_search returns third-party snippets and titles: the same ingress as fetch_web_page.
        exact.put(
                "web_search",
                new ToolClassification(
                        "web_search",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_PUBLIC,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // web_task runs a browser agent: it reads hostile pages (UNTRUSTED_EXTERNAL) and can act on
        // the sites it visits (EGRESS, DURABLE: not safe to re-run). Not declared irreversible, so
        // the always-on confirmation prompt is unchanged; the taint gate is what routes it.
        exact.put(
                "web_task",
                new ToolClassification(
                        "web_task",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.EGRESS,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        // delegate_parallel fans a request out to team members: identical to
        // delegate_to_specialist.
        exact.put(
                "delegate_parallel",
                new ToolClassification(
                        "delegate_parallel",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        // run_subtasks fans independent briefs out to throwaway copies of the calling agent: same
        // tier as delegate_parallel, since each branch can read the web.
        exact.put(
                "run_subtasks",
                new ToolClassification(
                        "run_subtasks",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        // create_workflow saves a DRAFT workflow generated from the instruction: nothing is
        // activated,
        // scheduled or run, and the person deletes it like any draft, so it is reversible (no human
        // confirmation, like data_table_write). WRITE_TENANT makes it a taint sink, so text from a
        // web
        // page cannot quietly plant a draft; DURABLE because a blind retry would save a second one.
        exact.put(
                "create_workflow",
                new ToolClassification(
                        "create_workflow",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));
        // Past messages include inbound channel text from outsiders, like recall_related_facts.
        exact.put(
                "search_conversation_history",
                new ToolClassification(
                        "search_conversation_history",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // Skill instructions are the point of load_skill: the model is meant to follow them, which
        // spotlighting them as untrusted data would defeat. Custom skills pass the review lifecycle
        // (PENDING until a person approves) and imports pass the supply-chain scan, so they are
        // tenant-scoped reads, not a taint source. Declared so a skill load after a web fetch is
        // not
        // an "unknown tool" approval.
        exact.put(
                "load_skill",
                new ToolClassification(
                        "load_skill",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        exact.put(
                "read_skill_file",
                new ToolClassification(
                        "read_skill_file",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // A skill script runs code and returns whatever it produced: a source and a sink.
        exact.put(
                "run_skill_script",
                new ToolClassification(
                        "run_skill_script",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.WRITE_TENANT,
                        ToolDeterminism.DURABLE,
                        null,
                        false,
                        true));

        // ---- On-demand tool loading (app.tools.search.*, default OFF) --------
        // search_tools only ranks the tool definitions this agent already holds and returns their
        // names, one-line descriptions and schemas - exactly what the model would have seen had the
        // tool list carried them, so it adds no taint source (TENANT_SCOPED: the descriptions of
        // tenant-registered MCP and custom tools are tenant data, already clamped at discovery) and
        // reveals nothing new (READ_TENANT). Not irreversible, no side effect beyond remembering
        // which tools were searched for.
        exact.put(
                "search_tools",
                new ToolClassification(
                        "search_tools",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
        // call_tool is a dispatcher with no action of its own. It invokes the SAME guard-wrapped
        // callback the model would have called directly, so the target is classified, approved,
        // confirmed, journaled and logged under its OWN name and arguments (a parked approval
        // replays
        // as the real tool). Declaring this entry at the target's worst case, as the "most
        // sensitive
        // reachable action" convention above would for a multi-action tool, would only make every
        // call confirm twice (once here, once on the target) without protecting anything extra; the
        // gate that matters runs on the target. Judgement call, and the one entry in this table
        // whose label deliberately describes the wrapper rather than its reach.
        exact.put(
                "call_tool",
                new ToolClassification(
                        "call_tool",
                        ToolTrustTier.TENANT_SCOPED,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));

        // ---- Context compaction (app.context.compaction.*, default OFF) -------
        // restore_tool_result re-reads the stored output of an earlier tool call of THIS run
        // (tool_call_log,
        // bound to the calling agent + session, in the tenant's own schema) - so READ_TENANT,
        // read-only,
        // not irreversible. It is UNTRUSTED_EXTERNAL on purpose, like recall_related_facts: the
        // stored
        // output may be what fetch_web_page or an MCP server returned, and a restore that came back
        // as
        // "tenant data" would launder that taint past the lethal-trifecta check (taint that does
        // not
        // survive a round trip through storage is not taint tracking). Over-taints a restore of a
        // trusted
        // tool's output, which is the conservative direction.
        exact.put(
                "restore_tool_result",
                new ToolClassification(
                        "restore_tool_result",
                        ToolTrustTier.UNTRUSTED_EXTERNAL,
                        ToolSensitivity.READ_TENANT,
                        ToolDeterminism.SIDE_EFFECT,
                        null,
                        false,
                        true));
    }
}

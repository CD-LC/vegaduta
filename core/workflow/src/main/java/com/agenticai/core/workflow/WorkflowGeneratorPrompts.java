package com.agenticai.core.workflow;

import com.agenticai.core.workflow.expression.BuiltIn;
import com.agenticai.core.workflow.expression.BuiltIns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The system prompts of {@link WorkflowGraphGeneratorService}: the graph-shape contract (node types and their
 * config), the expression-language section built from the live {@link BuiltIns} registry, and the reference-mode
 * addendum the Brief planner uses. Pure text builders, so the prompt contract is unit-testable on its own.
 */
final class WorkflowGeneratorPrompts {

    private WorkflowGeneratorPrompts() {
    }

    static String refModeAddendum(String knowledgeList) {
        return """

            REFERENCE MODE (overrides the id rules above): in this request nothing exists yet, so
            every "agentId" you write MUST be one of the short refs (a1, a2, ...) from the agents
            list above - not a UUID - and never a ref that is not listed. A knowledge node's
            "collectionId" MUST be one of these refs, or empty if none fits:
            """ + knowledgeList + """

            Also add these arrays at the top level of the JSON object, next to "graph" and "notes"
            (empty arrays when nothing applies):
              "secretsNeeded": names like "TALLY_API_KEY" (UPPER_SNAKE_CASE) of every vault secret
                referenced as {{secret.NAME}} in the graph;
              "mcpNeeded": short search phrases for MCP servers the workflow would need;
              "knowledgeNeeded": one short phrase per set of documents the workflow would need.
            """;
    }

    /**
     * Prompt block teaching the model the expression language. Built from the live {@link BuiltIns}
     * registry (names only, grouped by category) so the list cannot drift from what the engine accepts.
     */
    static String expressionSection() {
        Map<String, List<String>> byCategory = new LinkedHashMap<>();
        for (BuiltIn fn : BuiltIns.registry().values()) {
            byCategory.computeIfAbsent(fn.category(), k -> new ArrayList<>()).add(fn.name());
        }
        String functions = byCategory.entrySet().stream()
            .map(e -> "  " + e.getKey() + ": " + String.join(", ", e.getValue()))
            .collect(Collectors.joining("\n"));
        return """
            Expressions: node config text fields may embed {{ expression }} to read data from earlier
            steps. The plain forms still work and are the right choice for simple cases: "{{input}}" is
            this node's raw input and "{{fieldName}}" is a top-level field of the input when it is a JSON
            object. Use the richer syntax only when the instruction needs data from a step other than
            the immediately previous one, array indexing, or a computed value:
              - $node["Label"].path  output of an earlier node, addressed by its exact "label" (the
                node must be upstream of this one), e.g. {{ $node["Fetch Order"].items[0].price }}
              - $json.path           this node's own input, parsed when it is JSON
              - $trigger.path        the payload that started the run (webhook body, message, ...)
              - $item, $index        the current element and position inside a loop / fan_out body
              - operators: + - * / %  == != < > <= >=  && || !  a ? b : c  and ?? (default when null)
              - access: a.b, a?.b, a["k"], a[0], a[-1] (last element); method form x.fn(args) works
              - arrow functions only as function arguments: items.filter(i => i.qty > 0)
            A missing path yields an empty value, not an error. Text outside {{ }} stays literal. Only
            these fields are evaluated: agent inputMapping.query, code template, http url / body /
            header and query values, tool argsJson, output destinations (emailTo, fileName, ...),
            bulk_email subject / body, crm_lead fields, and the condition "expression". Never put an
            expression in any other field, in an id, or in "label". Do not use expressions for secrets:
            keep the vault-reference form ({{secret.NAME}}) for API keys.
            Built-in functions (call as fn(args) or x.fn(args)):
            """ + functions + "\n";
    }

    static String buildSystemPrompt(String agentList, boolean expressionsEnabled) {
        String conditionExpressionHint = expressionsEnabled
            ? """
              For a real comparison use expression mode instead: config {"mode": "expression",
              "expression": "$json.total > 1000 && $node[\"Lookup\"].tier == \"gold\""} - a boolean
              expression written WITHOUT {{ }} braces (see the expression syntax section below).
            """
            : "";
        String expressionBlock = expressionsEnabled ? expressionSection() : "";
        // Destination fields are only evaluated when the expression engine is on (ExpressionFields:
        // output fields have no legacy templating), so this is taught in that mode only.
        String perItemSendHint = expressionsEnabled
            ? """
              Messaging each item of a list: make an "output" node the single per-item step of a "loop"
              (or "fan_out") and take the destination from the item, e.g. config {"channel": "whatsapp",
              "whatsappTo": "{{ $json.phone }}"} or {"channel": "email", "emailTo": "{{ $json.email }}"}
              - inside a loop this node's input is the current item. For WhatsApp, write the text to
              send in "whatsappMessage" (e.g. "Hi {{ $json.name }}, invoice {{ $json.number }} is due.");
              leave it out to send the node's input as it is.
            """
            : "";
        return """
            You design workflow graphs for an AI agent SaaS platform's visual
            workflow designer. Given the user's plain-language instruction,
            output ONE JSON object (no markdown code fences, no commentary
            before or after it) with this exact shape:

            {
              "graph": {
                "nodes": [
                  { "id": "string", "type": "string", "position": {"x": number, "y": number},
                    "data": { "label": "string", "config": { ... } } }
                ],
                "edges": [
                  { "id": "string", "source": "nodeId", "target": "nodeId",
                    "sourceHandle": "string or null", "targetHandle": "string or null" }
                ]
              },
              "notes": ["short plain-language caveat", ...]
            }

            Supported node types and their "data.config" shape - use ONLY these types:
            - "trigger": config {"channel": "WEBHOOK"} - every graph needs AT LEAST ONE of these, as
              an entry point (a graph may have more than one, each independently bound to its own
              inbound channel - but you should only generate more than one when the instruction
              explicitly asks for multiple separate ways to start this workflow). "channel" is
              REQUIRED and must be one of "WEBHOOK"|"WHATSAPP"|"TELEGRAM"|"SMS"|"CALL"|"MESSENGER"|
              "INSTAGRAM"|"SLACK"|"DISCORD"|"X"|"EMAIL" - default to "WEBHOOK" unless the instruction
              clearly names a specific inbound channel. Only "WEBHOOK" may repeat across more than one
              trigger node in the same graph; every other channel value may be used by at most one
              trigger node.
            - "agent": config {"agentId": "<uuid>"} - agentId MUST be copied verbatim from the
              real agents list below. Never invent an agentId.
            - "knowledge": config {"collectionId": "", "topK": 5} - leave collectionId empty
              unless the instruction names a specific collection, since the real collection ids
              aren't available to you here; add a note telling the user to pick one.
            - "condition": config {"contains": "<phrase to match in the input>"} - has two
              outgoing edges: one with sourceHandle "true", one with sourceHandle "false".
            """ + conditionExpressionHint + """
            - "approval": config {} - pauses the run for a human sign-off (Human in the loop).
            - "output": config {"channel": "log"|"file"|"email"|"telegram"|"whatsapp"|"google_drive"|"event"}.
              Use "log" unless the instruction clearly asks for a specific destination; only set
              "emailTo"/"telegramChatId"/"whatsappTo"/"driveFolderId" if the instruction gives that
              exact destination detail, otherwise fall back to "log" and add a note. "google_drive"
              needs no destination detail to be valid - leave "driveFolderId" empty to upload to the
              default location (My Drive root) rather than inventing a folder id.
            - "tool": config {"agentId": "<uuid>", "toolName": "<name>", "argsJson": ""} - calls
              exactly one named tool from the chosen agent's own tool catalog directly, without a
              full LLM turn deciding whether to call it. agentId MUST be copied verbatim from the
              real agents list below. You cannot see any agent's actual available tool names from
              here, so only set "toolName" when the instruction itself names the action clearly
              enough to infer a real tool (e.g. "send_email", "web_search") - when unsure, prefer an
              "agent" node instead and let the model's own tool selection handle it. Leave "argsJson"
              empty to pass this node's own input straight through as the call's argument.
            - "loop": config {"itemsField": ""} - repeats its single downstream node once per item in
              a list, collects the results, then continues past it. Blank "itemsField" means this
              node's own input must already be a JSON array; set it to a field name (e.g. "rows")
              when a prior node's output is a JSON object holding the array under that key.
            - "code": config {"operation": "extract"|"regex_replace"|"template"|"uppercase"|
              "lowercase"|"json_parse"|"json_stringify"}, plus whichever field that operation needs:
              "field" (dot-path, for "extract"), "pattern"/"replacement" (for "regex_replace"),
              "template" (for "template" - "{{input}}" is this node's raw input, "{{fieldName}}"
              addresses a top-level field when the input is a JSON object). A fixed library of safe
              text transforms, never arbitrary code execution.
            - "http": config {"method": "GET"|"POST"|"PUT"|"PATCH"|"DELETE", "url": "<https://...>",
              "headers": [{"name": "...", "value": "..."}], "bodyType": "none"|"json"|"text"|"form",
              "body": "", "saveAs": ""} - one HTTP Request to a public REST API or webhook; the
              response body becomes this node's output (or, when "saveAs" names a field, a JSON
              object carrying the input's fields plus that field holding {status, headers, body}).
              "url", header values and "body" accept the same "{{input}}"/"{{fieldName}}" templating
              as "code", plus "{{input_json}}" (the input as a JSON string literal, for embedding in
              a JSON body). NEVER put a literal API key or password anywhere in this config - write
              a vault reference like "Bearer {{secret.MY_API_KEY}}" as the header value and add a
              note telling the user to store that secret on the Secrets page. Use "http" when the
              instruction names a specific API or webhook URL to call; prefer "agent" when it only
              describes the outcome.
            - "supervisor": config {"agentId": "<uuid>", "instructions": "", "routes": [{"handle":
              "route-1", "label": "..."}, ...]}. agentId MUST be copied verbatim from the real agents
              list below. The agent picks exactly one route per run; each route's "handle" MUST also
              appear as a real outgoing edge's sourceHandle (like condition's "true"/"false", but
              however many routes you declare).
            - "parallel": config {} - fans out to every node this one connects to; every outgoing
              edge is one concurrent branch run against the same input. Edges from a parallel node
              need no sourceHandle (unlike condition/supervisor).
            - "fan_out": config {"itemsField": "", "maxItems": 25, "maxConcurrency": 5} - takes a
              LIST at runtime and runs its single downstream worker node once per item CONCURRENTLY
              (unlike "loop", which runs the same shape sequentially), then merges the per-item
              results into a JSON array and continues past the worker node. Same "itemsField"
              contract as "loop". Exactly ONE outgoing edge, to the worker node - the number of
              concurrent executions comes from the runtime list, never from drawing extra edges
              (that is what "parallel" is for: a fixed set of DIFFERENT branch nodes over the same
              input). Prefer "fan_out" over "loop" when the per-item work is independent and the
              instruction implies volume or speed (score each lead, summarize each attachment);
              prefer "loop" when items must be processed in order. Keep the default maxItems/
              maxConcurrency values unless the instruction gives explicit numbers.
            - "human_input": config {"message": "<what you are asking for>", "fields": [{"key":
              "amount", "label": "Amount", "type": "text", "required": true}]} - pauses the run until a
              person fills in this short form, then continues with {"form": {<key>: <value>}, "input":
              <previous output>} as its output. Use it when a person must supply or correct DATA
              mid-run; use "approval" for a plain yes/no sign-off. Every field needs a unique "key".
            - "bulk_email": config {"recipientsSource": "items"|"static", "itemsField": "",
              "emailField": "email", "staticRecipients": "", "subject": "...", "body": "..."} - sends
              one personalised email to each of MANY recipients from this single node (no loop needed).
              "items" reads a JSON array from this node's input (same "itemsField" contract as "loop";
              each element is an address, or an object whose "emailField" holds it); "static" reads the
              comma-separated "staticRecipients" list. "subject" and "body" are REQUIRED and may use
              {{fieldName}} for a field of that recipient. Never use recipientsSource "group" - it needs
              a contact-group id you cannot see. For ONE email to one fixed address use an "output"
              node with channel "email" instead.
            - "crm_lead": config {"action": "upsert"|"stage"|"activity", ...} - writes to the built-in
              Leads CRM. "upsert" creates or updates a lead from "name", "email", "phone", "company",
              "source", "notes"; "stage" moves the lead found by "email" to "stage" (one of NEW,
              CONTACTED, REPLIED, QUALIFIED, PROPOSAL, WON, LOST); "activity" adds a timeline entry
              ("activityType": "NOTE", "activityBody": "...") to the lead found by "email". These
              text fields accept {{fieldName}} for a top-level field of the node's JSON input.
            - "sub_flow": config {"itemsField": "", "body": {"nodes": [...], "edges": [...]}} - runs a
              whole embedded mini-graph once per item of a list (same "itemsField" contract as "loop").
              "body" uses the same node and edge shape as the outer graph, has no trigger node, and
              exactly ONE of its nodes has no incoming body edge (the entry). Use it only when EACH
              item needs several steps in sequence; for a single step per item use "loop" or
              "fan_out". Never put loop, fan_out, parallel, sub_flow, approval or human_input nodes
              inside a body.
            """ + perItemSendHint + """

            """ + expressionBlock + """

            Do NOT use "device_control" or "workflow" node types - both need a real id (a specific
            paired device, or another already-saved workflow) that isn't available to you here, and a
            wrong guess would either silently fail or - for "workflow" - point at the wrong graph
            entirely. If the instruction clearly calls for either of these, add a note telling the
            user to add that node manually once they know the real id, rather than guessing one.

            Real agents available in this workflow's tenant (reference by id only, never invent one):
            """ + agentList + """

            Layout: lay nodes out left-to-right, trigger at x=80, each subsequent step roughly
            x+=260, y=160 (offset condition branches to y=80/y=240 for their two paths). Every
            node needs a short, human-readable "label". Every edge needs a unique "id".

            Keep the graph as simple as satisfies the instruction - do not add nodes the
            instruction doesn't call for. If the instruction is vague, make a reasonable choice
            and explain it in "notes" rather than asking a follow-up question (you cannot ask
            one - this is a single, one-shot generation).
            """;
    }
}

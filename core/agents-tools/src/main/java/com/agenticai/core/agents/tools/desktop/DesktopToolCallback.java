package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.guardrails.ToolConfirmationDescriber;
import com.agenticai.core.json.JsonMappers;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One {@code local_desktop_*} tool over the desktop-agent sidecar (services/desktop-agent). The
 * agent process does the actual GUI work on the person's laptop; this callback only shapes each
 * tool's schema, validates its arguments, calls the matching endpoint and formats the reply.
 *
 * <p><b>Gating lives in the guard, not here.</b> The actuating actions (press/click/type/key/
 * open/activate) are classified irreversible + consequential in {@code
 * BuiltInToolClassificationsExtended} and pinned by {@code AgentToolGovernance#alwaysConfirm} while
 * {@code app.assistant.desktop.approval-required} is true - an unconfined action is a bigger ask
 * than a sandboxed command, so it has its own dial. Read actions (screenshot, element tree,
 * clipboard get) and notifications need no approval.
 */
public final class DesktopToolCallback implements ToolCallback, ToolConfirmationDescriber {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int SUMMARY_CHARS = 120;

    /** What the tool does at the desktop agent, how it is described, and the approval summary. */
    enum Action {
        SCREEN(
                "local_desktop_screen",
                "Take a screenshot of the whole screen and save it as a PNG on the laptop. Returns"
                        + " the saved path and its size - the person can open it, or you can open it"
                        + " for them with local_desktop_open. Use it to verify what an earlier action"
                        + " did.",
                "POST",
                "/screenshot",
                true),
        ELEMENTS(
                "local_desktop_elements",
                "List the interactive elements (buttons, fields, links, menus) of the frontmost"
                        + " app, or of `app` when given, with each element's role, label and on-screen"
                        + " position. The reliable way to act without guessing pixels: find the label,"
                        + " then press it with local_desktop_press or click its coordinates.",
                "POST",
                "/ui",
                true),
        PRESS(
                "local_desktop_press",
                "Press the first UI element whose label contains `label` in the frontmost app (or"
                        + " `app` when given), e.g. \"Save\", \"Send\", \"OK\". Prefer this over raw"
                        + " coordinates.",
                "POST",
                "/ui/press",
                false),
        CLICK(
                "local_desktop_click",
                "Click the screen at pixel coordinates `x`,`y` (`button` left or right). Prefer "
                        + "local_desktop_press when the target has a name.",
                "POST",
                "/click",
                false),
        TYPE(
                "local_desktop_type",
                "Type `text` with the keyboard into whatever has focus. Bring the right app or"
                        + " field to the front first (local_desktop_activate, local_desktop_click).",
                "POST",
                "/type",
                false),
        KEY(
                "local_desktop_key",
                "Press one named key: return, enter, tab, space, delete, escape, left, right, down,"
                        + " up or f1-f12.",
                "POST",
                "/key",
                false),
        OPEN(
                "local_desktop_open",
                "Open `target` - an app name, a file path or a URL - in the laptop's normal"
                        + " handler, or in `application` when given.",
                "POST",
                "/open",
                false),
        ACTIVATE(
                "local_desktop_activate",
                "Bring the named application to the front so follow-up typing and clicks land in"
                        + " it.",
                "POST",
                "/activate",
                false),
        NOTIFY(
                "local_desktop_notify",
                "Show the person a system notification with `title` and `body`. The quiet way to"
                        + " say a background job finished while they were away.",
                "POST",
                "/notify",
                true),
        CLIPBOARD(
                "local_desktop_clipboard",
                "Read the laptop's clipboard (no `text`), or replace its contents when `text` is"
                        + " given.",
                null,
                null,
                true);

        final String toolName;
        final String description;
        final String method;
        final String path;

        /** Read-only-ish or benign enough to need no approval. */
        final boolean benign;

        Action(String toolName, String description, String method, String path, boolean benign) {
            this.toolName = toolName;
            this.description = description;
            this.method = method;
            this.path = path;
            this.benign = benign;
        }
    }

    private static final Map<String, Action> BY_NAME = new LinkedHashMap<>();

    static {
        for (Action a : Action.values()) {
            BY_NAME.put(a.toolName, a);
        }
    }

    private final DesktopAgentClient client;
    private final Action action;
    private final boolean approvalRequired;

    public DesktopToolCallback(DesktopAgentClient client, Action action, boolean approvalRequired) {
        this.client = client;
        this.action = action;
        this.approvalRequired = approvalRequired;
    }

    Action action() {
        return action;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(action.toolName)
                .description(
                        action.description
                                + (approvalRequired && !action.benign
                                        ? " Each call is shown to the user for approval before it"
                                                + " runs."
                                        : ""))
                .inputSchema(schemaFor(action))
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode args = MAPPER.readTree(toolInput == null ? "{}" : toolInput);
            JsonNode reply;
            if (action == Action.CLIPBOARD) {
                String text = args.path("text").isString() ? args.path("text").asString() : null;
                reply =
                        text == null
                                ? client.get("/clipboard")
                                : client.post("/clipboard", Map.of("text", text));
            } else {
                Map<String, Object> body = new LinkedHashMap<>();
                args.properties().forEach(e -> body.put(e.getKey(), e.getValue()));
                reply = client.post(action.path, body);
            }
            return MAPPER.writeValueAsString(reply);
        } catch (java.io.IOException | JacksonException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            return error("The action could not run: " + e.getMessage());
        }
    }

    @Override
    public String describeForConfirmation(String toolInput) {
        try {
            JsonNode args = MAPPER.readTree(toolInput == null ? "{}" : toolInput);
            return switch (action) {
                case CLICK ->
                        "Click "
                                + args.path("button").asString("left")
                                + "-click at ("
                                + args.path("x")
                                + ", "
                                + args.path("y")
                                + ") on the screen";
                case PRESS ->
                        "Press the UI element labelled \""
                                + text(args, "label")
                                + "\""
                                + opt(args, "app", " in app ");
                case TYPE -> "Type into the frontmost app: " + abbreviate(text(args, "text"));
                case KEY -> "Press the " + text(args, "key") + " key";
                case OPEN -> "Open " + text(args, "target") + opt(args, "application", " with ");
                case ACTIVATE -> "Bring " + text(args, "app") + " to the front";
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static String schemaFor(Action a) {
        return switch (a) {
            case SCREEN ->
                    """
                    {"type":"object","properties":{}}
                    """;
            case ELEMENTS ->
                    """
{"type":"object","properties":{
  "app":{"type":"string","description":"Optional app name to inspect instead of the frontmost one"}
}}
""";
            case PRESS ->
                    """
{"type":"object","properties":{
  "label":{"type":"string","description":"Text on the element to press - a button's caption, menu item, link text"},
  "role":{"type":"string","description":"Optional accessibility role, e.g. AXButton"},
  "app":{"type":"string","description":"Optional app to act in instead of the frontmost one"}
},"required":["label"]}
""";
            case CLICK ->
                    """
{"type":"object","properties":{
  "x":{"type":"number","description":"Horizontal pixel from the screen's left edge"},
  "y":{"type":"number","description":"Vertical pixel from the top"},
  "button":{"type":"string","enum":["left","right"]}
},"required":["x","y"]}
""";
            case TYPE ->
                    """
                    {"type":"object","properties":{
                      "text":{"type":"string","description":"The exact text to type"}
                    },"required":["text"]}
                    """;
            case KEY ->
                    """
{"type":"object","properties":{
  "key":{"type":"string","description":"return|enter|tab|space|delete|escape|left|right|down|up|f1..f12"}
},"required":["key"]}
""";
            case OPEN ->
                    """
{"type":"object","properties":{
  "target":{"type":"string","description":"App name, file path or URL to open"},
  "application":{"type":"string","description":"Optional: open the target with this app"}
},"required":["target"]}
""";
            case ACTIVATE ->
                    """
                    {"type":"object","properties":{
                      "app":{"type":"string","description":"Application name, e.g. Safari"}
                    },"required":["app"]}
                    """;
            case NOTIFY ->
                    """
                    {"type":"object","properties":{
                      "title":{"type":"string"},
                      "body":{"type":"string"}
                    },"required":["title"]}
                    """;
            case CLIPBOARD ->
                    """
{"type":"object","properties":{
  "text":{"type":"string","description":"When set, replaces the clipboard contents instead of reading them"}
}}
""";
        };
    }

    private static String text(JsonNode args, String field) {
        return args.path(field).isString() ? args.path(field).asString() : "";
    }

    private static String opt(JsonNode args, String field, String prefix) {
        String v = text(args, field);
        return v.isEmpty() ? "" : prefix + v;
    }

    private static String abbreviate(String s) {
        String oneLine = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > SUMMARY_CHARS
                ? oneLine.substring(0, SUMMARY_CHARS) + "..."
                : oneLine;
    }

    private static String error(String message) {
        try {
            return MAPPER.writeValueAsString(
                    MAPPER.createObjectNode()
                            .put("error", message == null ? "unknown error" : message));
        } catch (Exception e) {
            return "{\"error\":\"internal error\"}";
        }
    }
}

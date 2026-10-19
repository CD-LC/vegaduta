package com.agenticai.core.agents.a2a;

/**
 * A JSON-RPC 2.0 error the A2A endpoint will serialize as
 * {@code {"jsonrpc":"2.0","id":...,"error":{"code","message","data"?}}} with
 * HTTP 200 - the JSON-RPC binding puts failures in the envelope, never in the
 * HTTP status (a 4xx/5xx would be read by A2A clients as a TRANSPORT failure and
 * retried, which is exactly wrong for "no such task").
 *
 * <p>Codes are the standard JSON-RPC set plus the A2A-specific block the spec
 * reserves from {@code -32001} down. Only the ones this server can actually
 * produce are defined - see {@link A2aTaskState}'s javadoc for the same
 * "advertise what you do, not what the spec lists" reasoning.
 */
public class A2aError extends RuntimeException {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int TASK_NOT_FOUND = -32001;
    public static final int TASK_NOT_CANCELABLE = -32002;
    public static final int PUSH_NOTIFICATION_NOT_SUPPORTED = -32003;
    public static final int UNSUPPORTED_OPERATION = -32004;
    public static final int CONTENT_TYPE_NOT_SUPPORTED = -32005;
    public static final int VERSION_NOT_SUPPORTED = -32009;

    private final int code;
    private final Object data;

    public A2aError(int code, String message) {
        this(code, message, null);
    }

    public A2aError(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    /** Optional structured detail for the {@code error.data} member; null omits it. */
    public Object getData() {
        return data;
    }

    public static A2aError parseError(String detail) {
        return new A2aError(PARSE_ERROR, "Parse error: " + detail);
    }

    public static A2aError invalidRequest(String detail) {
        return new A2aError(INVALID_REQUEST, "Invalid Request: " + detail);
    }

    public static A2aError methodNotFound(String method) {
        return new A2aError(METHOD_NOT_FOUND, "Method not found: " + method);
    }

    public static A2aError invalidParams(String detail) {
        return new A2aError(INVALID_PARAMS, "Invalid params: " + detail);
    }

    public static A2aError internal(String detail) {
        return new A2aError(INTERNAL_ERROR, "Internal error: " + detail);
    }

    /**
     * Same envelope for "never existed", "expired from the in-memory store"
     * and "belongs to a different tenant/user" - see {@link A2aTaskStore#find}
     * for why those three are deliberately indistinguishable to a caller.
     */
    public static A2aError taskNotFound(String taskId) {
        return new A2aError(TASK_NOT_FOUND, "Task not found", java.util.Map.of("taskId", taskId));
    }

    public static A2aError taskNotCancelable(String taskId, A2aTaskState state) {
        return new A2aError(TASK_NOT_CANCELABLE, "Task cannot be canceled: it is already " + state.name().toLowerCase(java.util.Locale.ROOT),
            java.util.Map.of("taskId", taskId));
    }

    /** Spec 3.3.4/13: an operation the Agent Card does not advertise (e.g. SubscribeToTask without streaming resubscribe). */
    public static A2aError unsupportedOperation(String operation) {
        return new A2aError(UNSUPPORTED_OPERATION, "Unsupported operation: " + operation);
    }

    public static A2aError pushNotificationsNotSupported() {
        return new A2aError(PUSH_NOTIFICATION_NOT_SUPPORTED, "Push notifications are not supported");
    }

    public static A2aError contentTypeNotSupported(String detail) {
        return new A2aError(CONTENT_TYPE_NOT_SUPPORTED, "Incompatible content types: " + detail);
    }

    public static A2aError versionNotSupported(String requested) {
        return new A2aError(VERSION_NOT_SUPPORTED, "Version not supported: " + requested,
            java.util.Map.of("supported", java.util.List.of("0.3", "1.0")));
    }
}

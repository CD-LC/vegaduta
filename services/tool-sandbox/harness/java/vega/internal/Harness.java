package vega.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point for a custom-tool JVM. Trusted platform code - the tenant's
 * compiled classes are what get checked against the reference policy, not this.
 *
 * <p>Invoked as {@code vega.internal.Harness <inputJsonFile> <resultJsonFile>}.
 * Input and output travel through files rather than stdout for one reason: the
 * tenant's tool can print freely via {@link vega.Log}, and framing a protocol
 * message inside a stream the tool also writes to would let the tool forge the
 * result by printing the frame itself. A file the tool cannot open (it has no
 * filesystem API at all) has no such ambiguity, and it leaves stdout entirely
 * free for the author's own debugging output.
 *
 * <p>Everything here is written so the JVM exits with a result file no matter
 * what the tool did - a tool that throws produces a structured error the model
 * can read and react to, which is the same "give the model the real error"
 * posture the rest of the platform's tool callbacks take.
 */
public final class Harness {

    /** Must match tool-sandbox/app/secretenv.py SECRET_ENV_PREFIX. */
    private static final String SECRET_ENV_PREFIX = "VEGA_SECRET_";

    private static final String TOOL_CLASS = "Tool";
    private static final String TOOL_METHOD = "run";

    private Harness() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: Harness <inputJsonFile> <resultJsonFile>");
            Runtime.getRuntime().halt(2);
            return;
        }
        Path inputPath = Path.of(args[0]);
        Path resultPath = Path.of(args[1]);

        // Publish the granted vault secrets BEFORE any tenant bytecode loads.
        // The tool cannot read the environment itself - the verifier denies
        // java.lang.System, "the door to getenv" - so this is the only point at
        // which they can cross into tool-reachable code, and vega.Secrets is
        // the only thing they cross into.
        vega.Secrets.populate(readGrantedSecrets());

        Map<String, Object> result;
        try {
            result = invoke(inputPath);
        } catch (Throwable t) {
            result = error(describe(t));
        }

        try {
            Files.writeString(resultPath, Json.write(result), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            System.err.println("harness: could not write result file: " + t);
            Runtime.getRuntime().halt(3);
            return;
        }

        // halt() rather than a normal return: a tool that started a non-daemon
        // thread (it cannot reference java.lang.Thread, but a library it calls
        // could) would otherwise keep this JVM alive until the wall-clock kill,
        // burning the whole timeout for a call that already produced its answer.
        Runtime.getRuntime().halt(0);
    }

    /**
     * Collects VEGA_SECRET_&lt;NAME&gt; out of the environment.
     *
     * <p>Unlike the Python harness there is no way to unset an environment
     * variable for the rest of the JVM - {@code System.getenv} returns an
     * unmodifiable view of a snapshot taken at start-up - so the scrubbing step
     * has no Java equivalent. It is not needed: the tool cannot reference
     * {@code System} at all, which is a stronger guarantee than removing the
     * entries would be.
     */
    private static Map<String, String> readGrantedSecrets() {
        Map<String, String> granted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith(SECRET_ENV_PREFIX)) {
                granted.put(key.substring(SECRET_ENV_PREFIX.length()), entry.getValue());
            }
        }
        return granted;
    }

    private static Map<String, Object> invoke(Path inputPath) throws Exception {
        String inputJson = Files.readString(inputPath, StandardCharsets.UTF_8);
        Object parsed = inputJson.isBlank() ? new LinkedHashMap<String, Object>() : Json.parse(inputJson);

        Map<String, Object> input;
        if (parsed instanceof Map<?, ?> map) {
            input = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                input.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else {
            // A bare scalar or array still needs to be reachable, so it arrives
            // under "value" - the same accommodation the SpEL tools make.
            input = new LinkedHashMap<>();
            input.put("value", parsed);
        }

        Class<?> toolClass;
        try {
            toolClass = Class.forName(TOOL_CLASS, true, Harness.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return error("No class named `Tool` was found. "
                + "Your program must declare `public class Tool`.");
        }

        Method run;
        try {
            run = toolClass.getMethod(TOOL_METHOD, Map.class);
        } catch (NoSuchMethodException e) {
            return error("`Tool` has no `run(Map<String, Object>)` method. "
                + "Declare `public static Object run(java.util.Map<String, Object> input)`.");
        }
        if (!Modifier.isStatic(run.getModifiers())) {
            return error("`Tool.run(Map)` must be declared `static`.");
        }

        Object value;
        try {
            value = run.invoke(null, input);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return error("Your tool threw " + describe(cause));
        } catch (IllegalAccessException e) {
            return error("`Tool.run(Map)` must be declared `public`.");
        }

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", Boolean.TRUE);
        ok.put("result", value);
        return ok;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.FALSE);
        out.put("errorType", "RUNTIME");
        out.put("error", message);
        return out;
    }

    /**
     * A one-line description including the first frame that belongs to the
     * tool, so the author sees where it failed without the harness's own
     * frames drowning it out.
     */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null && !t.getMessage().isBlank()) {
            sb.append(": ").append(t.getMessage());
        }
        for (StackTraceElement frame : t.getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.equals(TOOL_CLASS) || cls.startsWith(TOOL_CLASS + "$")) {
                sb.append(" (at ").append(cls).append('.').append(frame.getMethodName())
                  .append(" line ").append(frame.getLineNumber()).append(')');
                break;
            }
        }
        return sb.toString();
    }
}

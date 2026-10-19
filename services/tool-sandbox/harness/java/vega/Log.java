package vega;

/**
 * The only I/O a custom tool gets: a way to print while developing.
 *
 * <p>{@code java.lang.System} is denied to tenant code (it is the door to
 * {@code getenv}, {@code getProperty}, {@code exit} and {@code loadLibrary}),
 * which also takes {@code System.out.println} away. This class gives the
 * printing back without giving the rest back - it is trusted code shipped by
 * the platform, compiled into {@code vega-api.jar}, and only the tenant's own
 * compiled classes are checked against the reference policy, so the fact that
 * this class itself touches {@code System} is intentional and safe.
 *
 * <p>Whatever a tool logs is returned to its author in the {@code stdout}
 * field of a test run. It is not shown to the AI model, which only ever sees
 * the tool's return value.
 */
public final class Log {

    private Log() {
    }

    /** Print a line of diagnostic output. {@code null} prints as {@code null}. */
    public static void info(Object message) {
        System.out.println(message);
    }

    /** Print {@code label: value} - a shorthand for the common debugging case. */
    public static void info(String label, Object value) {
        System.out.println(label + ": " + value);
    }
}

package com.agenticai.core.sdlc.harness;

/** POSIX single-quote escaping for the few places the harness interpolates a value into a shell script. */
final class ShellQuote {

    private ShellQuote() {
    }

    static String single(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}

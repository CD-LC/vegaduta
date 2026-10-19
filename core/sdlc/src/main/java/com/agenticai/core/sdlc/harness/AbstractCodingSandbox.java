package com.agenticai.core.sdlc.harness;

/** Seeding steps shared by every {@link CodingSandbox}; they only use {@link #internal()}. */
abstract class AbstractCodingSandbox implements CodingSandbox {

    /** Repository size ceiling for a git clone, in KiB (checked after the clone). */
    static final long MAX_CLONE_KIB = 120L * 1024;

    private static final String GIT_CLONE =
        "git -c core.symlinks=false -c protocol.allow=never -c protocol.https.allow=always "
            + "-c http.followRedirects=false clone -q --depth 1 --no-tags --single-branch -- ";

    @Override
    public void seedFromGit(String httpsUrl) {
        if (httpsUrl == null || !httpsUrl.startsWith("https://")) {
            throw new IllegalArgumentException("Only https repository URLs can be cloned.");
        }
        ShellRunner.ShellResult clone = internal().run(GIT_CLONE + ShellQuote.single(httpsUrl) + " .", 180);
        if (!clone.ok()) {
            throw new IllegalStateException("Could not clone the repository: " + tail(clone.output()));
        }
        ShellRunner.ShellResult size = internal().run("du -sk . | cut -f1", 60);
        long kib = parseLong(size.output());
        if (kib > MAX_CLONE_KIB) {
            throw new IllegalStateException("The repository is larger than " + (MAX_CLONE_KIB / 1024) + " MB.");
        }
    }

    @Override
    public void prepareRepository() {
        require(internal().run("git init -q", 60), "git init");
        require(internal().run(
            "git config core.symlinks false && git config gc.auto 0 && git config core.autocrlf false", 60),
            "git config");
        require(internal().run("mkdir -p .git/info && printf '%s\\n' 'target/' 'node_modules/' '__pycache__/' "
            + "'.pytest_cache/' '.gradle/' 'build/' 'dist/' '*.class' '*.pyc' '.venv/' >> .git/info/exclude", 60),
            "git exclude");
    }

    private static void require(ShellRunner.ShellResult r, String what) {
        if (!r.ok()) {
            throw new IllegalStateException(what + " failed: " + tail(r.output()));
        }
    }

    static String tail(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= 400 ? t : t.substring(t.length() - 400);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s == null ? "" : s.strip());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

package com.agenticai.core.agents.tools.stdiosandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Scriptable host for the decision tests: which OS, which tools exist, which probes pass. */
final class FakeHost implements HostProbe {

    String os = "mac";
    Path home = Path.of("/Users/test");
    Set<String> tools = new HashSet<>();
    Set<String> executables = new HashSet<>();
    List<Path> pathDirs = new ArrayList<>(List.of(Path.of("/usr/bin")));
    Predicate<List<String>> probe = argv -> true;
    String uidGid = "501:20";
    final List<List<String>> probes = new ArrayList<>();

    @Override
    public String os() {
        return os;
    }

    @Override
    public Optional<String> which(String name) {
        return tools.contains(name) ? Optional.of("/usr/bin/" + name) : Optional.empty();
    }

    @Override
    public boolean executable(String absolutePath) {
        return executables.contains(absolutePath);
    }

    @Override
    public boolean runsOk(List<String> argv, int timeoutSeconds) {
        probes.add(argv);
        return probe.test(argv);
    }

    @Override
    public Path home() {
        return home;
    }

    @Override
    public List<Path> pathDirs() {
        return pathDirs;
    }

    @Override
    public String uidGid() {
        return uidGid;
    }

    static FakeHost mac() {
        FakeHost h = new FakeHost();
        h.os = "mac";
        h.executables.add("/usr/bin/sandbox-exec");
        return h;
    }

    static FakeHost linuxWithBwrap() {
        FakeHost h = new FakeHost();
        h.os = "linux";
        h.home = Path.of("/home/test");
        h.tools.add("bwrap");
        h.executables.add("/usr/bin/true");
        return h;
    }
}

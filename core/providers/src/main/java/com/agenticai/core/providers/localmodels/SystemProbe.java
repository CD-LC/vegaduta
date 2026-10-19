package com.agenticai.core.providers.localmodels;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The few operating-system reads {@link HardwareDetector} needs, behind an interface so tests can
 * describe any machine. Every method tolerates failure by returning null (or 0): a missing
 * {@code nvidia-smi}, an unreadable {@code /proc} or a sandbox that forbids a process start is a
 * normal answer, never an exception.
 */
interface SystemProbe {

    String osName();

    String osArch();

    int cpuCores();

    /** Whole file as text, or null when it does not exist or cannot be read. */
    String readFile(String path);

    /** Standard output of a fixed command, or null when it is absent, fails, or takes too long. */
    String run(List<String> command);

    /** Physical memory as the JVM sees it (the container limit inside a container), 0 if unknown. */
    long jvmTotalMemoryBytes();

    /** The real machine. Commands are fixed argument lists, never built from request data. */
    static SystemProbe real() {
        return new SystemProbe() {
            @Override
            public String osName() {
                return System.getProperty("os.name", "");
            }

            @Override
            public String osArch() {
                return System.getProperty("os.arch", "");
            }

            @Override
            public int cpuCores() {
                return Runtime.getRuntime().availableProcessors();
            }

            @Override
            public String readFile(String path) {
                try {
                    Path p = Path.of(path);
                    return Files.isReadable(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            }

            @Override
            public String run(List<String> command) {
                Process process = null;
                try {
                    process = new ProcessBuilder(command)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
                    process.getOutputStream().close();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        return null;
                    }
                    if (process.exitValue() != 0) {
                        return null;
                    }
                    return new String(process.getInputStream().readNBytes(64 * 1024), StandardCharsets.UTF_8);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (IOException | RuntimeException e) {
                    return null;
                } finally {
                    if (process != null && process.isAlive()) {
                        process.destroyForcibly();
                    }
                }
            }

            @Override
            public long jvmTotalMemoryBytes() {
                try {
                    if (ManagementFactory.getOperatingSystemMXBean()
                            instanceof com.sun.management.OperatingSystemMXBean os) {
                        return Math.max(0L, os.getTotalMemorySize());
                    }
                } catch (RuntimeException | LinkageError e) {
                    // not a HotSpot JVM: fall through
                }
                return 0L;
            }
        };
    }
}

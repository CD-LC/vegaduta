package com.agenticai.core.providers.localmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HardwareDetectorTest {

    /** A machine described by its files and command outputs; anything not listed is absent. */
    private static final class FakeSystem implements SystemProbe {
        String os = "Linux";
        String arch = "amd64";
        int cores = 8;
        long jvmMemory = 0;
        final Map<String, String> files = new HashMap<>();
        final Map<String, String> commands = new HashMap<>();
        int commandRuns;

        @Override
        public String osName() {
            return os;
        }

        @Override
        public String osArch() {
            return arch;
        }

        @Override
        public int cpuCores() {
            return cores;
        }

        @Override
        public String readFile(String path) {
            return files.get(path);
        }

        @Override
        public String run(List<String> command) {
            commandRuns++;
            return commands.get(String.join(" ", command));
        }

        @Override
        public long jvmTotalMemoryBytes() {
            return jvmMemory;
        }
    }

    private static final String NVIDIA_CMD = "nvidia-smi --query-gpu=name,memory.total --format=csv,noheader,nounits";

    @Test
    void linuxReadsMemTotalAndHasNoGpuWhenNvidiaSmiIsAbsent() {
        FakeSystem sys = new FakeSystem();
        sys.files.put("/proc/meminfo", "MemTotal:       16384000 kB\nMemFree: 1 kB\n");

        HardwareProfile p = new HardwareDetector(sys).measure();

        assertEquals(15.6, p.ramGb(), 0.05);
        assertEquals(8, p.cpuCores());
        assertNull(p.gpu());
        assertFalse(p.appleSilicon());
    }

    @Test
    void aContainerMemoryLimitLowersTheFigure() {
        FakeSystem sys = new FakeSystem();
        sys.files.put("/proc/meminfo", "MemTotal:       65536000 kB\n");
        sys.files.put("/sys/fs/cgroup/memory.max", "8589934592\n");

        assertEquals(8.0, new HardwareDetector(sys).measure().ramGb(), 0.05);
    }

    @Test
    void cgroupV1LimitIsUsedAndAnUnlimitedValueIsIgnored() {
        FakeSystem sys = new FakeSystem();
        sys.files.put("/proc/meminfo", "MemTotal:       32768000 kB\n");
        sys.files.put("/sys/fs/cgroup/memory.max", "max\n");
        sys.files.put("/sys/fs/cgroup/memory/memory.limit_in_bytes", "9223372036854771712\n");
        assertEquals(31.3, new HardwareDetector(sys).measure().ramGb(), 0.05);

        sys.files.put("/sys/fs/cgroup/memory/memory.limit_in_bytes", "4294967296\n");
        assertEquals(4.0, new HardwareDetector(sys).measure().ramGb(), 0.05);
    }

    @Test
    void nvidiaSmiPicksTheCardWithTheMostMemory() {
        FakeSystem sys = new FakeSystem();
        sys.files.put("/proc/meminfo", "MemTotal:       32768000 kB\n");
        sys.commands.put(NVIDIA_CMD, "NVIDIA GeForce RTX 3060, 12288\nNVIDIA GeForce RTX 4090, 24564\n");

        HardwareProfile p = new HardwareDetector(sys).measure();

        assertNotNull(p.gpu());
        assertEquals("NVIDIA", p.gpu().vendor());
        assertEquals("NVIDIA GeForce RTX 4090", p.gpu().name());
        assertEquals(24.0, p.gpu().vramGb(), 0.05);
        assertTrue(p.hasDiscreteGpu());
    }

    @Test
    void garbageFromNvidiaSmiIsIgnored() {
        FakeSystem sys = new FakeSystem();
        sys.files.put("/proc/meminfo", "MemTotal:       8192000 kB\n");
        sys.commands.put(NVIDIA_CMD, "No devices were found\n");

        assertNull(new HardwareDetector(sys).measure().gpu());
    }

    @Test
    void appleSiliconUsesSysctlAndSharesMemoryWithTheGpu() {
        FakeSystem sys = new FakeSystem();
        sys.os = "Mac OS X";
        sys.arch = "aarch64";
        sys.commands.put("sysctl -n hw.memsize", "34359738368\n");
        sys.commands.put("sysctl -n machdep.cpu.brand_string", "Apple M3 Pro\n");

        HardwareProfile p = new HardwareDetector(sys).measure();

        assertTrue(p.appleSilicon());
        assertEquals(32.0, p.ramGb(), 0.05);
        assertEquals("Apple", p.gpu().vendor());
        assertEquals("Apple M3 Pro", p.gpu().name());
        assertEquals(32.0, p.gpu().vramGb(), 0.05);
        assertFalse(p.hasDiscreteGpu(), "unified memory is not a discrete card");
    }

    @Test
    void anIntelJvmUnderRosettaStillCountsAsAppleSilicon() {
        FakeSystem sys = new FakeSystem();
        sys.os = "Mac OS X";
        sys.arch = "x86_64";
        sys.commands.put("sysctl -n hw.optional.arm64", "1\n");
        sys.commands.put("sysctl -n hw.memsize", "17179869184\n");

        assertTrue(new HardwareDetector(sys).measure().appleSilicon());
    }

    @Test
    void anIntelMacIsNotAppleSilicon() {
        FakeSystem sys = new FakeSystem();
        sys.os = "Mac OS X";
        sys.arch = "x86_64";
        sys.commands.put("sysctl -n hw.memsize", "17179869184\n");

        HardwareProfile p = new HardwareDetector(sys).measure();

        assertFalse(p.appleSilicon());
        assertEquals(16.0, p.ramGb(), 0.05);
    }

    @Test
    void whenEveryProbeFailsTheProfileIsEmptyButNeverAnError() {
        FakeSystem sys = new FakeSystem();
        sys.os = "Windows 11";

        HardwareProfile p = new HardwareDetector(sys).measure();

        assertEquals(0.0, p.ramGb());
        assertEquals(8, p.cpuCores());
        assertNull(p.gpu());
    }

    @Test
    void theJvmFigureIsTheFallbackWhereNoOsSourceExists() {
        FakeSystem sys = new FakeSystem();
        sys.os = "Windows 11";
        sys.jvmMemory = 16L * 1024 * 1024 * 1024;

        assertEquals(16.0, new HardwareDetector(sys).measure().ramGb(), 0.05);
    }

    @Test
    void theResultIsCachedSoNvidiaSmiIsNotRunPerRequest() {
        FakeSystem sys = new FakeSystem();
        sys.files.put("/proc/meminfo", "MemTotal:       8192000 kB\n");
        HardwareDetector detector = new HardwareDetector(sys);

        HardwareProfile first = detector.detect();
        int runs = sys.commandRuns;
        assertSame(first, detector.detect());
        assertEquals(runs, sys.commandRuns);
    }

    @Test
    void theRealProbeDescribesThisMachineWithoutThrowing() {
        HardwareProfile p = new HardwareDetector().measure();

        assertTrue(p.cpuCores() >= 1);
        assertTrue(p.ramGb() >= 0);
    }

    @Test
    void theLauncherCanDeclareTheHostSoAContainerDoesNotReportItself() {
        FakeSystem sys = new FakeSystem();
        sys.files.put("/proc/meminfo", "MemTotal:        1572864 kB\n");
        sys.files.put("/sys/fs/cgroup/memory.max", "1610612736\n");

        HardwareProfile container = new HardwareDetector(sys).measure();
        assertEquals(1.5, container.ramGb(), 0.05);
        assertNull(container.gpu());

        HardwareProfile mac = new HardwareDetector(sys, 16, "apple").measure();
        assertEquals(16.0, mac.ramGb(), 0.05);
        assertTrue(mac.appleSilicon());
        assertEquals("Apple", mac.gpu().vendor());

        HardwareProfile card = new HardwareDetector(sys, 32, "nvidia:RTX 4090:24").measure();
        assertEquals(32.0, card.ramGb(), 0.05);
        assertFalse(card.appleSilicon());
        assertEquals("RTX 4090", card.gpu().name());
        assertEquals(24.0, card.gpu().vramGb(), 0.05);

        // Unset or unreadable declarations change nothing.
        assertEquals(1.5, new HardwareDetector(sys, 0, "").measure().ramGb(), 0.05);
        assertNull(new HardwareDetector(sys, 0, "nvidia:broken").measure().gpu());
    }
}

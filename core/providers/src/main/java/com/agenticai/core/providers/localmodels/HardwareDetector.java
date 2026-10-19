package com.agenticai.core.providers.localmodels;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Describes the hardware a local model would run on, with no dependency beyond the JDK and the
 * tools a machine may happen to have: {@code /proc/meminfo} or {@code sysctl} for memory, the
 * cgroup files for a container limit, {@code nvidia-smi} for an NVIDIA card, {@code sysctl} for
 * Apple Silicon. Every probe may be absent; what cannot be read is reported as unknown (0 / null),
 * so detection never fails and never blocks for more than a few seconds.
 *
 * <p>Core may run in a container while Ollama runs on the host, or the reverse; what is measured
 * here is the machine core sees. A container sees only its own cgroup limit and a Linux kernel, so
 * the launcher (scripts/vegaduta personal) can pass the HOST's facts: {@code
 * app.local-models.host-ram-gb} (HOST_RAM_GB) replaces the measured memory and {@code
 * app.local-models.host-accelerator} (HOST_ACCELERATOR) is {@code apple} or {@code
 * nvidia:NAME:VRAM_GB}. Unset, nothing changes. The result is cached for ten minutes: hardware does not change
 * between page loads and {@code nvidia-smi} is not free.
 */
@Component
public class HardwareDetector {

    private static final long CACHE_MILLIS = 10 * 60_000L;
    private static final double GIB = 1024d * 1024d * 1024d;
    /** A cgroup "limit" at or above this is the kernel's spelling of "no limit". */
    private static final long UNLIMITED_BYTES = 1L << 60;
    private static final Pattern MEM_TOTAL = Pattern.compile("(?m)^MemTotal:\\s+(\\d+)\\s*kB");

    private final SystemProbe system;
    private final double hostRamGb;
    private final String hostAccelerator;
    private volatile HardwareProfile cached;
    private volatile long cachedAt;

    @Autowired
    public HardwareDetector(
            @Value("${app.local-models.host-ram-gb:0}") double hostRamGb,
            @Value("${app.local-models.host-accelerator:}") String hostAccelerator) {
        this(SystemProbe.real(), hostRamGb, hostAccelerator);
    }

    /** What core itself sees; used where no launcher facts exist (and by tests). */
    public HardwareDetector() {
        this(SystemProbe.real(), 0d, "");
    }

    HardwareDetector(SystemProbe system) {
        this(system, 0d, "");
    }

    HardwareDetector(SystemProbe system, double hostRamGb, String hostAccelerator) {
        this.system = system;
        this.hostRamGb = hostRamGb > 0d ? hostRamGb : 0d;
        this.hostAccelerator = hostAccelerator == null ? "" : hostAccelerator.trim();
    }

    /** The profile, from cache when it is younger than ten minutes. Never throws. */
    public HardwareProfile detect() {
        HardwareProfile profile = cached;
        long now = System.currentTimeMillis();
        if (profile != null && now - cachedAt < CACHE_MILLIS) {
            return profile;
        }
        profile = measure();
        cached = profile;
        cachedAt = now;
        return profile;
    }

    HardwareProfile measure() {
        boolean apple = isAppleSilicon();
        double ramGb = hostRamGb > 0d ? round1(hostRamGb) : round1(Math.max(0d, totalMemoryBytes()) / GIB);
        int cores = Math.max(1, system.cpuCores());
        HardwareProfile.Gpu gpu;
        String declared = hostAccelerator.toLowerCase(Locale.ROOT);
        if (declared.equals("apple") || declared.startsWith("apple:")) {
            // Declared by the launcher: core sits in a Linux container on an Apple Silicon Mac.
            String brand = hostAccelerator.length() > 6 ? hostAccelerator.substring(6).trim() : "";
            return new HardwareProfile(
                    ramGb, cores, new HardwareProfile.Gpu("Apple", brand.isEmpty() ? "Apple Silicon" : brand, ramGb), true);
        }
        HardwareProfile.Gpu declaredGpu = declaredNvidia();
        if (declaredGpu != null) {
            return new HardwareProfile(ramGb, cores, declaredGpu, false);
        }
        if (apple) {
            String brand = trimmed(system.run(List.of("sysctl", "-n", "machdep.cpu.brand_string")));
            gpu = new HardwareProfile.Gpu("Apple", brand.isEmpty() ? "Apple Silicon" : brand, ramGb);
        } else {
            gpu = nvidia();
        }
        return new HardwareProfile(ramGb, cores, gpu, apple);
    }

    /** {@code nvidia:NAME:VRAM_GB} from the launcher, or null. */
    private HardwareProfile.Gpu declaredNvidia() {
        if (!hostAccelerator.toLowerCase(Locale.ROOT).startsWith("nvidia:")) {
            return null;
        }
        String rest = hostAccelerator.substring(7);
        int colon = rest.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        String name = rest.substring(0, colon).trim();
        double vram;
        try {
            vram = Double.parseDouble(rest.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return name.isEmpty() || vram <= 0d ? null : new HardwareProfile.Gpu("NVIDIA", name, round1(vram));
    }

    private boolean isAppleSilicon() {
        String os = system.osName().toLowerCase(Locale.ROOT);
        if (!os.contains("mac")) {
            return false;
        }
        String arch = system.osArch().toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return true;
        }
        // An x86_64 JVM under Rosetta still runs on an Apple Silicon machine.
        return "1".equals(trimmed(system.run(List.of("sysctl", "-n", "hw.optional.arm64"))));
    }

    /** Physical memory, lowered to the container's cgroup limit when one is set. */
    private long totalMemoryBytes() {
        long bytes = 0L;
        String os = system.osName().toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            bytes = parseLong(system.run(List.of("sysctl", "-n", "hw.memsize")));
        } else if (os.contains("linux")) {
            String meminfo = system.readFile("/proc/meminfo");
            if (meminfo != null) {
                Matcher m = MEM_TOTAL.matcher(meminfo);
                if (m.find()) {
                    bytes = parseLong(m.group(1)) * 1024L;
                }
            }
        }
        if (bytes <= 0L) {
            bytes = system.jvmTotalMemoryBytes();
        }
        long limit = cgroupLimitBytes();
        if (limit > 0L && (bytes <= 0L || limit < bytes)) {
            bytes = limit;
        }
        return bytes;
    }

    /** cgroup v2 {@code memory.max}, else v1 {@code memory.limit_in_bytes}; 0 when unlimited or absent. */
    private long cgroupLimitBytes() {
        for (String file : List.of("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes")) {
            long value = parseLong(system.readFile(file));
            if (value > 0L && value < UNLIMITED_BYTES) {
                return value;
            }
        }
        return 0L;
    }

    /** The NVIDIA card with the most memory, from {@code nvidia-smi}; null when absent. */
    private HardwareProfile.Gpu nvidia() {
        String out = system.run(List.of(
                "nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader,nounits"));
        if (out == null || out.isBlank()) {
            return null;
        }
        HardwareProfile.Gpu best = null;
        for (String line : out.split("\\R")) {
            int comma = line.lastIndexOf(',');
            if (comma <= 0) {
                continue;
            }
            String name = line.substring(0, comma).trim();
            long mib = parseLong(line.substring(comma + 1));
            if (name.isEmpty() || mib <= 0L) {
                continue;
            }
            double vramGb = round1(mib / 1024d);
            if (best == null || vramGb > best.vramGb()) {
                best = new HardwareProfile.Gpu("NVIDIA", name, vramGb);
            }
        }
        return best;
    }

    private static long parseLong(String text) {
        if (text == null) {
            return 0L;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String trimmed(String text) {
        return text == null ? "" : text.trim();
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }
}

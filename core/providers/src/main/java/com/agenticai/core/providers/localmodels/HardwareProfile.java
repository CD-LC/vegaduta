package com.agenticai.core.providers.localmodels;

/**
 * What the machine core runs on can offer a local model: system memory (the container's cgroup
 * limit when one is set), CPU cores, the best discrete or Apple GPU found, and whether the memory
 * is Apple Silicon unified memory. Serialised as the {@code hardware} object of {@code GET
 * /api/local-models/recommendation}.
 *
 * @param ramGb usable system memory in GB, one decimal; 0 when it could not be read
 * @param cpuCores logical cores available to this process
 * @param gpu the GPU with the most memory, or null when none was detected
 * @param appleSilicon true on an Apple Silicon Mac (CPU and GPU share {@code ramGb})
 */
public record HardwareProfile(double ramGb, int cpuCores, Gpu gpu, boolean appleSilicon) {

    /**
     * @param vendor "NVIDIA" or "Apple"
     * @param name marketing name as the driver or the OS reports it
     * @param vramGb dedicated video memory in GB; for Apple Silicon the shared system memory
     */
    public record Gpu(String vendor, String name, double vramGb) {}

    /** True when {@link #gpu()} is a discrete card with its own memory (NVIDIA), not unified memory. */
    public boolean hasDiscreteGpu() {
        return gpu != null && !appleSilicon && gpu.vramGb() > 0;
    }
}

package com.agenticai.core.sdlc.dto;

/**
 * An input file mounted into the run's working directory (data-analysis
 * sandbox). {@code name} is a plain filename; the runner strips any path.
 */
public record SandboxFile(String name, byte[] content) {
}

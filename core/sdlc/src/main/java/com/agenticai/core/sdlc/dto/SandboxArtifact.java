package com.agenticai.core.sdlc.dto;

/** An output file (chart, workbook, script, notebook) the run left in its working directory. */
public record SandboxArtifact(String name, String mimeType, byte[] content) {
}

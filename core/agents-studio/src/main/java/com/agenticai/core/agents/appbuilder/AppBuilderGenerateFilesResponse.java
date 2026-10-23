package com.agenticai.core.agents.appbuilder;

import java.util.List;

/**
 * The model's reply shape for generate-files. {@code summary} is a
 * plain-English, one- or two-sentence explanation of what the change does,
 * written for the non-technical person who asked for it - surfaced as the
 * headline of each chat result in /build. Additive: replies from before the
 * field existed simply parse to null.
 */
public record AppBuilderGenerateFilesResponse(List<AppBuilderFileEdit> files, String summary) {
}

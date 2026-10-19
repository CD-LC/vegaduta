package com.agenticai.core.agents.appbuilder;

/** One full-file write - path is project-relative, content is the file's complete new contents. */
public record AppBuilderFileEdit(String path, String content) {
}

package com.agenticai.core.documentgen.model;

/**
 * Every file format this module can produce. Mirrors the {@code
 * ImageGenerationClient}/{@code VideoGenerationClient} convention of one
 * closed enum naming everything a generator can be registered against - see
 * {@code com.agenticai.core.documentgen.generator.DocumentGeneratorRegistry},
 * which maps exactly one {@code DocumentGenerator} bean per constant here.
 *
 * <p>{@link #extension()} feeds {@link
 * com.agenticai.core.documentgen.model.DocumentGenerationJobResponse#filename()}
 * (built WITH the extension, unlike {@code
 * DocumentGenerationRequest#filename()} which is extension-less); {@link
 * #mimeType()} is what {@code DocumentGenerationController#download} sets as
 * {@code Content-Type} when streaming the finished bytes back.
 */
public enum DocumentType {
    DOCX(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF(".pdf", "application/pdf"),
    XLSX(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PPTX(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    CSV(".csv", "text/csv"),
    TXT(".txt", "text/plain"),
    MD(".md", "text/markdown"),
    JSON(".json", "application/json");

    private final String extension;
    private final String mimeType;

    DocumentType(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    /** The filename suffix, including the leading dot (e.g. {@code ".docx"}). */
    public String extension() {
        return extension;
    }

    /** The {@code Content-Type} this format is served/stored as. */
    public String mimeType() {
        return mimeType;
    }
}

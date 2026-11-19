package com.agenticai.core.imagegen;

/** What the download endpoint needs to serve a stored generated image: type, download name and storage key. */
public record GeneratedImageFileRef(String contentType, String filename, String objectKey) {}

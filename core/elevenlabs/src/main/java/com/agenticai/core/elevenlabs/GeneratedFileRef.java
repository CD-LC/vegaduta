package com.agenticai.core.elevenlabs;

/**
 * What a controller needs to serve a stored generated file: its type, download name and storage
 * key.
 */
public record GeneratedFileRef(String contentType, String filename, String objectKey) {}

package com.agenticai.core.tenancy.demo;

/** A stored demo image/video ready to serve: its (already-validated) content type and bytes. */
public record DemoServedMedia(String contentType, byte[] bytes) {}

package com.agenticai.core.omnichannel.whatsapp;

/** One approved WhatsApp message template, as the provider that stores templates reports it. */
public record WhatsAppTemplate(
        String name, String language, String category, String bodyText, int bodyParamCount) {}

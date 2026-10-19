package com.agenticai.core.providers.modelcost;

/** What a model in {@code control_plane.media_model_cost} produces - matches that table's {@code modality} CHECK constraint. */
public enum MediaModality {
    TEXT,
    IMAGE,
    VIDEO,
    MUSIC,
    SPEECH
}

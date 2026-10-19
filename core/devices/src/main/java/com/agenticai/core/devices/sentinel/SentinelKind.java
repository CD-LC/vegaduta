package com.agenticai.core.devices.sentinel;

/** Closed set of signal kinds a Mobile Sentinel can read (contract v1). */
public enum SentinelKind {
    HEART_RATE,
    RESTING_HEART_RATE,
    HRV,
    SPO2,
    STEPS,
    SLEEP,
    BLOOD_PRESSURE,
    BLOOD_GLUCOSE,
    BODY_TEMPERATURE,
    WEIGHT,
    RESPIRATORY_RATE,
    SYMPTOM,
    MOOD,
    MEDICATION,
    DEVICE
}

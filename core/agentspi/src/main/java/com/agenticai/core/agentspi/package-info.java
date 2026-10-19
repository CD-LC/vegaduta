/**
 * Kernel-owned extension points through which vendor adapters contribute agent tools (ADR-0011 rule
 * a).
 *
 * <p>Deliberately its own top-level package with no dependency on {@code tools}, {@code agents} or
 * any vendor: the adapters live in vendor packages ({@code google.agenttools}, {@code
 * payments.agenttools}, {@code imagegen.agenttools}, {@code sarvam.agenttools}, {@code
 * elevenlabs.agenttools}) and depend only on this package, so kernel -> SPI <- vendor never forms a
 * package cycle (ADR-0011 rule c).
 */
package com.agenticai.core.agentspi;

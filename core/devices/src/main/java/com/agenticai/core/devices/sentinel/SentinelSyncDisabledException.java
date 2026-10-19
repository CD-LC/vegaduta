package com.agenticai.core.devices.sentinel;

/**
 * Ingest refused because the tenant policy has sentinels or sync switched
 * off. Mapped by {@link MobileSentinelController} to
 * {@code 403 {"error":"sentinel-sync-disabled"}} (contract body shape).
 */
public class SentinelSyncDisabledException extends RuntimeException {

    public SentinelSyncDisabledException() {
        super("sentinel-sync-disabled");
    }
}

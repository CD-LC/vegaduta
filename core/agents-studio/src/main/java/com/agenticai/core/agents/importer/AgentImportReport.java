package com.agenticai.core.agents.importer;

import com.agenticai.core.agents.importer.AgentImportDtos.ConversionReport;
import com.agenticai.core.agents.importer.AgentImportDtos.ConvertedItem;
import com.agenticai.core.agents.importer.AgentImportDtos.NeedsManualItem;
import com.agenticai.core.agents.importer.AgentImportDtos.UnsupportedItem;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable collector the mappers write the honest conversion report into. Pure. Every string is
 * length-capped on the way in (the text originates in an untrusted payload) and each list is
 * capped so a hostile payload with thousands of tools cannot inflate the response; the overflow
 * is summarised as one warning.
 */
public final class AgentImportReport {

    static final int MAX_ITEMS_PER_LIST = 100;
    private static final int WHAT_MAX = 120;
    private static final int TEXT_MAX = 400;

    private final List<ConvertedItem> converted = new ArrayList<>();
    private final List<NeedsManualItem> needsManual = new ArrayList<>();
    private final List<UnsupportedItem> unsupported = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private int dropped;

    public void converted(String what, String detail) {
        if (converted.size() < MAX_ITEMS_PER_LIST) {
            converted.add(new ConvertedItem(cap(what, WHAT_MAX), cap(detail, TEXT_MAX)));
        } else {
            dropped++;
        }
    }

    public void needsManual(String what, String why, String hint) {
        if (needsManual.size() < MAX_ITEMS_PER_LIST) {
            needsManual.add(new NeedsManualItem(cap(what, WHAT_MAX), cap(why, TEXT_MAX), cap(hint, TEXT_MAX)));
        } else {
            dropped++;
        }
    }

    public void unsupported(String what, String why) {
        if (unsupported.size() < MAX_ITEMS_PER_LIST) {
            unsupported.add(new UnsupportedItem(cap(what, WHAT_MAX), cap(why, TEXT_MAX)));
        } else {
            dropped++;
        }
    }

    public void warn(String warning) {
        if (warnings.size() < MAX_ITEMS_PER_LIST) {
            warnings.add(cap(warning, TEXT_MAX));
        } else {
            dropped++;
        }
    }

    public boolean hasNeedsManual() {
        return !needsManual.isEmpty();
    }

    /** True when a needs-manual entry with exactly this {@code what} label was already recorded. */
    public boolean hasNeedsManualFor(String what) {
        for (NeedsManualItem item : needsManual) {
            if (item.what().equals(what)) {
                return true;
            }
        }
        return false;
    }

    /** Immutable snapshot. Appends one warning when entries were dropped by the per-list cap. */
    public ConversionReport toReport() {
        List<String> outWarnings = new ArrayList<>(warnings);
        if (dropped > 0) {
            outWarnings.add(dropped + " further report entries were omitted (the report is capped at "
                + MAX_ITEMS_PER_LIST + " entries per section).");
        }
        return new ConversionReport(
            List.copyOf(converted), List.copyOf(needsManual), List.copyOf(unsupported), List.copyOf(outWarnings));
    }

    private static String cap(String s, int max) {
        return AgentImportText.truncate(s == null ? "" : s, max);
    }
}

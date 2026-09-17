package com.abheenash.triage.core;

/**
 * How much attention a finding deserves, most severe first.
 *
 * <p>Ordinal order is the ranking order, so {@code compareTo} already sorts a report the way an
 * on-call engineer wants to read it. {@link #escalate()} exists because severity is not purely a
 * property of the check: three orphaned bookings is a Tuesday, three thousand is an incident.
 */
public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    /** True when this severity is at least as severe as {@code floor}. */
    public boolean atLeast(Severity floor) {
        return this.ordinal() <= floor.ordinal();
    }

    /** One step more severe, saturating at {@link #CRITICAL}. */
    public Severity escalate() {
        return this == CRITICAL ? CRITICAL : values()[this.ordinal() - 1];
    }

    public static Severity parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown severity '" + raw + "'; expected one of CRITICAL, HIGH, MEDIUM, LOW, INFO");
        }
    }
}

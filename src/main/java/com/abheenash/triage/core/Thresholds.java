package com.abheenash.triage.core;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The numeric knobs the check queries are written against.
 *
 * <p>Check SQL refers to these as {@code ${name}} placeholders, which are substituted before the
 * statement is prepared. Substituting text into SQL is normally how injection happens, so the
 * rules here are deliberately narrow: a key must already exist in {@link #DEFAULTS} (so only the
 * eight names below are substitutable at all), and a value must parse as a plain number. Anything
 * else is rejected before it can reach the database. {@code SqlSafety} then re-checks the
 * assembled statement, so a mistake here still cannot produce a write.
 *
 * <p>Making these configurable was a version-2 item in the project scope. It landed in version 1
 * because the 7-day booking window already required a substitution mechanism, and once that
 * existed, exposing the other seven knobs cost one CLI option.
 */
public final class Thresholds {

    /** Every substitutable name, with the default the runbooks are written against. */
    public static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> d = new LinkedHashMap<>();
        // Bookings older than this cannot be acted on, so no check looks at them.
        d.put("booking_window_days", "7");
        // A calendar-sync run is expected to finish well inside 30 minutes.
        d.put("stuck_job_minutes", "30");
        // The sync runs every 30 minutes; 6 hours without a success is unambiguous.
        d.put("stale_job_hours", "6");
        // Connection saturation: below 80% there is still room to absorb a spike.
        d.put("connection_pct", "80");
        // No interactive query in this application should run for a minute.
        d.put("long_query_seconds", "60");
        // Dead-tuple floor, so a tiny table at 50% dead does not page anyone.
        d.put("dead_tuples_min", "10000");
        d.put("dead_tuple_pct", "20");
        // A pooled connection idle in a transaction for 5 minutes is a bug, not slow work.
        d.put("idle_in_transaction_seconds", "300");
        DEFAULTS = Collections.unmodifiableMap(d);
    }

    private final Map<String, String> values;

    private Thresholds(Map<String, String> values) {
        this.values = values;
    }

    public static Thresholds defaults() {
        return new Thresholds(new LinkedHashMap<>(DEFAULTS));
    }

    /**
     * Applies {@code key=value} overrides.
     *
     * @throws IllegalArgumentException if a key is unknown or a value is not a plain number
     */
    public Thresholds with(Iterable<String> overrides) {
        Map<String, String> merged = new LinkedHashMap<>(values);
        for (String override : overrides) {
            int eq = override.indexOf('=');
            if (eq <= 0 || eq == override.length() - 1) {
                throw new IllegalArgumentException(
                        "threshold override must look like key=value, got '" + override + "'");
            }
            String key = override.substring(0, eq).trim();
            String value = override.substring(eq + 1).trim();

            if (!DEFAULTS.containsKey(key)) {
                throw new IllegalArgumentException("unknown threshold '" + key + "'; known thresholds: "
                        + String.join(", ", DEFAULTS.keySet()));
            }
            BigDecimal parsed;
            try {
                parsed = new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "threshold " + key + " must be a number, got '" + value + "'");
            }
            if (parsed.signum() < 0) {
                throw new IllegalArgumentException("threshold " + key + " must not be negative");
            }
            merged.put(key, parsed.toPlainString());
        }
        return new Thresholds(merged);
    }

    public String get(String key) {
        String v = values.get(key);
        if (v == null) {
            throw new IllegalArgumentException("no such threshold: " + key);
        }
        return v;
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * Replaces every {@code ${name}} placeholder in {@code sql}.
     *
     * @throws IllegalStateException if any placeholder is left unresolved, which would otherwise
     *                               reach PostgreSQL as a syntax error at 2am instead of here
     */
    public String substitute(String sql, String checkId) {
        String out = sql;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        int unresolved = out.indexOf("${");
        if (unresolved >= 0) {
            int end = out.indexOf('}', unresolved);
            String name = end > 0 ? out.substring(unresolved + 2, end) : out.substring(unresolved);
            throw new IllegalStateException(
                    "check " + checkId + " references unknown threshold '" + name + "'");
        }
        return out;
    }
}

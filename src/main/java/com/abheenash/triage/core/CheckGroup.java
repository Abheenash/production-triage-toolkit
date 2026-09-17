package com.abheenash.triage.core;

import java.util.Locale;

/** The three families of check, and the {@code --group} value that selects each one. */
public enum CheckGroup {
    DATA_INTEGRITY("data", "Data integrity"),
    OPERATIONS("ops", "Operations"),
    DATABASE_HEALTH("dbhealth", "Database health");

    private final String cliName;
    private final String displayName;

    CheckGroup(String cliName, String displayName) {
        this.cliName = cliName;
        this.displayName = displayName;
    }

    public String cliName() {
        return cliName;
    }

    public String displayName() {
        return displayName;
    }

    public static CheckGroup parse(String raw) {
        if (raw != null) {
            String needle = raw.trim().toLowerCase(Locale.ROOT);
            for (CheckGroup g : values()) {
                if (g.cliName.equals(needle) || g.name().toLowerCase(Locale.ROOT).equals(needle)) {
                    return g;
                }
            }
        }
        throw new IllegalArgumentException(
                "unknown group '" + raw + "'; expected one of data, ops, dbhealth");
    }
}

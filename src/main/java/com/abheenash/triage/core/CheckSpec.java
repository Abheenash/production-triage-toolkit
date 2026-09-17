package com.abheenash.triage.core;

/**
 * One diagnostic: what it looks for, how bad it is, and where its runbook lives.
 *
 * <p>The SQL itself is deliberately NOT in this record. It lives in a {@code .sql} file on the
 * classpath so that it can be read, reviewed and run by hand in psql by whoever is on call --
 * a check nobody can read is a check nobody trusts at 2am.
 *
 * @param id              stable identifier, e.g. {@code DI002}; used by {@code --check} and in alerts
 * @param title           one line, imperative-free, describing what was found
 * @param group           which family this belongs to
 * @param baseSeverity    severity when the finding count is below {@code escalateAtRows}
 * @param escalateAtRows  row count at or above which severity moves one step up; -1 disables
 * @param sqlResource     classpath path to the query
 * @param runbook         repo-relative path to the runbook for this check
 * @param userImpact      what a user notices when this goes wrong, in plain language
 */
public record CheckSpec(
        String id,
        String title,
        CheckGroup group,
        Severity baseSeverity,
        int escalateAtRows,
        String sqlResource,
        String runbook,
        String userImpact) {

    public CheckSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("check id must not be blank");
        }
        if (sqlResource == null || !sqlResource.endsWith(".sql")) {
            throw new IllegalArgumentException("check " + id + " must point at a .sql resource");
        }
        if (runbook == null || !runbook.endsWith(".md")) {
            throw new IllegalArgumentException("check " + id + " must point at a .md runbook");
        }
    }

    /** Severity for a given number of matched rows. */
    public Severity severityFor(int matchCount) {
        if (escalateAtRows > 0 && matchCount >= escalateAtRows) {
            return baseSeverity.escalate();
        }
        return baseSeverity;
    }
}

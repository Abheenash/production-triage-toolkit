package com.abheenash.triage.core;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static guard: a check query must be a single, read-only SELECT.
 *
 * <p>This is the cheapest of the four safety layers and the only one that fails at build time --
 * {@code CheckCatalogTest} runs it over all 15 shipped queries, so a check that could write cannot
 * be released. The other three layers are at runtime: a read-only session, a read-only
 * transaction, and a statement timeout. Any one of them is sufficient; the point of having four
 * is that "the tool is pointed at production" is a claim that should not rest on one mechanism.
 *
 * <p>Comments are stripped before scanning, so the word "UPDATE" in an explanatory comment is not
 * a false positive, while a real {@code UPDATE} hidden behind a comment marker still is not.
 */
public final class SqlSafety {

    private static final List<String> FORBIDDEN = List.of(
            "insert", "update", "delete", "truncate", "drop", "alter", "create", "grant", "revoke",
            "merge", "copy", "vacuum", "analyze", "reindex", "cluster", "refresh", "call", "do",
            "lock", "comment", "security label", "import", "prepare", "execute", "listen", "notify",
            "set", "reset", "begin", "commit", "rollback", "savepoint", "discard", "checkpoint");

    /** {@code pg_terminate_backend} and friends write nothing but are absolutely not read-only. */
    private static final List<String> FORBIDDEN_FUNCTIONS = List.of(
            "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf", "pg_rotate_logfile",
            "pg_create_restore_point", "pg_promote", "pg_switch_wal", "lo_import", "lo_export",
            "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "dblink", "pg_sleep");

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    private SqlSafety() {
    }

    /**
     * @throws IllegalArgumentException with a specific reason if {@code sql} is not a lone read-only
     *                                  SELECT
     */
    public static void assertReadOnly(String sql, String checkId) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("check " + checkId + ": query is empty");
        }

        String stripped = strip(sql);
        String lower = stripped.toLowerCase(java.util.Locale.ROOT);

        String trimmed = lower.strip();
        if (!(trimmed.startsWith("select") || trimmed.startsWith("with"))) {
            throw new IllegalArgumentException(
                    "check " + checkId + ": query must start with SELECT or WITH");
        }

        // One statement only. A trailing semicolon would also break the CTE wrapper the runner
        // builds, so this catches a real bug as well as a safety issue.
        if (stripped.strip().contains(";")) {
            throw new IllegalArgumentException(
                    "check " + checkId + ": query must be a single statement with no semicolon");
        }

        for (String keyword : FORBIDDEN) {
            Matcher m = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(keyword) + "(?![a-z0-9_])")
                    .matcher(lower);
            if (m.find()) {
                // "WITH x AS MATERIALIZED (...)" and a data-modifying CTE are the same shape to a
                // keyword scan, so the message points at the offending word rather than guessing.
                throw new IllegalArgumentException("check " + checkId
                        + ": query contains the forbidden keyword '" + keyword.toUpperCase(java.util.Locale.ROOT)
                        + "' at offset " + m.start() + "; checks must be read-only");
            }
        }

        for (String fn : FORBIDDEN_FUNCTIONS) {
            if (lower.contains(fn)) {
                throw new IllegalArgumentException("check " + checkId
                        + ": query calls '" + fn + "', which is not read-only or not safe against production");
            }
        }
    }

    /** Removes comments and string literals so scanning sees only executable SQL. */
    static String strip(String sql) {
        String out = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        out = LINE_COMMENT.matcher(out).replaceAll(" ");
        out = STRING_LITERAL.matcher(out).replaceAll("''");
        return out;
    }
}

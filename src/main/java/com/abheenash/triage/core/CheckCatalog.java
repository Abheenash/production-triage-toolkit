package com.abheenash.triage.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The 15 shipped checks, and the loader that turns one into runnable SQL.
 *
 * <p>Severities are assigned by user-visible consequence, not by how interesting the bug is.
 * A ghost badge is CRITICAL because it is a physical security hole that no software alarm will
 * ever raise. An over-capacity booking is MEDIUM because the worst case is an awkward meeting.
 *
 * <p>Escalation thresholds are set where a difference in scale is a difference in kind. Three
 * orphaned bookings is a bad delete; five hundred is a failed migration, and the runbook's first
 * step changes accordingly.
 */
public final class CheckCatalog {

    private static final List<CheckSpec> CHECKS = List.of(

            // ---------------------------------------------------------------- data integrity
            new CheckSpec("DI001", "Bookings pointing at a room that no longer exists",
                    CheckGroup.DATA_INTEGRITY, Severity.HIGH, 100,
                    "/checks/sql/DI001_orphaned_bookings.sql",
                    "runbooks/DI001-orphaned-bookings.md",
                    "The booking page shows a blank room, or errors outright when opened."),

            new CheckSpec("DI002", "One room confirmed to two people at the same time",
                    CheckGroup.DATA_INTEGRITY, Severity.HIGH, 25,
                    "/checks/sql/DI002_overlapping_bookings.sql",
                    "runbooks/DI002-overlapping-bookings.md",
                    "Two groups arrive for the same room and one of them has to leave."),

            new CheckSpec("DI003", "Active badge belonging to a terminated employee",
                    CheckGroup.DATA_INTEGRITY, Severity.CRITICAL, -1,
                    "/checks/sql/DI003_active_badge_terminated_employee.sql",
                    "runbooks/DI003-ghost-badges.md",
                    "Someone who has left the company can still open the doors."),

            new CheckSpec("DI004", "Booking with more attendees than the room holds",
                    CheckGroup.DATA_INTEGRITY, Severity.MEDIUM, 250,
                    "/checks/sql/DI004_over_capacity_bookings.sql",
                    "runbooks/DI004-over-capacity.md",
                    "People arrive at a meeting and there is nowhere for them to sit."),

            new CheckSpec("DI005", "Booking that ends before it starts",
                    CheckGroup.DATA_INTEGRITY, Severity.HIGH, 50,
                    "/checks/sql/DI005_invalid_booking_range.sql",
                    "runbooks/DI005-invalid-time-range.md",
                    "The room shows as free when it is not, and availability maths goes wrong."),

            new CheckSpec("DI006", "Future booking in a decommissioned room",
                    CheckGroup.DATA_INTEGRITY, Severity.MEDIUM, 20,
                    "/checks/sql/DI006_bookings_on_inactive_rooms.sql",
                    "runbooks/DI006-inactive-rooms.md",
                    "Someone walks to a room that is now a store cupboard or a building site."),

            new CheckSpec("DI007", "Calendar sync wrote the same event more than once",
                    CheckGroup.DATA_INTEGRITY, Severity.HIGH, 50,
                    "/checks/sql/DI007_duplicate_external_events.sql",
                    "runbooks/DI007-duplicate-events.md",
                    "A meeting appears twice and the room looks busier than it really is."),

            // ---------------------------------------------------------------- operations
            new CheckSpec("OPS001", "Sync job started and never finished",
                    CheckGroup.OPERATIONS, Severity.CRITICAL, -1,
                    "/checks/sql/OPS001_stuck_sync_job.sql",
                    "runbooks/OPS001-stuck-sync-job.md",
                    "New calendar events stop appearing, silently, with no error anywhere."),

            new CheckSpec("OPS002", "No successful sync run recently enough",
                    CheckGroup.OPERATIONS, Severity.HIGH, -1,
                    "/checks/sql/OPS002_stale_sync_job.sql",
                    "runbooks/OPS002-stale-sync-job.md",
                    "Room availability drifts further from the truth with every hour that passes."),

            new CheckSpec("OPS003", "Facility request past its SLA",
                    CheckGroup.OPERATIONS, Severity.MEDIUM, 25,
                    "/checks/sql/OPS003_facility_request_sla_breach.sql",
                    "runbooks/OPS003-facility-sla.md",
                    "A broken room stays broken, and the requester hears nothing."),

            // ---------------------------------------------------------------- database health
            new CheckSpec("DBH001", "Connection slots close to exhausted",
                    CheckGroup.DATABASE_HEALTH, Severity.HIGH, -1,
                    "/checks/sql/DBH001_connection_saturation.sql",
                    "runbooks/DBH001-connection-saturation.md",
                    "At 100% every new request fails at once, with no warning beforehand."),

            new CheckSpec("DBH002", "Query running far longer than expected",
                    CheckGroup.DATABASE_HEALTH, Severity.HIGH, 5,
                    "/checks/sql/DBH002_long_running_queries.sql",
                    "runbooks/DBH002-long-running-queries.md",
                    "Pages hang. One runaway query can hold a connection the whole site needs."),

            new CheckSpec("DBH003", "Sessions blocked waiting on a lock",
                    CheckGroup.DATABASE_HEALTH, Severity.CRITICAL, -1,
                    "/checks/sql/DBH003_blocked_sessions.sql",
                    "runbooks/DBH003-blocked-sessions.md",
                    "Writes stall behind one holder and the queue grows until something times out."),

            new CheckSpec("DBH004", "Dead rows accumulating faster than autovacuum clears them",
                    CheckGroup.DATABASE_HEALTH, Severity.MEDIUM, 5,
                    "/checks/sql/DBH004_autovacuum_lag.sql",
                    "runbooks/DBH004-autovacuum-lag.md",
                    "Everything gets gradually slower, and eventually risks transaction wraparound."),

            new CheckSpec("DBH005", "Session idle inside an open transaction",
                    CheckGroup.DATABASE_HEALTH, Severity.HIGH, -1,
                    "/checks/sql/DBH005_idle_in_transaction.sql",
                    "runbooks/DBH005-idle-in-transaction.md",
                    "Holds locks and stops autovacuum cleaning every table in the database."));

    private static final Map<String, CheckSpec> BY_ID;

    static {
        Map<String, CheckSpec> byId = new LinkedHashMap<>();
        for (CheckSpec spec : CHECKS) {
            if (byId.put(spec.id().toUpperCase(Locale.ROOT), spec) != null) {
                throw new IllegalStateException("duplicate check id: " + spec.id());
            }
        }
        BY_ID = Map.copyOf(byId);
    }

    private CheckCatalog() {
    }

    public static List<CheckSpec> all() {
        return CHECKS;
    }

    public static Optional<CheckSpec> byId(String id) {
        return id == null ? Optional.empty()
                : Optional.ofNullable(BY_ID.get(id.trim().toUpperCase(Locale.ROOT)));
    }

    public static List<CheckSpec> byGroup(CheckGroup group) {
        return CHECKS.stream().filter(c -> c.group() == group).toList();
    }

    /**
     * Resolves {@code --check} and {@code --group} into the checks to run, in catalog order.
     *
     * <p>Empty selectors mean "everything", which is the behaviour a scheduled run wants.
     *
     * @throws IllegalArgumentException naming the unknown id, rather than silently running fewer
     *                                  checks than the operator believes they asked for
     */
    public static List<CheckSpec> select(Collection<String> ids, Collection<CheckGroup> groups) {
        boolean noIds = ids == null || ids.isEmpty();
        boolean noGroups = groups == null || groups.isEmpty();
        if (noIds && noGroups) {
            return CHECKS;
        }

        List<CheckSpec> selected = new ArrayList<>();
        if (!noIds) {
            for (String id : ids) {
                CheckSpec spec = byId(id).orElseThrow(() -> new IllegalArgumentException(
                        "unknown check '" + id + "'; run --list-checks to see the 15 available"));
                if (!selected.contains(spec)) {
                    selected.add(spec);
                }
            }
        }
        if (!noGroups) {
            for (CheckSpec spec : CHECKS) {
                if (groups.contains(spec.group()) && !selected.contains(spec)) {
                    selected.add(spec);
                }
            }
        }
        return CHECKS.stream().filter(selected::contains).toList();
    }

    /**
     * Reads a check's SQL from the classpath, substitutes thresholds, and proves it read-only.
     *
     * <p>The safety assertion runs on the FINAL text, after substitution, so a threshold value
     * cannot smuggle anything past it.
     */
    public static String loadSql(CheckSpec spec, Thresholds thresholds) {
        String raw;
        try (InputStream in = CheckCatalog.class.getResourceAsStream(spec.sqlResource())) {
            if (in == null) {
                throw new IllegalStateException(
                        "check " + spec.id() + ": SQL resource not on classpath: " + spec.sqlResource());
            }
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("check " + spec.id() + ": could not read " + spec.sqlResource(), e);
        }

        String sql = thresholds.substitute(raw, spec.id());
        SqlSafety.assertReadOnly(sql, spec.id());
        return sql;
    }
}

package com.abheenash.triage.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What changed between a previous run's JSON report and this run.
 *
 * <p>A single run answers "is anything wrong?". On call, the question is almost always "what is
 * wrong <em>now that wasn't at 09:00</em>?" -- a finding that has been open and ticketed for a week
 * is noise, a finding that appeared since the last run is the page. This compares by check id:
 *
 * <ul>
 *   <li>{@link Change#NEW} -- finding now, passed before</li>
 *   <li>{@link Change#RESOLVED} -- passed now, finding before</li>
 *   <li>{@link Change#WORSENED} / {@link Change#IMPROVED} -- finding both times, match count moved</li>
 *   <li>{@link Change#UNCHANGED} -- finding both times, same count</li>
 *   <li>{@link Change#BROKE} -- ran before, could not run now (the run is less trustworthy than it was)</li>
 *   <li>{@link Change#RECOVERED} -- could not run before, ran now</li>
 * </ul>
 *
 * <p>Checks that passed both times are not listed -- nothing happened. Checks present in only one
 * run (a different {@code --group} selection) are listed as {@link Change#NOT_COMPARED} so a
 * shrunken selection can never masquerade as "everything resolved".
 *
 * <p>Severity is not compared: it is derived from the match count and the thresholds, so a change
 * in it is already a change in count, and a threshold change between runs would otherwise show up
 * as spurious movement.
 */
public record RunDiff(String previousStartedAt, String previousTarget, List<Entry> entries) {

    /** Defensive copy — see the note in {@link CheckOutcome}. */
    public RunDiff {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public enum Change { NEW, WORSENED, BROKE, UNCHANGED, IMPROVED, RESOLVED, RECOVERED, NOT_COMPARED }

    /** One check's movement. Counts are {@code -1} when the check did not produce a count. */
    public record Entry(String checkId, String title, Change change, int previousCount, int currentCount) {
        public int delta() {
            return (previousCount < 0 || currentCount < 0) ? 0 : currentCount - previousCount;
        }
    }

    private static final Comparator<Entry> ORDER =
            Comparator.comparing((Entry e) -> e.change().ordinal()).thenComparing(Entry::checkId);

    /** Reads a report written by {@code --format json}. Rejects anything that is not one. */
    public static Map<String, Object> readPrevious(Path file) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(file));
        if (!root.isObject() || !"production-triage-toolkit".equals(root.path("tool").asText())) {
            throw new IllegalArgumentException(file + " is not a triage JSON report (no \"tool\" field)");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startedAt", root.path("startedAt").asText(""));
        JsonNode t = root.path("target");
        out.put("target", t.isMissingNode() ? "" : t.path("host").asText("") + ":" + t.path("port").asText("")
                + "/" + t.path("database").asText(""));
        Map<String, int[]> checks = new LinkedHashMap<>();  // id -> {status, count}; status 0 pass, 1 finding, 2 could not run
        for (JsonNode n : root.path("passed")) {
            checks.put(n.path("checkId").asText(), new int[] {0, 0});
        }
        for (JsonNode n : root.path("findings")) {
            checks.put(n.path("checkId").asText(), new int[] {1, n.path("matchCount").asInt(0)});
        }
        for (JsonNode n : root.path("couldNotRun")) {
            checks.put(n.path("checkId").asText(), new int[] {2, -1});
        }
        out.put("checks", checks);
        return out;
    }

    /** Compares {@code current} against a map produced by {@link #readPrevious(Path)}. */
    @SuppressWarnings("unchecked")
    public static RunDiff between(Map<String, Object> previous, RunReport current) {
        Map<String, int[]> before = (Map<String, int[]>) previous.get("checks");
        List<Entry> entries = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        for (CheckOutcome o : current.outcomes()) {
            String id = o.spec().id();
            seen.add(id);
            int[] prev = before.get(id);
            if (prev == null) {
                entries.add(new Entry(id, o.spec().title(), Change.NOT_COMPARED, -1, countOf(o)));
                continue;
            }
            int prevStatus = prev[0];
            int prevCount = prev[1];
            int curStatus = o.didNotRun() ? 2 : (o.isFinding() ? 1 : 0);
            int curCount = countOf(o);
            Change change;
            if (curStatus == 2) {
                change = prevStatus == 2 ? Change.UNCHANGED : Change.BROKE;
            } else if (prevStatus == 2) {
                change = Change.RECOVERED;
            } else if (curStatus == 1 && prevStatus == 0) {
                change = Change.NEW;
            } else if (curStatus == 0 && prevStatus == 1) {
                change = Change.RESOLVED;
            } else if (curStatus == 1) {
                change = curCount > prevCount ? Change.WORSENED : curCount < prevCount ? Change.IMPROVED : Change.UNCHANGED;
            } else {
                continue;  // passed both times: nothing to say
            }
            if (change == Change.UNCHANGED && curStatus == 2) {
                entries.add(new Entry(id, o.spec().title(), Change.UNCHANGED, -1, -1));
            } else {
                entries.add(new Entry(id, o.spec().title(), change, prevCount, curCount));
            }
        }
        for (Map.Entry<String, int[]> e : before.entrySet()) {
            if (!seen.contains(e.getKey())) {
                entries.add(new Entry(e.getKey(), "", Change.NOT_COMPARED, e.getValue()[1], -1));
            }
        }
        entries.sort(ORDER);
        return new RunDiff(
                Objects.toString(previous.get("startedAt"), ""),
                Objects.toString(previous.get("target"), ""),
                List.copyOf(entries));
    }

    private static int countOf(CheckOutcome o) {
        return o.didNotRun() ? -1 : o.matchCount();
    }

    public List<Entry> of(Change change) {
        return entries.stream().filter(e -> e.change() == change).toList();
    }

    /** True when something got worse: a new finding, a bigger one, or a check that stopped running. */
    public boolean hasRegressions() {
        return entries.stream().anyMatch(e -> e.change() == Change.NEW
                || e.change() == Change.WORSENED || e.change() == Change.BROKE);
    }

    /** True when the two runs looked at different check sets, so "no regressions" is not "all clear". */
    public boolean selectionsDiffer() {
        return entries.stream().anyMatch(e -> e.change() == Change.NOT_COMPARED);
    }
}

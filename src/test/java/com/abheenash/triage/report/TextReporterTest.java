package com.abheenash.triage.report;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TextReporterTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void tableHasAHeaderARuleAndOneLinePerRow() {
        List<String> lines = TextReporter.renderTable(
                List.of("booking_id", "room_id"),
                List.of(row("booking_id", 1, "room_id", 42),
                        row("booking_id", 2, "room_id", 43)));

        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).contains("booking_id").contains("room_id");
        assertThat(lines.get(1)).startsWith("---");
        assertThat(lines.get(3)).contains("43");
    }

    @Test
    void nullsAreRenderedExplicitlyRatherThanAsBlanks() {
        // A blank cell is ambiguous: empty string, or no value at all? During an incident that
        // difference decides whether the sync ever ran.
        List<String> lines = TextReporter.renderTable(
                List.of("heartbeat_at"), List.of(row("heartbeat_at", null)));
        assertThat(lines.get(2)).contains("null");
    }

    @Test
    void columnsThatWillNotFitAreDroppedAndCounted() {
        // Truncating silently would be worse: the reader cannot tell there was more to see.
        List<String> manyColumns = new java.util.ArrayList<>();
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            manyColumns.add("column_number_" + i);
            wide.put("column_number_" + i, "value_" + i);
        }
        List<String> lines = TextReporter.renderTable(manyColumns, List.of(wide));
        assertThat(lines.get(0)).contains("more columns)");
    }

    @Test
    void longValuesAreEllipsisedNotWrapped() {
        String longValue = "x".repeat(200);
        List<String> lines = TextReporter.renderTable(List.of("query"), List.of(row("query", longValue)));
        assertThat(lines.get(2)).endsWith("...");
        assertThat(lines.get(2).length()).isLessThan(60);
    }
}

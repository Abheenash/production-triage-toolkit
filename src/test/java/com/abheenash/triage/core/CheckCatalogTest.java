package com.abheenash.triage.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckCatalogTest {

    @Test
    void shipsFifteenChecksSplitSevenThreeFive() {
        // The project scope commits to exactly this split. Asserting it here means a check added
        // without updating the documentation breaks the build instead of quietly making the
        // README wrong.
        assertThat(CheckCatalog.all()).hasSize(15);
        assertThat(CheckCatalog.byGroup(CheckGroup.DATA_INTEGRITY)).hasSize(7);
        assertThat(CheckCatalog.byGroup(CheckGroup.OPERATIONS)).hasSize(3);
        assertThat(CheckCatalog.byGroup(CheckGroup.DATABASE_HEALTH)).hasSize(5);
    }

    @Test
    void checkIdsAreUnique() {
        Set<String> ids = CheckCatalog.all().stream().map(CheckSpec::id).collect(Collectors.toSet());
        assertThat(ids).hasSize(CheckCatalog.all().size());
    }

    @Test
    void everyCheckHasSqlOnTheClasspath() {
        for (CheckSpec spec : CheckCatalog.all()) {
            assertThat(CheckCatalog.loadSql(spec, Thresholds.defaults()))
                    .as("check %s", spec.id())
                    .isNotBlank();
        }
    }

    @Test
    void everyCheckHasARunbookThatActuallyExists() {
        // A finding without a next step is the exact failure this project argues against, so a
        // dangling runbook link is a build failure and not a documentation nit.
        for (CheckSpec spec : CheckCatalog.all()) {
            Path runbook = Path.of(spec.runbook());
            assertThat(Files.exists(runbook))
                    .as("check %s points at %s, which does not exist", spec.id(), spec.runbook())
                    .isTrue();
        }
    }

    @Test
    void everyRunbookCoversConfirmFixPreventAndEscalate() {
        for (CheckSpec spec : CheckCatalog.all()) {
            String text;
            try {
                text = Files.readString(Path.of(spec.runbook())).toLowerCase(java.util.Locale.ROOT);
            } catch (Exception e) {
                throw new AssertionError("could not read runbook for " + spec.id(), e);
            }
            assertThat(text).as("%s runbook", spec.id())
                    .contains("## confirm")
                    .contains("## fix")
                    .contains("## prevent")
                    .contains("## escalate");
        }
    }

    @Test
    void everyRunbookNamesItsOwnCheckId() {
        for (CheckSpec spec : CheckCatalog.all()) {
            try {
                assertThat(Files.readString(Path.of(spec.runbook())))
                        .as("%s runbook should name the check it belongs to", spec.id())
                        .contains(spec.id());
            } catch (Exception e) {
                throw new AssertionError("could not read runbook for " + spec.id(), e);
            }
        }
    }

    @Test
    void noSelectorMeansEveryCheck() {
        assertThat(CheckCatalog.select(List.of(), List.of())).isEqualTo(CheckCatalog.all());
    }

    @Test
    void selectionIsDeduplicatedAndKeepsCatalogueOrder() {
        List<CheckSpec> selected = CheckCatalog.select(
                List.of("DI003", "DI001", "di003"), List.of(CheckGroup.OPERATIONS));
        List<String> ids = selected.stream().map(CheckSpec::id).toList();
        assertThat(ids).containsExactly("DI001", "DI003", "OPS001", "OPS002", "OPS003");
    }

    @Test
    void anUnknownCheckIdFailsLoudlyRatherThanRunningFewerChecks() {
        // Silently ignoring a typo would mean an operator believes a check ran when it did not.
        assertThatThrownBy(() -> CheckCatalog.select(List.of("DI999"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DI999")
                .hasMessageContaining("--list-checks");
    }

    @Test
    void lookupIsCaseInsensitive() {
        assertThat(CheckCatalog.byId("di002")).isPresent();
        assertThat(CheckCatalog.byId(" OPS001 ")).isPresent();
        assertThat(CheckCatalog.byId("nope")).isEmpty();
    }

    @Test
    void bookingChecksAreScopedToTheSevenDayWindow() {
        // The scope says booking checks only look at recent bookings. That promise is what keeps
        // the tool fast at 10M rows, so it is verified rather than trusted.
        List<String> windowed = List.of("DI001", "DI002", "DI004", "DI005", "DI007");
        for (String id : windowed) {
            CheckSpec spec = CheckCatalog.byId(id).orElseThrow();
            assertThat(CheckCatalog.loadSql(spec, Thresholds.defaults()))
                    .as("check %s must bound itself to the booking window", id)
                    .contains("interval '7 days'");
        }
    }
}

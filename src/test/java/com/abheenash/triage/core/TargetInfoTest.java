package com.abheenash.triage.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TargetInfoTest {

    @Test
    void describeIdentifiesTheDatabaseThatWasExamined() {
        TargetInfo target = new TargetInfo("db.internal", 5432, "bookings", "readonly", "16.2", "triage/1.0.0");
        assertThat(target.describe()).isEqualTo("readonly@db.internal:5432/bookings");
    }

    @Test
    void thereIsNowhereInThisTypeToPutAPassword() {
        // The report is written to logs and to stdout. Nothing password-shaped may reach it, and
        // the simplest guarantee of that is a type with no component to hold one.
        assertThat(TargetInfo.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("host", "port", "database", "user", "serverVersion", "applicationName");
    }
}

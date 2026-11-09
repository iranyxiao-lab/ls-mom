package com.lsmom.core.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaGuardMigrationStrategyTest {

    private final SchemaFingerprintService fingerprints = mock(SchemaFingerprintService.class);
    private final SchemaDriftProperties properties = new SchemaDriftProperties();
    private final Flyway flyway = mock(Flyway.class, RETURNS_DEEP_STUBS);
    private final MigrateResult result = mock(MigrateResult.class);

    @BeforeEach
    void setUp() {
        when(flyway.migrate()).thenReturn(result);
    }

    @Test
    void recordsFingerprintWhenMigrationsExecuted() {
        result.migrationsExecuted = 2;
        new SchemaGuardMigrationStrategy(fingerprints, properties).migrate(flyway);
        verify(fingerprints).record(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("core-migrate"));
        verify(fingerprints, never()).diffAgainstLatest();
    }

    @Test
    void recordsFingerprintWhenNeverRecorded() {
        when(fingerprints.latestRecorded()).thenReturn(Optional.empty());
        new SchemaGuardMigrationStrategy(fingerprints, properties).migrate(flyway);
        verify(fingerprints).record(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("core-migrate"));
    }

    @Test
    void driftOnlyWarnsInWarnMode() {
        when(fingerprints.latestRecorded()).thenReturn(Optional.of(List.of("col a")));
        when(fingerprints.diffAgainstLatest()).thenReturn(List.of("+ col public.t.x text"));
        properties.setDriftMode(SchemaDriftProperties.DriftMode.WARN);
        assertThatCode(() -> new SchemaGuardMigrationStrategy(fingerprints, properties).migrate(flyway))
            .doesNotThrowAnyException();
    }

    @Test
    void driftFailsStartupInFailMode() {
        when(fingerprints.latestRecorded()).thenReturn(Optional.of(List.of("col a")));
        when(fingerprints.diffAgainstLatest()).thenReturn(List.of("+ col public.t.x text"));
        properties.setDriftMode(SchemaDriftProperties.DriftMode.FAIL);
        assertThatThrownBy(() -> new SchemaGuardMigrationStrategy(fingerprints, properties).migrate(flyway))
            .isInstanceOf(SchemaDriftException.class)
            .hasMessageContaining("+ col public.t.x text");
    }

    @Test
    void noDriftPassesInFailMode() {
        when(fingerprints.latestRecorded()).thenReturn(Optional.of(List.of("col a")));
        when(fingerprints.diffAgainstLatest()).thenReturn(List.of());
        properties.setDriftMode(SchemaDriftProperties.DriftMode.FAIL);
        assertThatCode(() -> new SchemaGuardMigrationStrategy(fingerprints, properties).migrate(flyway))
            .doesNotThrowAnyException();
    }
}

/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.internal.utils.search.postgres.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.pekko.actor.ActorSystem;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.PostgresConfig;
import org.junit.Test;

/**
 * Production write-path soak of the PostgreSQL search tables.
 * <ul>
 *     <li>{@link #load}: the soak-twin corpus with the production DDL.</li>
 *     <li>{@link #soak}: a warm-up and a gated period of updates through the shipped
 *     {@code PostgresSearchUpdaterFlow}, sampled in windows, with the throughput-gate and bloat-plateau verdicts.</li>
 * </ul>
 * Evidence: {@code bench.soak.resultsDir} ({@code load.md}, {@code environment.md}, {@code windows.md},
 * {@code vacuum.md}, {@code summary.md}, {@code samples.json}). The default is the committed evidence folder, so pass
 * a scratch folder for any trial run.
 * <p>
 * <b>Deliberately excluded from every automatic build phase</b> (the name matches no Surefire/Failsafe pattern). Run
 * via {@code benchmark/run-benchmark.sh soak-load} / {@code soak-run}, or explicitly:
 * </p>
 * <pre>
 * mvn -pl internal/utils/search-r2dbc test -Dtest='WriteSoakBench#soak' -DfailIfNoTests=false \
 *     -DBENCH_PG_URL=jdbc:postgresql://localhost:55434/bench -Dbench.count=10000000 -Dbench.soak.resultsDir=/tmp/x
 * </pre>
 */
public class WriteSoakBench {

    @Test
    public void load() throws Exception {
        final long count = BenchConfig.thingCount();
        System.out.printf("=== WriteSoakBench#load: %,d things (seed=%d, from=%d) into %s ===%n", count,
                BenchConfig.seed(), BenchConfig.soakLoadFrom(), BenchConfig.jdbcUrl());
        try (Connection connection = jdbc()) {
            final SoakCorpus.LoadReport report = SoakCorpus.load(connection, new SoakTwin(BenchConfig.seed()), count,
                    BenchConfig.soakLoadFrom(), BenchConfig.soakLoadChunk(), BenchConfig.soakMaintenanceWorkMem(),
                    System.out::println);
            System.out.println(report.markdown());
            BenchQuerySupport.writeResultFile(BenchConfig.soakResultsDir().resolve("load.md"), report.markdown());
            if (!report.catalog().matchesProduction()) {
                throw new AssertionError("catalog diverges from the production DDL: " + report.catalog());
            }
        }
    }

    @Test
    public void soak() throws Exception {
        final WriteSoakRunner.Settings settings = new WriteSoakRunner.Settings(BenchConfig.thingCount(),
                BenchConfig.seed(), BenchConfig.soakWorkers(), BenchConfig.soakTargetRate(), BenchConfig.soakWarmup(),
                BenchConfig.soakGated(), BenchConfig.soakPlateau(), BenchConfig.soakWindow(),
                BenchConfig.soakVacuumPoll());
        final double gateRate = BenchConfig.soakGateRate();
        final PostgresConfig config = ProductionSearchDb.shippedConfig(BenchConfig.r2dbcUrl(), BenchConfig.user(),
                BenchConfig.password());
        final int poolMaxSize = config.getPoolConfig().getMaxSize();
        settings.requireWorkersWithin(poolMaxSize);
        final Path dir = BenchConfig.soakResultsDir();
        final ActorSystem system = ActorSystem.create("WriteSoakBench");
        final DittoPostgresClient client = DittoPostgresClient.newInstance(config);
        final SoakTwin twin = new SoakTwin(settings.seed());
        try (Connection connection = jdbc()) {
            SoakCorpus.requireCorpusOf(connection, twin, settings.thingCount());
            final ProductionConfigSchema.CatalogCheck catalog = ProductionConfigSchema.verify(connection);
            if (!catalog.matchesProduction()) {
                throw new AssertionError("catalog diverges from the production DDL: " + catalog);
            }
            final Map<String, SoakAnalysis.VacuumTrigger> triggers = SoakDbStats.vacuumTriggers(connection);
            BenchQuerySupport.writeResultFile(dir.resolve("environment.md"), SoakReport.environment(settings,
                    gateRate, poolMaxSize, SoakDbStats.settings(connection), SoakDbStats.reloptions(connection),
                    triggers, catalog, SoakDbStats.snapshot(connection), Instant.now()));
            final Path windowsFile = dir.resolve("windows.md");
            BenchQuerySupport.writeResultFile(windowsFile, SoakReport.windowsHeader(settings, gateRate));

            final WriteSoakRunner.Result result = WriteSoakRunner.run(settings, twin, client, system,
                    WriteSoakBench::jdbc, window -> append(windowsFile, SoakReport.windowRow(window)));

            final SoakAnalysis.GateVerdict gate = SoakAnalysis.throughputGate(result.windows(), gateRate,
                    SoakAnalysis.MINIMUM_GATED);
            final List<SoakAnalysis.PlateauVerdict> plateaus = SoakAnalysis.RELATIONS.stream()
                    .filter(triggers::containsKey)
                    .map(relation -> SoakAnalysis.plateau(relation, result.windows(), settings.plateau(),
                            settings.window(), triggers.get(relation)))
                    .toList();
            BenchQuerySupport.writeResultFile(dir.resolve("vacuum.md"), SoakReport.vacuum(settings, result));
            BenchQuerySupport.writeResultFile(dir.resolve("samples.json"), SoakReport.json(settings, gateRate, result));
            final String summary = SoakReport.summary(settings, gateRate, result, gate, plateaus);
            BenchQuerySupport.writeResultFile(dir.resolve("summary.md"), summary);
            System.out.println(summary);
        } finally {
            client.close();
            system.terminate();
        }
    }

    private static Connection jdbc() throws SQLException {
        final Connection connection =
                DriverManager.getConnection(BenchConfig.jdbcUrl(), BenchConfig.user(), BenchConfig.password());
        connection.setAutoCommit(true);
        return connection;
    }

    private static void append(final Path file, final String row) {
        try {
            BenchQuerySupport.appendResultFile(file, row);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}

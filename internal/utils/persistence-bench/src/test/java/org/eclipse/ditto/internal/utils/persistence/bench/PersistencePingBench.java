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
package org.eclipse.ditto.internal.utils.persistence.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.SystemMaterializer;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.PostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.readjournal.PostgresReadJournal;
import org.eclipse.ditto.internal.utils.persistence.postgres.schema.PostgresSchema;
import org.junit.Test;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The persistence-ping stages on a corpus loaded with the tag mix (run-benchmark.sh ping | ping-recovery |
 * ping-actor, PostgreSQL only): read-journal walks, a walk broken twice, and the PersistencePingActor.
 */
public final class PersistencePingBench {

    private static final Duration WALK_TIMEOUT = Duration.ofHours(1);
    private static final int CONTROL_PIDS = 1_000_000;
    private static final int CONTROL_CHUNK = 100_000;
    private static final String PRIORITY_LABEL = "priority order (TAGS): windows, sorted by priority";

    @Test
    public void walks() throws Exception {
        if (PingWalks.BATCH != PostgresReadJournal.PRIORITY_WALK_WINDOW) {
            throw new IllegalStateException("the priority walk's window (" + PostgresReadJournal.PRIORITY_WALK_WINDOW
                    + ") must equal the bench window (" + PingWalks.BATCH + ") for the per-window figures");
        }
        final Corpus corpus = Corpus.open();
        final ActorSystem system = ActorSystem.create("PersistencePingBench");
        final Materializer mat = SystemMaterializer.get(system).materializer();
        final StatementTimer timer = new StatementTimer();
        final DittoPostgresClient client = PingWalks.timedClient(corpus.config("ping-walk"), timer);
        final StringBuilder md = new StringBuilder(corpus.header(
                String.format("read-journal walks over %,d long-history things", corpus.gen().pidCount())));
        final List<String> failures = new ArrayList<>();
        try (Connection observer = BenchConfig.openPg("ping-observer")) {
            final String statementTimeout = PingWalks.showStatementTimeout(client, mat);
            // the shipped value of ditto.postgresql.statement-timeout, as the client sends it (60 s)
            final long timeoutNanos = corpus.config("ping-walk").getStatementTimeout().toNanos();
            md.append("- client: the production read journal (`PostgresReadJournal` over `things_*`: the things ")
                    .append("service has no ping, the SQL is the policies ping's with another table name); connection ")
                    .append("options of `ConnectionPoolFactory.createDdlConnectionFactory` (`SHOW statement_timeout` ")
                    .append("through this client: `").append(statementTimeout).append("`), production pool limits; ")
                    .append("every statement timed by an r2dbc-proxy listener (execution until the last row is read); ")
                    .append("**no throttle**; read-journal-batch-size ").append(PingWalks.BATCH)
                    .append(", maxIdleTime ").append(PingWalks.MAX_IDLE_TIME).append("\n\n")
                    .append(PgVacuumProbe.tableStatsMarkdown(observer)).append('\n')
                    .append(corpus.mix().evidenceMarkdown());
            final PostgresReadJournal journal = PingWalks.readJournal(client, "things");
            final List<String> any = corpus.mix().expectedIdOrder(false);
            final List<String> newest = corpus.mix().expectedIdOrder(true);
            final List<Measured> walks = List.of(
                    measure("ID order, any tagged event (the policies ping)", journal.getJournalPidsWithTag(
                            PingTagMix.TAG, PingWalks.BATCH, PingWalks.MAX_IDLE_TIME, mat, false),
                            any, true, timer, mat),
                    measure("ID order, newest event tagged", journal.getJournalPidsWithTag(
                            PingTagMix.TAG, PingWalks.BATCH, PingWalks.MAX_IDLE_TIME, mat, true),
                            newest, true, timer, mat),
                    measure(PRIORITY_LABEL, journal.getJournalPidsWithTagOrderedByPriorityTag(
                            PingTagMix.TAG, PingWalks.MAX_IDLE_TIME), any, false, timer, mat));
            md.append("\n## Walks\n\nOrder: strictly ascending pid order for the ID walks (the priority order is ")
                    .append("checked below). Percentiles: nearest rank over the statements of a walk, the final empty ")
                    .append("window excluded. Every walk, the priority order included, reads windows of ")
                    .append(PingWalks.BATCH).append(" pids; the priority order emits its pids only after its last ")
                    .append("window.\n\n")
                    .append(walkTable(walks, statementTimeout, timeoutNanos)).append(tenthsTable(walks))
                    .append(slowestWindows(walks.get(0), corpus.mix()))
                    .append(slowestWindows(walks.get(1), corpus.mix()))
                    .append(slowestWindows(walks.get(2), corpus.mix()));
            final List<String> priorityViolations =
                    PingWalks.priorityViolations(walks.get(2).walk().delivered(), corpus.mix().priorityByPid());
            md.append('\n').append(PingWalks.priorityLine(walks.get(2).walk().delivered(), priorityViolations))
                    .append("\n\n");
            md.append(explains(observer, walks, corpus.mix()));
            md.append(sameRunControl(observer, timer, client, mat, timeoutNanos, failures));
            walks.forEach(w -> failures.addAll(PingWalks.offenders(w.walk(), w.check(), w.maxNanos(), timeoutNanos)));
            if (!priorityViolations.isEmpty()) {
                failures.add(PRIORITY_LABEL + ": " + priorityViolations.size() + " pids out of priority order");
            }
        } catch (final Throwable t) {
            failures.add("the stage failed: " + StatementTimer.describe(t));
            throw t;
        } finally {
            BenchProtocol.writeEvidence(BenchProtocol.evidenceName("ping-" + PingTagMix.EVIDENCE_SUFFIX + "-walks"),
                    md.append(verdictLine(failures)).toString());
            client.close();
            TestKit.shutdownActorSystem(system);
        }
        assertThat(failures).as("walk offenders (see the evidence)").isEmpty();
    }

    @Test
    public void recovery() throws Exception {
        final Corpus corpus = Corpus.open();
        final ActorSystem system = ActorSystem.create("PersistencePingBench");
        final Materializer mat = SystemMaterializer.get(system).materializer();
        final ListAppender<ILoggingEvent> warnings = PingRecovery.captureReadJournalWarnings();
        final String app = "ping-recovery";
        final DittoPostgresClient client = PingWalks.productionClient(corpus.config(app));
        final StringBuilder md = new StringBuilder(corpus.header("an ID-order walk broken twice"));
        final List<String> failures = new ArrayList<>();
        try {
            final List<String> expected = corpus.mix().expectedIdOrder(false);
            final String startedBefore = postmasterStart();
            final HostMemory.Sample before = HostMemory.sample();
            final PingRecovery.Result result = PingRecovery.run(PingWalks.readJournal(client, "things")
                            .getJournalPidsWithTag(PingTagMix.TAG, PingWalks.BATCH, PingWalks.MAX_IDLE_TIME, mat,
                                    false),
                    mat, expected, List.of(
                            new PingRecovery.Trigger(PingRecovery.TERMINATE, (expected.size() + 2) / 3,
                                    PingRecovery.terminateActiveWindow(() -> BenchConfig.openPg("ping-observer"), app,
                                            Duration.ofSeconds(60))),
                            new PingRecovery.Trigger(PingRecovery.RESTART, (2 * expected.size() + 2) / 3,
                                    PingRecovery.restartContainer(BenchConfig.pgContainer()))),
                    warnings, WALK_TIMEOUT);
            final HostMemory.Sample after = HostMemory.sample();
            final PingWalks.Check check = PingWalks.check(result.delivered(), expected, true);
            final String startedAfter = postmasterStart();
            md.append(recoveryMarkdown(result, check, List.copyOf(warnings.list), startedBefore, startedAfter,
                    before, after));
            failures.addAll(PingRecovery.offenders(result, check, !startedBefore.equals(startedAfter)));
        } catch (final Throwable t) {
            failures.add("the stage failed: " + StatementTimer.describe(t));
            throw t;
        } finally {
            BenchProtocol.writeEvidence(BenchProtocol.evidenceName("ping-" + PingTagMix.EVIDENCE_SUFFIX + "-recovery"),
                    md.append(verdictLine(failures)).toString());
            client.close();
            PingRecovery.release(warnings);
            TestKit.shutdownActorSystem(system);
        }
        assertThat(failures).as("recovery offenders (see the evidence)").isEmpty();
    }

    @Test
    public void actor() throws Exception {
        final Corpus corpus = Corpus.open();
        final ActorSystem system = ActorSystem.create("PersistencePingBench");
        final DittoPostgresClient client = PingWalks.productionClient(corpus.config("ping-actor"));
        final Duration interval = Duration.ofSeconds(BenchConfig.pingActorIntervalSeconds());
        final long rate = BenchConfig.pingActorRate();
        final List<String> expected = corpus.mix().expectedIdOrder(false);
        final String failAt = expected.get(expected.size() / 2);
        final StringBuilder md = new StringBuilder(corpus.header("PersistencePingActor, four walks"));
        final List<String> failures = new ArrayList<>();
        try {
            final HostMemory.Sample before = HostMemory.sample();
            final PingActorRun.Result result = PingActorRun.run(system,
                    PingActorRun.failingOnce(PingWalks.readJournal(client, "things"), 2, failAt),
                    PingActorRun.pingConfig(interval, rate), 4, interval.multipliedBy(4).plusMinutes(15));
            final HostMemory.Sample after = HostMemory.sample();
            failures.addAll(PingActorRun.verify(result, expected, failAt));
            md.append(actorMarkdown(result, expected, failAt, interval, rate, before, after));
        } catch (final Throwable t) {
            failures.add("the stage failed: " + StatementTimer.describe(t));
            throw t;
        } finally {
            BenchProtocol.writeEvidence(BenchProtocol.evidenceName("ping-" + PingTagMix.EVIDENCE_SUFFIX + "-actor"),
                    md.append(verdictLine(failures)).toString());
            client.close();
            TestKit.shutdownActorSystem(system);
        }
        assertThat(failures).as("actor offenders (see the evidence)").isEmpty();
    }

    /** The corpus of these stages: generator + tag mix, checked against the database before anything runs. */
    private record Corpus(CorpusGenerator gen, PingTagMix mix) {

        static Corpus open() throws Exception {
            if (!BenchConfig.runPg() || !BenchConfig.pingTagMix()) {
                throw new IllegalStateException("the ping stages need --backend pg and BENCH_PING_TAG_MIX=true");
            }
            final CorpusGenerator gen = BenchConfig.corpus();
            final PingTagMix mix = PingTagMix.of(gen);
            try (Connection c = BenchConfig.openPg("ping-check");
                    Statement st = c.createStatement();
                    ResultSet rs = st.executeQuery("SELECT count(*) FROM things_journal_seq")) {
                rs.next();
                final long pids = rs.getLong(1);
                final long tagged = PgLoadBench.countTaggedRows(c);
                if (pids != gen.pidCount() || tagged != mix.taggedRows()) {
                    throw new IllegalStateException("the loaded corpus is not this tag mix: " + pids + " pids, "
                            + tagged + " tagged rows; expected " + gen.pidCount() + " and " + mix.taggedRows()
                            + " (load with BENCH_PING_TAG_MIX=true and the same --count)");
                }
            }
            return new Corpus(gen, mix);
        }

        PostgresConfig config(final String applicationName) {
            return PingWalks.postgresConfig(BenchConfig.pgUrl(), BenchConfig.pgUser(), BenchConfig.pgPassword(),
                    applicationName);
        }

        String header(final String what) {
            final String label = BenchConfig.evidenceLabel();
            return "# Persistence ping on a partly tagged corpus: " + what + "\n\n"
                    + "- generated: " + Instant.now() + "\n"
                    + "- corpus: count=" + gen.pidCount() + " seed=" + BenchConfig.seed() + " pingTagMix="
                    + PingTagMix.EVIDENCE_SUFFIX + " (load evidence: `" + BenchProtocol.corpusEvidenceName(
                    PgLoadBench.evidenceBase(mix), gen) + "`), schema v" + PostgresSchema.VERSION + "\n"
                    + "- backend: pg\n"
                    + BenchProtocol.containersLine(BenchConfig.containerLimits())
                    + (label.isEmpty() ? "" : "- evidence label: " + label + "\n");
        }
    }

    private record Measured(PingWalks.Walk walk, PingWalks.Check check, boolean idOrder, HostMemory.Sample before,
            HostMemory.Sample after, List<Long> windowNanos, long maxNanos) {}

    private static Measured measure(final String label, final Source<String, NotUsed> source,
            final List<String> expected, final boolean idOrder, final StatementTimer timer, final Materializer mat)
            throws Exception {
        System.out.println("[bench] walk: " + label);
        final HostMemory.Sample before = HostMemory.sample();
        final PingWalks.Walk walk = PingWalks.walk(label, source, timer, mat, WALK_TIMEOUT);
        final HostMemory.Sample after = HostMemory.sample();
        final List<StatementTimer.Statement> statements = walk.statements();
        final int last = statements.size() - 1;
        // every walk reads windows: the final empty window does not count in the per-statement figures (as in the 1M
        // evidence)
        final List<Long> nanos = IntStream.range(0, statements.size())
                .filter(i -> !(i == last && statements.get(i).rows() == 0))
                .mapToObj(i -> statements.get(i).nanos())
                .toList();
        final long max = nanos.stream().mapToLong(Long::longValue).max().orElse(0L);
        return new Measured(walk, PingWalks.check(walk.delivered(), expected, idOrder), idOrder, before, after, nanos,
                max);
    }

    private static String verdictLine(final List<String> failures) {
        return failures.isEmpty() ? "\n**Result: every walk delivered exactly the expected pids.**\n"
                : "\n**Result: ⛔ offenders** (the stage fails):\n\n" + failures.stream().map(f -> "- " + f + "\n")
                .collect(Collectors.joining());
    }

    private static String postmasterStart() throws SQLException {
        try (Connection c = BenchConfig.openPg("ping-check"); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT pg_postmaster_start_time()::text")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** One row per walk; {@code timeoutNanos} 0: no statement_timeout comparison. */
    private static String walkTable(final List<Measured> walks, final String statementTimeout,
            final long timeoutNanos) {
        final List<List<String>> rows = new ArrayList<>();
        for (final Measured m : walks) {
            final List<Long> sorted = m.windowNanos().stream().sorted().toList();
            final List<StatementTimer.Statement> statements = m.walk().statements();
            final int maxStatement = IntStream.range(0, statements.size())
                    .filter(i -> statements.get(i).nanos() == m.maxNanos()).findFirst().orElse(-1) + 1;
            rows.add(List.of(m.walk().label(),
                    String.format("%,d", statements.size()),
                    String.format("%,d / %,d", m.check().delivered(), m.check().expected()),
                    String.valueOf(m.check().missing().size()),
                    String.valueOf(m.check().extra().size()),
                    String.valueOf(m.check().duplicates().size()),
                    orderCell(m),
                    String.format("%.1f s", m.walk().nanos() / 1e9),
                    String.format("%.2f ms", PingWalks.ms(PingWalks.percentile(sorted, 50))),
                    String.format("%.2f ms", PingWalks.ms(PingWalks.percentile(sorted, 95))),
                    String.format("%.2f ms", PingWalks.ms(PingWalks.percentile(sorted, 99))),
                    String.format("%.2f ms (#%d)", PingWalks.ms(m.maxNanos()), maxStatement),
                    timeoutNanos == 0 ? "—" : String.format("%.4f %%", 100.0 * m.maxNanos() / timeoutNanos),
                    memory(m.before(), m.after()),
                    HostMemory.verdict(m.before(), m.after())));
        }
        return BenchProtocol.table(List.of("walk", "statements", "delivered / expected", "missing", "extra", "dup",
                "order", "walk time", "p50", "p95", "p99", "max (statement #)",
                "max / statement_timeout" + (statementTimeout.isEmpty() ? "" : " (" + statementTimeout + ")"),
                "host memory (Δ swap-outs, free % before→after)", "verdict"), rows);
    }

    private static String orderCell(final Measured m) {
        if (m.check().delivered() == 0) {
            return "not checked (nothing delivered)";
        }
        if (!m.idOrder()) {
            return "by priority, see below";
        }
        return m.check().ascending() ? "ok" : "NOT ascending";
    }

    private static String memory(final HostMemory.Sample before, final HostMemory.Sample after) {
        return before.available() && after.available()
                ? String.format("%+d, %d → %d %%", after.swapouts() - before.swapouts(), before.freePercent(),
                after.freePercent())
                : "not sampled";
    }

    private static String tenthsTable(final List<Measured> walks) {
        final List<String> header = new ArrayList<>(List.of("walk"));
        IntStream.rangeClosed(1, 10).forEach(tenth -> header.add("tenth " + tenth));
        final List<List<String>> rows = new ArrayList<>();
        for (final Measured m : walks) {
            // every walk with more than one statement (all of them read windows)
            if (m.walk().statements().size() > 1) {
                final List<String> row = new ArrayList<>(List.of(m.walk().label()));
                PingWalks.tenthMedians(m.windowNanos()).forEach(n -> row.add(String.format("%.2f", PingWalks.ms(n))));
                rows.add(row);
            }
        }
        return "\n### Median statement time per tenth of the walk (ms)\n\n" + BenchProtocol.table(header, rows);
    }

    private static String slowestWindows(final Measured m, final PingTagMix mix) {
        final List<StatementTimer.Statement> statements = m.walk().statements();
        final long[][] generatorRows = mix.journalRowsPerWindow(PingWalks.BATCH);
        final List<List<String>> rows = IntStream.range(0, statements.size()).boxed()
                .sorted(Comparator.comparingLong((Integer i) -> statements.get(i).nanos()).reversed())
                .limit(5)
                .map(i -> List.of(String.valueOf(i + 1),
                        String.format("%.2f", PingWalks.ms(statements.get(i).nanos())),
                        String.format("%,d", statements.get(i).rows()),
                        i < generatorRows.length ? String.format("%,d", generatorRows[i][0]) : "—",
                        i < generatorRows.length ? String.format("%,d", generatorRows[i][1]) : "—"))
                .toList();
        return "\n### Five slowest windows: " + m.walk().label() + "\n\n"
                + BenchProtocol.table(List.of("# (window = # − 1)", "ms", "rows returned",
                "journal rows of the window's pids (generator)", "of them, of untagged pids"), rows);
    }

    private static String explains(final Connection observer, final List<Measured> walks, final PingTagMix mix)
            throws SQLException {
        final List<String> sorted = mix.allPidsSorted();
        final long[][] generatorRows = mix.journalRowsPerWindow(PingWalks.BATCH);
        final int windows = generatorRows.length;
        int heaviest = 0;
        for (int k = 1; k < windows; k++) {
            if (generatorRows[k][0] > generatorRows[heaviest][0]) {
                heaviest = k;
            }
        }
        final Set<Integer> picks = new LinkedHashSet<>(List.of(0, windows / 2,
                (sorted.size() - sorted.size() / 100) / PingWalks.BATCH, heaviest));
        picks.removeIf(k -> k >= windows);
        final StringBuilder md = new StringBuilder("\n## EXPLAIN (ANALYZE, BUFFERS) of the walks' statements\n\n")
                .append("Each statement as the walk recorded it, prepared on a JDBC connection with ")
                .append("`plan_cache_mode = force_custom_plan` and executed with the bind values of the window: ")
                .append("window 0, the middle window, the first window of the clustered block and the window with ")
                .append("the most journal rows (generator).\n");
        for (final Measured m : walks) {
            final String sql = m.walk().statements().get(0).sql();
            for (final int k : picks) {
                final String cursor = PingWalks.windowCursor(sorted, k, PingWalks.BATCH);
                md.append(String.format("%n### %s: window %d, cursor `%s`, %,d journal rows (generator)%n%n```%n",
                                m.walk().label(), k, cursor.isEmpty() ? "''" : cursor, generatorRows[k][0]))
                        .append(PingWalks.explain(observer, sql, PingWalks.WINDOW_PARAMETER_TYPES,
                                List.of(cursor, PingTagMix.TAG, PingWalks.BATCH)))
                        .append("```\n");
            }
        }
        return md.toString();
    }

    private static String sameRunControl(final Connection observer, final StatementTimer timer,
            final DittoPostgresClient client, final Materializer mat, final long timeoutNanos,
            final List<String> failures) throws Exception {
        final List<String> ddl = PostgresSchema.ddlStatements().stream()
                .filter(s -> s.contains("policies_journal") && !s.contains("policies_journal_seq")).toList();
        final long seedStart = System.nanoTime();
        try (Statement st = observer.createStatement()) {
            st.execute("DROP TABLE IF EXISTS policies_journal");
            for (final String statement : ddl) {
                st.execute(statement);
            }
            for (int from = 1; from <= CONTROL_PIDS; from += CONTROL_CHUNK) {
                final int to = Math.min(CONTROL_PIDS, from + CONTROL_CHUNK - 1);
                st.execute("INSERT INTO policies_journal (pid, sn, manifest, tags, event) "
                        + "SELECT 'policy:mio:' || lpad(i::text, 7, '0'), s, 'manifest', "
                        + "CASE WHEN i > " + (CONTROL_PIDS - CONTROL_PIDS / 100) + " THEN ARRAY['" + PingTagMix.TAG
                        + "'] ELSE ARRAY['other'] END, jsonb_build_object('i', i, 's', s) "
                        + "FROM generate_series(" + from + ", " + to + ") AS i, generate_series(1, 2) AS s "
                        + "ORDER BY random()");
            }
            st.execute("VACUUM ANALYZE policies_journal");
        }
        try {
            final List<String> expected = IntStream.rangeClosed(CONTROL_PIDS - CONTROL_PIDS / 100 + 1, CONTROL_PIDS)
                    .mapToObj(i -> String.format("policy:mio:%07d", i)).toList();
            final PostgresReadJournal controlJournal = PingWalks.readJournal(client, "policies");
            final Measured control = measure("same-run control: 1,000,000 pids x 2 events, 1 % clustered at the end, "
                            + "random heap", controlJournal.getJournalPidsWithTag(PingTagMix.TAG, PingWalks.BATCH,
                            PingWalks.MAX_IDLE_TIME, mat, false), expected, true, timer, mat);
            // no priority tags in the control: every pid has priority 0, so its priority order is the pid order
            final Measured controlPriority = measure("same-run control, priority order (TAGS)",
                    controlJournal.getJournalPidsWithTagOrderedByPriorityTag(PingTagMix.TAG, PingWalks.MAX_IDLE_TIME),
                    expected, true, timer, mat);
            for (final Measured m : List.of(control, controlPriority)) {
                failures.addAll(PingWalks.offenders(m.walk(), m.check(), m.maxNanos(), timeoutNanos));
            }
            return "\n## Same-run control (the clustered layout of the 1M evidence, this container)\n\n"
                    + String.format("Seeded in %.0f s into a production-DDL `policies_journal`, dropped "
                    + "afterwards.%n%n", (System.nanoTime() - seedStart) / 1e9)
                    + walkTable(List.of(control, controlPriority), "", 0L)
                    + "\nFor comparison, `ping-keyset-paging-1m.md` (2026-10-02, Testcontainers defaults, "
                    + "shared_buffers 128MB, warm cache): 2,000 windows, 10,000 delivered, 3.0 s, p50 1.27 ms, "
                    + "p95 2.06 ms, p99 3.50 ms, max 6.52 ms (the ID order only; the priority order was one statement "
                    + "then).\n\n";
        } finally {
            try (Statement st = observer.createStatement()) {
                st.execute("DROP TABLE IF EXISTS policies_journal");
            }
        }
    }

    private static String recoveryMarkdown(final PingRecovery.Result result, final PingWalks.Check check,
            final List<ILoggingEvent> warnings, final String startedBefore, final String startedAfter,
            final HostMemory.Sample before, final HostMemory.Sample after) {
        final StringBuilder md = new StringBuilder("\n## Walk\n\n")
                .append("- client: exactly the production pool (`ConnectionPoolFactory.createConnectionPool`), ")
                .append("`PostgresReadJournal` over `things_*`, ID order, any tagged event, read-journal-batch-size ")
                .append(PingWalks.BATCH).append(", maxIdleTime ").append(PingWalks.MAX_IDLE_TIME)
                .append(" (10 retries per window), no throttle\n")
                .append(String.format("- delivered / expected: %,d / %,d; %s%n", check.delivered(), check.expected(),
                        check.problems().isEmpty() ? "missing 0, extra 0, duplicates 0, strictly ascending"
                                : String.join("; ", check.problems())))
                .append(String.format("- walk time: %.1f s%n", result.walkNanos() / 1e9))
                .append("- walk failure: ").append(result.walkFailure().map(StatementTimer::describe).orElse("none"))
                .append('\n')
                .append("- host memory before: ").append(before.line()).append('\n')
                .append("- host memory after: ").append(after.line()).append('\n')
                .append("- host memory verdict: ").append(HostMemory.verdict(before, after)).append('\n')
                .append("- postmaster start time: ").append(startedBefore).append(" → ").append(startedAfter)
                .append(startedBefore.equals(startedAfter) ? " (unchanged: no restart)" : " (restarted)")
                .append("\n\n## Breaks\n\n")
                .append(BenchProtocol.table(List.of("break", "at (UTC)", "delivered before", "detail", "failure"),
                        result.breaks().stream().map(b -> List.of(b.kind(), b.at().toString(),
                                String.format("%,d", b.deliveredBefore()), b.detail().isEmpty() ? "—" : b.detail(),
                                b.failure().isEmpty() ? "—" : b.failure())).toList()))
                .append("\n## Read-journal WARN lines (verbatim)\n\n")
                .append("Each line: the time, the break it follows and the gap to the previous WARN of that break ")
                .append("(the back-off: 1 s, 2 s, 4 s … plus the attempt's own time and jitter).\n\n");
        if (warnings.isEmpty()) {
            md.append("none\n");
        }
        final Map<Integer, Instant> previousOfBreak = new HashMap<>();
        for (final ILoggingEvent event : warnings) {
            final Instant at = Instant.ofEpochMilli(event.getTimeStamp());
            final int breakIndex = PingRecovery.breakOf(result.breaks(), at);
            final Instant previous = previousOfBreak.put(breakIndex, at);
            md.append("- ").append(at).append(" (")
                    .append(breakIndex < 0 ? "before any break" : result.breaks().get(breakIndex).kind())
                    .append(previous == null ? ", first" : String.format(", +%.1f s",
                            Duration.between(previous, at).toMillis() / 1000.0))
                    .append("): `").append(event.getFormattedMessage()).append("`\n");
        }
        final List<List<String>> resumeRows = result.resumes().stream().map(r -> List.of(r.kind(),
                r.warning().map(w -> "`" + (w.cursor().isEmpty() ? "the start" : w.cursor()) + "` ("
                        + w.at() + ")").orElse("**no WARN**"),
                String.valueOf(r.failedAttempts()),
                r.lastBefore().isEmpty() ? "—" : "`" + r.lastBefore() + "`",
                String.valueOf(r.lastBeforeIndex()),
                r.firstAfter().isEmpty() ? "—" : "`" + r.firstAfter() + "`",
                String.valueOf(r.firstAfterIndex()),
                r.contiguous() ? "yes" : "NO",
                r.sinceTrigger().map(PersistencePingBench::seconds).orElse("—"),
                r.sinceWarning().map(PersistencePingBench::seconds).orElse("—"))).toList();
        return md.append("\n## Resume points\n\n")
                .append("Each break is tied to the window it failed: the cursor (`after <…>`) of the first ")
                .append("read-journal WARN logged at or after its trigger and before the next trigger. The read ")
                .append("journal fetches a page ahead, so when a trigger fires the walk may still be delivering a ")
                .append("page it holds, and the window that fails is a later one. The walk resumed right after the ")
                .append("failed window if the last pid delivered up to the cursor and the first one after it are ")
                .append("neighbours in the expected order. A break with no WARN had no effect and fails the stage.\n\n")
                .append(BenchProtocol.table(List.of("break", "failed window: cursor (first WARN)",
                        "failed attempts of that window", "last pid ≤ cursor", "its index (expected order)",
                        "first pid > cursor", "its index", "contiguous", "s from the trigger to the first pid > cursor",
                        "s from the first WARN"), resumeRows))
                .append("\nA failed window is retried as a whole from its cursor; the rows of the failed attempt are ")
                .append("discarded (the window is read completely before it is emitted), so an ID-order walk ")
                .append("delivers no pid twice. Observed: ").append(check.duplicates().size())
                .append(" duplicates.\n")
                .toString();
    }

    private static String seconds(final Duration duration) {
        return String.format("%.2f", duration.toNanos() / 1e9);
    }

    private static String actorMarkdown(final PingActorRun.Result result, final List<String> expected,
            final String failAt, final Duration interval, final long rate, final HostMemory.Sample before,
            final HostMemory.Sample after) {
        final int f = expected.indexOf(failAt);
        final int k = result.walks().size() > 1 ? result.walks().get(1).pinged().size() : 0;
        final List<List<String>> rows = new ArrayList<>();
        for (final PingActorRun.ActorWalk walk : result.walks()) {
            final List<String> walkExpected = switch (walk.number()) {
                case 2 -> expected.subList(0, Math.min(k, expected.size()));
                case 3 -> expected.subList(Math.min(k, expected.size()), expected.size());
                default -> expected;
            };
            final PingWalks.Check check = PingWalks.check(walk.pinged(), walkExpected, true);
            rows.add(List.of(String.valueOf(walk.number()), String.valueOf(walk.start()), String.valueOf(walk.end()),
                    String.format("%.1f s", Duration.between(walk.start(), walk.end()).toMillis() / 1000.0),
                    walk.completed() ? "yes" : "no",
                    walk.resumedAfter() == null ? "—" : "`" + walk.resumedAfter() + "`",
                    String.format("%,d", walk.pinged().size()), String.format("%,d", walkExpected.size()),
                    String.valueOf(check.missing().size()), String.valueOf(check.extra().size()),
                    String.valueOf(check.duplicates().size()), "`" + walk.endLog() + "`"));
        }
        return "\n## Settings\n\n"
                + "- the production PersistencePingActor with the policies ping settings: journal-tag `"
                + PingTagMix.TAG + "`, streaming-order ID, read-journal-batch-size " + PingWalks.BATCH
                + ", initial-delay 0s, interval " + interval.toSeconds() + " s; only the rate is raised, to " + rate
                + " pings/s (policies.conf ships 1/s)\n"
                + "- client: exactly the production pool (`ConnectionPoolFactory.createConnectionPool`), "
                + "`PostgresReadJournal` over `things_*`; a TestKit is the shard region\n"
                + String.format("- injection point: the source of the 2nd walk fails at `%s`, index %,d of the %,d "
                + "expected pids%n%n", failAt, f, expected.size())
                + "## Walks\n\n"
                + BenchProtocol.table(List.of("walk", "started (UTC)", "ended", "duration", "completed",
                "continued after", "pinged", "expected", "missing", "extra", "dup", "end log (verbatim)"), rows)
                + String.format("%nWalk 2 pinged %,d of the %,d pids before the injection point. A failing source may "
                + "drop the element buffered in front of the throttle; walk 3 continued after the last pid walk 2 "
                + "reached.%n%n", k, f)
                + "- skipped ticks (\"Another ping iteration is currently in progress\"): " + result.skippedTicks()
                + "\n"
                + "- other warnings: " + (result.otherWarnings().isEmpty() ? "none" : result.otherWarnings().size()
                + ", first " + result.otherWarnings().subList(0, Math.min(10, result.otherWarnings().size()))) + "\n"
                + "- host memory before: " + before.line() + "\n"
                + "- host memory after: " + after.line() + "\n"
                + "- host memory verdict: " + HostMemory.verdict(before, after) + "\n";
    }
}

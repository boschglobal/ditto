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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.Assume.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.apache.pekko.Done;
import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.SystemMaterializer;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.ConnectionPoolFactory;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.DittoPostgresClient;
import org.eclipse.ditto.internal.utils.persistence.postgres.client.config.PostgresConfig;
import org.eclipse.ditto.internal.utils.persistence.postgres.readjournal.PostgresReadJournal;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The persistence-ping harness on a small partly tagged corpus (Testcontainers postgres:16): the walks, a terminated
 * window and the PersistencePingActor deliver exactly the pids the tag mix says, and nothing else.
 */
public final class PersistencePingIT {

    private static final CorpusGenerator GEN = new CorpusGenerator(42L, 5_000L);
    private static final PingTagMix MIX = PingTagMix.of(GEN);

    private static PostgreSQLContainer<?> postgres;
    private static ActorSystem system;
    private static Materializer mat;

    @BeforeClass
    public static void start() throws Exception {
        assumeTrue("Docker is not available - skipping", DockerClientFactory.instance().isDockerAvailable());
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("bench").withUsername("bench").withPassword("bench");
        postgres.start();
        try (Connection connection = jdbc()) {
            PgLoadBench.run(connection, GEN, MIX, false);
        }
        system = ActorSystem.create("PersistencePingIT");
        mat = SystemMaterializer.get(system).materializer();
    }

    @AfterClass
    public static void stop() {
        if (system != null) {
            TestKit.shutdownActorSystem(system);
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    public void loadedTagsFollowTheMix() throws Exception {
        try (Connection connection = jdbc();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT sn, tags::text FROM things_journal WHERE pid = ? ORDER BY sn")) {
            for (final PingTagMix.Group group : PingTagMix.Group.values()) {
                final String pid = MIX.pids(group).get(0);
                final PingTagMix.Placement placement = MIX.placement(PingTagMix.indexOf(pid));
                ps.setString(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        assertThat(rs.getString(2)).as(group + " " + pid + " sn " + rs.getLong(1))
                                .isEqualTo(placement.tagsLiteral(rs.getLong(1)));
                    }
                }
            }
        }
    }

    @Test
    public void idOrderAndPriorityWalksDeliverExactlyTheExpectedPids() throws Exception {
        final StatementTimer timer = new StatementTimer();
        final DittoPostgresClient client = PingWalks.timedClient(config("ping-walk-it"), timer);
        try {
            assertThat(PingWalks.showStatementTimeout(client, mat)).isEqualTo("1min");
            final PostgresReadJournal journal = PingWalks.readJournal(client, "things");
            final PingWalks.Walk any = PingWalks.walk("any", journal.getJournalPidsWithTag(PingTagMix.TAG,
                    PingWalks.BATCH, PingWalks.MAX_IDLE_TIME, mat, false), timer, mat, Duration.ofMinutes(2));
            assertThat(PingWalks.check(any.delivered(), MIX.expectedIdOrder(false), true).problems()).isEmpty();
            assertThat(any.statements()).as("10 windows + the empty one").hasSize(11)
                    .allSatisfy(s -> assertThat(s.success()).isTrue());
            assertThat(any.statements()).allSatisfy(s -> assertThat(s.rows()).isLessThanOrEqualTo(PingWalks.BATCH));

            final PingWalks.Walk newest = PingWalks.walk("newest", journal.getJournalPidsWithTag(PingTagMix.TAG,
                    PingWalks.BATCH, PingWalks.MAX_IDLE_TIME, mat, true), timer, mat, Duration.ofMinutes(2));
            assertThat(PingWalks.check(newest.delivered(), MIX.expectedIdOrder(true), true).problems()).isEmpty();
            assertThat(newest.statements()).allSatisfy(s -> assertThat(s.rows()).isLessThanOrEqualTo(PingWalks.BATCH));

            final PingWalks.Walk priority = PingWalks.walk("priority",
                    journal.getJournalPidsWithTagOrderedByPriorityTag(PingTagMix.TAG, PingWalks.MAX_IDLE_TIME), timer,
                    mat, Duration.ofMinutes(2));
            assertThat(PingWalks.check(priority.delivered(), MIX.expectedIdOrder(false), false).problems()).isEmpty();
            assertThat(PingWalks.priorityViolations(priority.delivered(), MIX.priorityByPid())).isEmpty();
            assertThat(priority.statements()).as("the same 10 windows + the empty one as the ID order").hasSize(11)
                    .allSatisfy(s -> {
                        assertThat(s.success()).isTrue();
                        assertThat(s.rows()).isLessThanOrEqualTo(PingWalks.BATCH);
                    });
        } finally {
            client.close();
        }
    }

    @Test
    public void aTerminatedWindowIsRetriedFromItsCursorWithoutGapOrDuplicate() throws Exception {
        final ListAppender<ILoggingEvent> warnings = PingRecovery.captureReadJournalWarnings();
        final DittoPostgresClient client = PingWalks.productionClient(config("ping-recovery-it"));
        try {
            final List<String> expected = MIX.expectedIdOrder(false);
            // throttled so that the walk is still running when the lock is taken
            final Source<String, NotUsed> walk = PingWalks.readJournal(client, "things")
                    .getJournalPidsWithTag(PingTagMix.TAG, PingWalks.BATCH, PingWalks.MAX_IDLE_TIME, mat, false)
                    .throttle(500, Duration.ofSeconds(1));
            final PingRecovery.Result result = PingRecovery.run(walk, mat, expected, List.of(new PingRecovery.Trigger(
                    PingRecovery.TERMINATE, expected.size() / 3, () -> terminateWhileLocked("ping-recovery-it"))),
                    warnings, Duration.ofMinutes(2));
            assertThat(result.walkFailure()).isEmpty();
            final PingWalks.Check check = PingWalks.check(result.delivered(), expected, true);
            assertThat(check.problems()).isEmpty();
            assertThat(result.breaks()).hasSize(1).allSatisfy(b -> assertThat(b.failure()).isEmpty());
            assertThat(result.resumes()).allSatisfy(r -> {
                assertThat(r.warning()).as("the terminated window's WARN").isPresent();
                assertThat(r.contiguous()).as(r.toString()).isTrue();
            });
            assertThat(PingRecovery.offenders(result, check, false)).isEmpty();
            assertThat(warnings.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("Page query of read-journal stream <pids with tag <always-alive>>")
                    .contains("failed on attempt <1>"));
        } finally {
            client.close();
            PingRecovery.release(warnings);
        }
    }

    @Test
    public void aTerminatedPriorityWindowIsRetriedAndTheWalkStillDeliversEveryPidOnceInPriorityOrder()
            throws Exception {
        final String app = "ping-priority-break-it";
        final ListAppender<ILoggingEvent> warnings = PingRecovery.captureReadJournalWarnings();
        final DittoPostgresClient client = PingWalks.productionClient(config(app));
        try {
            final List<String> delivered = new CopyOnWriteArrayList<>();
            final CompletableFuture<Done> done;
            final String terminated;
            try (Connection locker = jdbc()) {
                locker.setAutoCommit(false);
                try (Statement st = locker.createStatement()) {
                    st.execute("LOCK TABLE things_journal IN ACCESS EXCLUSIVE MODE"); // the first window waits: active
                }
                try {
                    done = PingWalks.readJournal(client, "things")
                            .getJournalPidsWithTagOrderedByPriorityTag(PingTagMix.TAG, PingWalks.MAX_IDLE_TIME)
                            .runForeach(delivered::add, mat).toCompletableFuture();
                    terminated = PingRecovery.terminateActiveWindow(PersistencePingIT::jdbc, app,
                            Duration.ofSeconds(30)).call();
                } finally {
                    locker.rollback();
                }
            }
            done.get(2, TimeUnit.MINUTES);

            assertThat(terminated).endsWith("terminated: true");
            assertThat(PingWalks.check(delivered, MIX.expectedIdOrder(false), false).problems()).isEmpty();
            assertThat(PingWalks.priorityViolations(delivered, MIX.priorityByPid())).isEmpty();
            assertThat(warnings.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("Page query of read-journal stream <pids with tag <always-alive> in priority order>")
                    .contains("after <the start> failed on attempt <1>"));
        } finally {
            client.close();
            PingRecovery.release(warnings);
        }
    }

    @Test
    public void aStatementCancelledByStatementTimeoutIsOneFailedRecord() {
        final StatementTimer timer = new StatementTimer();
        final ConnectionFactory factory =
                timer.decorate(ConnectionPoolFactory.createDdlConnectionFactory(config("timer-it")));
        final io.r2dbc.spi.Connection connection = Mono.from(factory.create()).block(Duration.ofSeconds(30));
        final Throwable failure;
        try {
            Flux.from(connection.createStatement("SET statement_timeout = '200ms'").execute())
                    .flatMap(Result::getRowsUpdated).blockLast(Duration.ofSeconds(30));
            failure = catchThrowable(() -> Flux.from(connection.createStatement("SELECT pg_sleep(5)").execute())
                    .flatMap(result -> result.map((row, meta) -> 1)).blockLast(Duration.ofSeconds(30)));
        } finally {
            Mono.from(connection.close()).block(Duration.ofSeconds(30));
        }

        assertThat(StatementTimer.describe(failure)).contains("57014");
        assertThat(timer.statements().stream().filter(s -> s.sql().contains("pg_sleep")).toList())
                .as("one record per execution, however often r2dbc-proxy reports its end")
                .singleElement().satisfies(s -> assertThat(s.success()).isFalse());
    }

    @Test
    public void theActorPingsEveryExpectedPidOncePerWalkAndRecoversFromAnInjectedFailure() throws Exception {
        final DittoPostgresClient client = PingWalks.productionClient(config("ping-actor-it"));
        try {
            final List<String> expected = MIX.expectedIdOrder(false);
            final String failAt = expected.get(expected.size() / 2);
            final PingActorRun.Result result = PingActorRun.run(system,
                    PingActorRun.failingOnce(PingWalks.readJournal(client, "things"), 2, failAt),
                    PingActorRun.pingConfig(Duration.ofSeconds(2), 1_000L), 4, Duration.ofSeconds(90));
            assertThat(PingActorRun.verify(result.walks(), expected, failAt)).isEmpty();
            assertThat(result.walks().get(1).endLog()).contains("injected walk failure at <" + failAt + ">");
        } finally {
            client.close();
        }
    }

    private static String terminateWhileLocked(final String applicationName) throws Exception {
        try (Connection locker = jdbc()) {
            locker.setAutoCommit(false);
            try (Statement st = locker.createStatement()) {
                st.execute("LOCK TABLE things_journal IN ACCESS EXCLUSIVE MODE"); // the next window waits: active
            }
            try {
                return PingRecovery.terminateActiveWindow(PersistencePingIT::jdbc, applicationName,
                        Duration.ofSeconds(30)).call();
            } finally {
                locker.rollback();
            }
        }
    }

    private static PostgresConfig config(final String applicationName) {
        return PingWalks.postgresConfig(postgres.getJdbcUrl(), "bench", "bench", applicationName);
    }

    private static Connection jdbc() throws SQLException {
        final Properties props = new Properties();
        props.setProperty("user", "bench");
        props.setProperty("password", "bench");
        props.setProperty("prepareThreshold", "0");
        return DriverManager.getConnection(postgres.getJdbcUrl(), props);
    }
}

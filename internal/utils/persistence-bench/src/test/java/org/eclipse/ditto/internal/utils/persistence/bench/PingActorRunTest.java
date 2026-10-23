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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.pekko.Done;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.SystemMaterializer;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.internal.utils.persistence.api.DittoReadJournal;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public final class PingActorRunTest {

    private static final List<String> EXPECTED = List.of("thing:n:a", "thing:n:b", "thing:n:c", "thing:n:d");
    private static final String FAIL_AT = "thing:n:c";
    private static ActorSystem system;

    @BeforeClass
    public static void start() {
        system = ActorSystem.create("PingActorRunTest");
    }

    @AfterClass
    public static void stop() {
        TestKit.shutdownActorSystem(system);
    }

    @Test
    public void fourCorrectWalksHaveNoProblem() {
        assertThat(PingActorRun.verify(walks(EXPECTED, List.of("thing:n:a")), EXPECTED, FAIL_AT)).isEmpty();
    }

    @Test
    public void aGapOrADuplicateInTheResumedWalkIsAProblem() {
        final List<PingActorRun.ActorWalk> gap = new ArrayList<>(walks(EXPECTED, List.of("thing:n:a")));
        gap.set(2, walk(3, true, "thing:n:a", List.of("thing:n:c", "thing:n:d")));
        assertThat(PingActorRun.verify(gap, EXPECTED, FAIL_AT)).anyMatch(p -> p.contains("walk 3"));
        final List<PingActorRun.ActorWalk> twice = new ArrayList<>(walks(EXPECTED, List.of("thing:n:a")));
        twice.set(0, walk(1, true, null, List.of("thing:n:a", "thing:n:a", "thing:n:b", "thing:n:c", "thing:n:d")));
        assertThat(PingActorRun.verify(twice, EXPECTED, FAIL_AT)).anyMatch(p -> p.contains("duplicates"));
    }

    @Test
    public void theWrapperFailsOnlyTheChosenCallAtTheChosenPid() throws Exception {
        final DittoReadJournal stub = (DittoReadJournal) Proxy.newProxyInstance(DittoReadJournal.class.getClassLoader(),
                new Class<?>[]{DittoReadJournal.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getJournalPidsWithTag" -> Source.from(EXPECTED);
                    case "ensureTagPidIndex" -> CompletableFuture.completedFuture(Done.done());
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final DittoReadJournal wrapped = PingActorRun.failingOnce(stub, 2, FAIL_AT);
        final Materializer mat = SystemMaterializer.get(system).materializer();
        assertThat(drain(wrapped, mat)).containsExactlyElementsOf(EXPECTED);
        final List<String> seen = new ArrayList<>();
        assertThatThrownBy(() -> wrapped.getJournalPidsWithTag("t", 1, Duration.ZERO, mat, false)
                .runForeach(seen::add, mat).toCompletableFuture().get(10, TimeUnit.SECONDS))
                .hasCauseInstanceOf(PingActorRun.InjectedWalkFailure.class);
        assertThat(seen).containsExactly("thing:n:a", "thing:n:b");
        assertThat(drain(wrapped, mat)).containsExactlyElementsOf(EXPECTED);
    }

    private static List<String> drain(final DittoReadJournal journal, final Materializer mat) throws Exception {
        return journal.getJournalPidsWithTag("t", 1, Duration.ZERO, mat, false).runWith(Sink.seq(), mat)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    public void fewerThanFourWalksIsAProblem() {
        assertThat(PingActorRun.verify(walks(EXPECTED, List.of("thing:n:a")).subList(0, 3), EXPECTED, FAIL_AT))
                .singleElement().satisfies(p -> assertThat(p).startsWith("only 3 walks ended"));
    }

    @Test
    public void aSecondWalkThatCompletedPingedNothingOrFailedForAnotherReasonIsAProblem() {
        final List<PingActorRun.ActorWalk> completed = new ArrayList<>(walks(EXPECTED, List.of("thing:n:a")));
        completed.set(1, walk(2, true, null, List.of("thing:n:a")));
        assertThat(PingActorRun.verify(completed, EXPECTED, FAIL_AT)).anyMatch(p -> p.contains("walk 2 completed"));

        final List<PingActorRun.ActorWalk> nothing = new ArrayList<>(walks(EXPECTED, List.of()));
        assertThat(PingActorRun.verify(nothing, EXPECTED, FAIL_AT))
                .singleElement().satisfies(p -> assertThat(p).contains("before it pinged a pid"));

        final List<PingActorRun.ActorWalk> otherCause = new ArrayList<>(walks(EXPECTED, List.of("thing:n:a")));
        otherCause.set(1, new PingActorRun.ActorWalk(2, Instant.EPOCH, Instant.EPOCH, false, null,
                List.of("thing:n:a"), "Ping iteration failed after pinging <1> … Cause: connection lost"));
        assertThat(PingActorRun.verify(otherCause, EXPECTED, FAIL_AT))
                .anyMatch(p -> p.contains("did not end with the injected failure"));
    }

    @Test
    public void aWrongResumePointOrAResumedFourthWalkIsAProblem() {
        final List<PingActorRun.ActorWalk> wrongResume = new ArrayList<>(walks(EXPECTED, List.of("thing:n:a")));
        wrongResume.set(2, walk(3, true, "thing:n:b", List.of("thing:n:b", "thing:n:c", "thing:n:d")));
        assertThat(PingActorRun.verify(wrongResume, EXPECTED, FAIL_AT))
                .anyMatch(p -> p.contains("walk 3 continued after <thing:n:b>"));
        final List<PingActorRun.ActorWalk> resumedFourth = new ArrayList<>(walks(EXPECTED, List.of("thing:n:a")));
        resumedFourth.set(3, walk(4, true, "thing:n:a", EXPECTED));
        assertThat(PingActorRun.verify(resumedFourth, EXPECTED, FAIL_AT))
                .anyMatch(p -> p.contains("walk 4 continued after"));
    }

    @Test
    public void strayPingsOtherWarningsAndErrorsAreProblems() {
        final List<PingActorRun.ActorWalk> good = walks(EXPECTED, List.of("thing:n:a"));
        assertThat(PingActorRun.verify(new PingActorRun.Result(good, 0, List.of()), EXPECTED, FAIL_AT)).isEmpty();
        assertThat(PingActorRun.verify(new PingActorRun.Result(good, 0,
                        List.of("ping outside a walk: thing:n:x", "ERROR from PersistencePingActor: boom")),
                EXPECTED, FAIL_AT))
                .hasSize(2).allSatisfy(p -> assertThat(p).startsWith("unexpected during the run: "));
    }

    /** Walk 1 full, walk 2 failed after the given prefix by the injected failure, walk 3 the rest, walk 4 full. */
    private static List<PingActorRun.ActorWalk> walks(final List<String> expected, final List<String> prefix) {
        final String last = prefix.isEmpty() ? null : prefix.get(prefix.size() - 1);
        return List.of(walk(1, true, null, expected),
                failedWalk(prefix),
                walk(3, true, last, expected.subList(prefix.size(), expected.size())),
                walk(4, true, null, expected));
    }

    private static PingActorRun.ActorWalk failedWalk(final List<String> prefix) {
        return new PingActorRun.ActorWalk(2, Instant.EPOCH, Instant.EPOCH, false, null, prefix,
                "Ping iteration failed after pinging <" + prefix.size() + "> … Cause: injected walk failure at <"
                        + FAIL_AT + ">");
    }

    private static PingActorRun.ActorWalk walk(final int number, final boolean completed, final String resumedAfter,
            final List<String> pinged) {
        return new PingActorRun.ActorWalk(number, Instant.EPOCH, Instant.EPOCH, completed, resumedAfter, pinged,
                completed ? "Ping iteration completed: pinged <" + pinged.size() + ">" : "failed");
    }
}

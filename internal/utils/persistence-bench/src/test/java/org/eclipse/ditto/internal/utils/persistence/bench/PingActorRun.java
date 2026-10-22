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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.event.Logging;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.internal.utils.pekko.PingCommand;
import org.eclipse.ditto.internal.utils.persistence.api.DittoReadJournal;
import org.eclipse.ditto.internal.utils.persistentactors.PersistencePingActor;
import org.eclipse.ditto.internal.utils.persistentactors.config.DefaultPingConfig;
import org.eclipse.ditto.internal.utils.persistentactors.config.PingConfig;

import com.typesafe.config.ConfigFactory;

/**
 * The production PersistencePingActor over a read journal: a TestKit is its shard region and also receives the
 * actor's Info/Warning log events, which split the pings into walks.
 */
final class PingActorRun {

    record ActorWalk(int number, Instant start, Instant end, boolean completed, String resumedAfter,
            List<String> pinged, String endLog) {}

    record Result(List<ActorWalk> walks, int skippedTicks, List<String> otherWarnings) {}

    /** Thrown by {@link #failingOnce} into the chosen walk's source. */
    static final class InjectedWalkFailure extends IllegalStateException {

        InjectedWalkFailure(final String pid) {
            super("injected walk failure at <" + pid + ">");
        }
    }

    private PingActorRun() {}

    /** The policies ping settings (policies.conf) except the interval, if given, and the raised rate. */
    static PingConfig pingConfig(final Duration interval, final long pingsPerSecond) {
        return DefaultPingConfig.of(ConfigFactory.parseString("persistence-ping {\n"
                + "  journal-tag = \"" + PingTagMix.TAG + "\"\n"
                + "  initial-delay = 0s\n"
                + "  interval = " + interval.toSeconds() + "s\n"
                + "  read-journal-batch-size = " + PingWalks.BATCH + "\n"
                + "  streaming-order = ID\n"
                + "  rate { frequency = 1s, entities = " + pingsPerSecond + " }\n"
                + "}\n"));
    }

    /** The delegate, except that the source of its {@code failingCall}-th ID-order walk fails at {@code failAtPid}. */
    static DittoReadJournal failingOnce(final DittoReadJournal delegate, final int failingCall,
            final String failAtPid) {
        final AtomicInteger calls = new AtomicInteger();
        final InvocationHandler handler = (proxy, method, args) -> {
            final Object result;
            try {
                result = method.invoke(delegate, args);
            } catch (final InvocationTargetException e) {
                throw e.getCause();
            }
            if ("getJournalPidsWithTag".equals(method.getName()) && calls.incrementAndGet() == failingCall) {
                @SuppressWarnings("unchecked") final Source<String, NotUsed> source = (Source<String, NotUsed>) result;
                return source.map(pid -> {
                    if (pid.equals(failAtPid)) {
                        throw new InjectedWalkFailure(pid);
                    }
                    return pid;
                });
            }
            return result;
        };
        return (DittoReadJournal) Proxy.newProxyInstance(DittoReadJournal.class.getClassLoader(),
                new Class<?>[]{DittoReadJournal.class}, handler);
    }

    static Result run(final ActorSystem system, final DittoReadJournal readJournal, final PingConfig config,
            final int walkCount, final Duration bound) {
        final TestKit collector = new TestKit(system);
        system.getEventStream().subscribe(collector.getRef(), Logging.Info.class);
        system.getEventStream().subscribe(collector.getRef(), Logging.Warning.class);
        final ActorRef ping = system.actorOf(PersistencePingActor.props(collector.getRef(), config, readJournal));
        final List<ActorWalk> walks = new ArrayList<>();
        final List<String> otherWarnings = new ArrayList<>();
        int skippedTicks = 0;
        Instant start = null;
        String resumedAfter = null;
        List<String> pinged = null;
        final long deadline = System.nanoTime() + bound.toNanos();
        try {
            while (walks.size() < walkCount && System.nanoTime() < deadline) {
                final Object message = collector.receiveOne(Duration.ofNanos(deadline - System.nanoTime()));
                if (message == null) {
                    break;
                }
                if (message instanceof PingCommand command) {
                    final String pid = command.getEntityId().getEntityType() + ":" + command.getEntityId();
                    if (pinged != null) {
                        pinged.add(pid);
                    } else {
                        otherWarnings.add("ping outside a walk: " + pid);
                    }
                } else if (message instanceof Logging.LogEvent event
                        && event.logClass() == PersistencePingActor.class) {
                    final String text = String.valueOf(event.message());
                    if (text.contains("Sending ping for PersistenceActors")) {
                        start = Instant.now();
                        resumedAfter = null;
                        pinged = new ArrayList<>();
                    } else if (text.contains("Continuing after <")) {
                        resumedAfter = text.substring(text.indexOf('<') + 1, text.indexOf('>'));
                    } else if (pinged != null && (text.contains("Ping iteration completed")
                            || text.contains("Ping iteration failed"))) {
                        walks.add(new ActorWalk(walks.size() + 1, start, Instant.now(),
                                text.contains("Ping iteration completed"), resumedAfter, List.copyOf(pinged), text));
                        pinged = null;
                    } else if (text.contains("Another ping iteration is currently in progress")) {
                        skippedTicks++;
                    } else if (event instanceof Logging.Warning) {
                        otherWarnings.add(text);
                    }
                }
            }
        } finally {
            system.stop(ping);
            system.getEventStream().unsubscribe(collector.getRef());
        }
        return new Result(List.copyOf(walks), skippedTicks, List.copyOf(otherWarnings));
    }

    /** What is wrong with walks 1–4 (full, failed at {@code failAt}, resumed, full); empty when nothing is. */
    static List<String> verify(final List<ActorWalk> walks, final List<String> expected, final String failAt) {
        final List<String> problems = new ArrayList<>();
        if (walks.size() < 4) {
            problems.add("only " + walks.size() + " walks ended: " + walks);
            return problems;
        }
        expectWalk(problems, walks.get(0), expected);
        final ActorWalk failed = walks.get(1);
        final int k = failed.pinged().size();
        if (failed.completed()) {
            problems.add("walk 2 completed although its source failed at <" + failAt + ">");
        }
        if (k > expected.indexOf(failAt) || !failed.pinged().equals(expected.subList(0, k))) {
            problems.add("walk 2 pinged no prefix of the expected pids before <" + failAt + ">: "
                    + PingWalks.check(failed.pinged(), expected.subList(0, Math.min(k, expected.size())), true)
                    .problems());
        }
        if (!failed.endLog().contains("<" + k + ">")) {
            problems.add("walk 2's WARN does not name the <" + k + "> pings: " + failed.endLog());
        }
        final ActorWalk resumed = walks.get(2);
        final String lastPinged = k == 0 ? null : expected.get(k - 1);
        if (!Objects.equals(resumed.resumedAfter(), lastPinged)) {
            problems.add("walk 3 continued after <" + resumed.resumedAfter() + ">, not after <" + lastPinged + ">");
        }
        expectWalk(problems, resumed, expected.subList(k, expected.size()));
        expectWalk(problems, walks.get(3), expected);
        if (walks.get(3).resumedAfter() != null) {
            problems.add("walk 4 continued after <" + walks.get(3).resumedAfter() + "> instead of from the start");
        }
        return problems;
    }

    private static void expectWalk(final List<String> problems, final ActorWalk walk, final List<String> expected) {
        if (!walk.completed()) {
            problems.add("walk " + walk.number() + " did not complete: " + walk.endLog());
        }
        PingWalks.check(walk.pinged(), expected, true).problems()
                .forEach(p -> problems.add("walk " + walk.number() + ": " + p));
    }
}

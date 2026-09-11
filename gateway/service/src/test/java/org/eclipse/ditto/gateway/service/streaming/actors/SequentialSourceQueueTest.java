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
package org.eclipse.ditto.gateway.service.streaming.actors;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.apache.pekko.Done;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.japi.Pair;
import org.apache.pekko.stream.OverflowStrategy;
import org.apache.pekko.stream.QueueOfferResult;
import org.apache.pekko.stream.javadsl.Keep;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;
import org.apache.pekko.stream.testkit.TestSubscriber;
import org.apache.pekko.stream.testkit.javadsl.TestSink;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit test for {@link SequentialSourceQueue}.
 */
public final class SequentialSourceQueueTest {

    private static final int BUFFER_SIZE = 2;
    private static final int BURST_SIZE = 200;
    private static final int MAX_PENDING = 4;
    private static final int UNREACHABLE_MAX_PENDING = 10_000;
    private static final long BOUNDED_WAIT_SECONDS = 10L;

    private static ActorSystem actorSystem;

    private final List<Integer> overflowed = Collections.synchronizedList(new ArrayList<>());

    @BeforeClass
    public static void setUpClass() {
        actorSystem = ActorSystem.create(SequentialSourceQueueTest.class.getSimpleName());
    }

    @AfterClass
    public static void tearDownClass() {
        TestKit.shutdownActorSystem(actorSystem);
    }

    /**
     * A small backpressured queue in front of a consumer slower than the producer.
     */
    private static Pair<SourceQueueWithComplete<Integer>, CompletionStage<List<Integer>>> slowConsumerQueue() {
        return Source.<Integer>queue(BUFFER_SIZE, OverflowStrategy.backpressure())
                .throttle(1, Duration.ofMillis(2))
                .toMat(Sink.seq(), Keep.both())
                .run(actorSystem);
    }

    /**
     * A small backpressured queue in front of a consumer which does not demand anything until it is told to.
     */
    private static Pair<SourceQueueWithComplete<Integer>, TestSubscriber.Probe<Integer>> stalledConsumerQueue() {
        return Source.<Integer>queue(BUFFER_SIZE, OverflowStrategy.backpressure())
                .toMat(TestSink.probe(actorSystem), Keep.both())
                .run(actorSystem);
    }

    @Test
    public void rawBackpressuredQueueLosesElementsWhenOffersAreNotAwaited() throws Exception {
        // guards the Pekko contract SequentialSourceQueue exists for: a queue materialized with
        // OverflowStrategy.backpressure rejects every offer issued while another one is still pending
        final var materialized = slowConsumerQueue();
        final SourceQueueWithComplete<Integer> rawQueue = materialized.first();

        final List<CompletionStage<QueueOfferResult>> offers = new ArrayList<>();
        IntStream.range(0, BURST_SIZE).forEach(i -> offers.add(rawQueue.offer(i)));

        final List<Throwable> failures = new ArrayList<>();
        for (final CompletionStage<QueueOfferResult> offer : offers) {
            offer.handle((result, failure) -> {
                if (failure != null) {
                    failures.add(failure);
                }
                return null;
            }).toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(failures)
                .as("offers rejected because a previous offer was still pending")
                .isNotEmpty()
                .allSatisfy(failure -> assertThat(failure).isInstanceOf(IllegalStateException.class));
    }

    @Test
    public void serializedOffersDeliverEveryElementInOrder() throws Exception {
        final var materialized = slowConsumerQueue();
        final SourceQueueWithComplete<Integer> queue =
                SequentialSourceQueue.of(materialized.first(), UNREACHABLE_MAX_PENDING, overflowed::add);

        final List<CompletionStage<QueueOfferResult>> offers = new ArrayList<>();
        IntStream.range(0, BURST_SIZE).forEach(i -> offers.add(queue.offer(i)));
        queue.complete();

        for (final CompletionStage<QueueOfferResult> offer : offers) {
            assertThat(offer.toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS))
                    .isEqualTo(QueueOfferResult.enqueued());
        }
        final List<Integer> consumed =
                materialized.second().toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(consumed).containsExactlyElementsOf(IntStream.range(0, BURST_SIZE).boxed().toList());
        assertThat(overflowed).as("dropped elements").isEmpty();
    }

    @Test
    public void completionIsQueuedBehindPendingOffers() throws Exception {
        final var materialized = slowConsumerQueue();
        final SourceQueueWithComplete<Integer> queue =
                SequentialSourceQueue.of(materialized.first(), UNREACHABLE_MAX_PENDING, overflowed::add);

        IntStream.range(0, 10).forEach(queue::offer);
        queue.complete();

        assertThat(materialized.second().toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS))
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThat(queue.watchCompletion().toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS))
                .isEqualTo(Done.getInstance());
    }

    @Test
    public void offersBeyondTheMaxPendingLimitAreDroppedAndReported() {
        final var materialized = stalledConsumerQueue();
        final SourceQueueWithComplete<Integer> queue =
                SequentialSourceQueue.of(materialized.first(), MAX_PENDING, overflowed::add);

        final List<CompletionStage<QueueOfferResult>> offers = new ArrayList<>();
        IntStream.range(0, BURST_SIZE).forEach(i -> offers.add(queue.offer(i)));

        final List<Integer> droppedElements = new ArrayList<>();
        for (int i = 0; i < BURST_SIZE; i++) {
            final CompletableFuture<QueueOfferResult> offer = offers.get(i).toCompletableFuture();
            if (offer.isDone() && !offer.isCompletedExceptionally() &&
                    QueueOfferResult.dropped().equals(offer.getNow(null))) {
                droppedElements.add(i);
            }
        }

        assertThat(overflowed)
                .as("elements reported to the overflow callback")
                .isNotEmpty()
                .containsExactlyElementsOf(droppedElements);
        assertThat(BURST_SIZE - overflowed.size())
                .as("elements the queue accepted while the consumer is stalled")
                .isLessThanOrEqualTo(MAX_PENDING + BUFFER_SIZE + 1);

        materialized.second().cancel();
    }

    @Test
    public void pendingOffersAreReleasedOnDeliverySoTheLimitIsReusable() throws Exception {
        final var materialized = stalledConsumerQueue();
        final SourceQueueWithComplete<Integer> queue =
                SequentialSourceQueue.of(materialized.first(), MAX_PENDING, overflowed::add);
        final TestSubscriber.Probe<Integer> consumer = materialized.second();

        final List<CompletionStage<QueueOfferResult>> offers = new ArrayList<>();
        IntStream.range(0, BURST_SIZE).forEach(i -> offers.add(queue.offer(i)));
        assertThat(overflowed).as("elements dropped while the consumer is stalled").isNotEmpty();

        // let the stalled consumer drain everything the queue accepted
        consumer.request(BURST_SIZE);
        for (final CompletionStage<QueueOfferResult> offer : offers) {
            assertThat(offer.toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS))
                    .isIn(QueueOfferResult.enqueued(), QueueOfferResult.dropped());
        }

        // the pending count is released by a callback on each offer's future, i.e. possibly after the future the
        // loop above awaited was already completed; the margin is the whole drained burst, so by now the count is
        // back at zero and the limit admits offers again
        final int droppedWhileStalled = overflowed.size();
        assertThat(queue.offer(BURST_SIZE).toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS))
                .as("offer issued after the pending offers were delivered")
                .isEqualTo(QueueOfferResult.enqueued());
        assertThat(overflowed).as("elements dropped after the drain").hasSize(droppedWhileStalled);

        consumer.cancel();
    }

    @Test
    public void failResolvesTheOfferParkedInTheDelegateAndEveryOfferBehindIt() throws Exception {
        final var materialized = stalledConsumerQueue();
        final TestSubscriber.Probe<Integer> consumer = materialized.second();
        final SourceQueueWithComplete<Integer> queue =
                SequentialSourceQueue.of(materialized.first(), UNREACHABLE_MAX_PENDING, overflowed::add);

        // fill the delegate's buffer, so that the next offer parks inside the delegate
        for (int i = 0; i < BUFFER_SIZE; i++) {
            assertThat(queue.offer(i).toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("offer into the free buffer")
                    .isEqualTo(QueueOfferResult.enqueued());
        }
        final List<CompletionStage<QueueOfferResult>> offers = new ArrayList<>();
        IntStream.range(BUFFER_SIZE, BUFFER_SIZE + 5).forEach(i -> offers.add(queue.offer(i)));
        // the stalled consumer demands nothing; by now the delegate has parked the first of the offers above
        consumer.ensureSubscription();
        consumer.expectNoMessage(Duration.ofMillis(500L));
        assertThat(offers.get(0).toCompletableFuture()).as("offer parked in the delegate").isNotDone();

        queue.fail(new IllegalStateException("session expired"));

        final List<Throwable> failures = new ArrayList<>();
        for (final CompletionStage<QueueOfferResult> offer : offers) {
            offer.handle((result, failure) -> {
                if (failure != null) {
                    failures.add(failure);
                }
                return null;
            }).toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS);
        }
        assertThat(failures).as("offers which could no longer be delivered").hasSize(offers.size());
        assertThat(offerOutcome(queue, BUFFER_SIZE + 5))
                .as("offer issued after the queue failed")
                .isInstanceOf(Throwable.class);
    }

    @Test
    public void offerAfterCompletionResolves() throws Exception {
        final var materialized = slowConsumerQueue();
        final SourceQueueWithComplete<Integer> queue =
                SequentialSourceQueue.of(materialized.first(), UNREACHABLE_MAX_PENDING, overflowed::add);

        IntStream.range(0, 10).forEach(queue::offer);
        queue.complete();

        assertThat(offerOutcome(queue, 10))
                .as("offer issued after the queue was completed")
                .satisfiesAnyOf(
                        outcome -> assertThat(outcome).isInstanceOf(Throwable.class),
                        outcome -> assertThat(outcome)
                                .isIn(QueueOfferResult.dropped(), QueueOfferResult.closed()));
    }

    @Test
    public void concurrentProducersLoseNothingAndKeepTheirOwnOrder() throws Exception {
        final int producers = 4;
        final int offersPerProducer = 50;
        final int offered = producers * offersPerProducer;
        final var materialized = slowConsumerQueue();
        final SourceQueueWithComplete<Integer> queue =
                SequentialSourceQueue.of(materialized.first(), MAX_PENDING, overflowed::add);

        // every producer offers <producerId * offersPerProducer + sequenceNumber>, so an element identifies both
        final List<CompletionStage<QueueOfferResult>> offers = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch startSignal = new CountDownLatch(1);
        final List<Thread> producerThreads = new ArrayList<>();
        for (int producer = 0; producer < producers; producer++) {
            final int producerId = producer;
            final Thread producerThread = new Thread(() -> {
                try {
                    startSignal.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < offersPerProducer; i++) {
                    offers.add(queue.offer(producerId * offersPerProducer + i));
                }
            }, "producer-" + producerId);
            producerThreads.add(producerThread);
            producerThread.start();
        }
        startSignal.countDown();
        for (final Thread producerThread : producerThreads) {
            producerThread.join(TimeUnit.SECONDS.toMillis(BOUNDED_WAIT_SECONDS));
            assertThat(producerThread.isAlive()).as("producer thread finished").isFalse();
        }

        final List<QueueOfferResult> results = new ArrayList<>();
        for (final CompletionStage<QueueOfferResult> offer : offers) {
            results.add(offer.toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS));
        }
        queue.complete();

        final long enqueued = results.stream().filter(QueueOfferResult.enqueued()::equals).count();
        final long dropped = results.stream().filter(QueueOfferResult.dropped()::equals).count();
        assertThat(results).as("resolved offers").hasSize(offered);
        assertThat(enqueued + dropped).as("every offer is either enqueued or dropped").isEqualTo(offered);
        assertThat(overflowed).as("elements reported to the overflow callback")
                .isNotEmpty()
                .hasSize((int) dropped);

        final List<Integer> consumed =
                materialized.second().toCompletableFuture().get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(consumed).as("delivered elements").hasSize((int) enqueued);
        for (int producer = 0; producer < producers; producer++) {
            final int producerId = producer;
            assertThat(consumed.stream().filter(element -> element / offersPerProducer == producerId).toList())
                    .as("elements of producer <" + producerId + "> in delivery order")
                    .isSorted();
        }
    }

    private static Object offerOutcome(final SourceQueueWithComplete<Integer> queue, final int element)
            throws Exception {

        return queue.offer(element)
                .handle((result, failure) -> null != failure ? failure : result)
                .toCompletableFuture()
                .get(BOUNDED_WAIT_SECONDS, TimeUnit.SECONDS);
    }

}

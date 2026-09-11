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

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkArgument;
import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.annotation.concurrent.ThreadSafe;

import org.apache.pekko.Done;
import org.apache.pekko.stream.QueueOfferResult;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;

/**
 * A {@link SourceQueueWithComplete} decorator that hands offers to the delegate strictly one after another.
 * <p>
 * A {@code Source.queue} materialized with {@code OverflowStrategy.backpressure} (see {@link SupervisedStream})
 * accepts at most ONE unresolved offer: while its buffer is full, the pending offer's future completes once space
 * is available, and every further {@code offer} issued before that fails with an {@link IllegalStateException}
 * ("You have to wait for previous offer to be resolved") — the element is never enqueued. A caller that offers
 * fire-and-forget therefore silently loses elements whenever a burst outruns the consumer, e.g. a websocket
 * session publishing a historical-events stream whose backend delivers all events at once.
 * <p>
 * This wrapper chains every offer (and the final {@link #complete()}) behind its predecessor, so the delegate never
 * sees a second offer before the first one resolved. Ordering is preserved. Because a consumer slower than the
 * producer would otherwise accumulate the pending elements in this chain without limit, at most
 * {@code maxPending} offers may wait for the delegate at any time: a further element is dropped instead of being
 * queued, its offer completes with {@link QueueOfferResult#dropped()} and the element is passed to the
 * {@code onOverflow} callback for the caller to log and count. The memory a single session may occupy is thereby
 * bounded by the delegate's buffer plus {@code maxPending} elements.
 *
 * @param <T> the element type.
 */
@ThreadSafe
final class SequentialSourceQueue<T> implements SourceQueueWithComplete<T> {

    private final SourceQueueWithComplete<T> delegate;
    private final int maxPending;
    private final Consumer<T> onOverflow;
    private final AtomicReference<CompletionStage<?>> tail;
    private final AtomicReference<CompletableFuture<QueueOfferResult>> inFlight;
    private final AtomicInteger pending;

    private SequentialSourceQueue(final SourceQueueWithComplete<T> delegate, final int maxPending,
            final Consumer<T> onOverflow) {

        this.delegate = delegate;
        this.maxPending = maxPending;
        this.onOverflow = onOverflow;
        tail = new AtomicReference<>(CompletableFuture.completedFuture(Done.getInstance()));
        inFlight = new AtomicReference<>(CompletableFuture.completedFuture(QueueOfferResult.enqueued()));
        pending = new AtomicInteger();
    }

    /**
     * Wraps the given queue so that offers are serialized and their number is bounded.
     *
     * @param delegate the queue to wrap.
     * @param maxPending the maximum number of offers that may wait for the delegate; further elements are dropped.
     * @param onOverflow called with each element that is dropped because {@code maxPending} is exceeded.
     * @param <T> the element type.
     * @return the serializing queue.
     * @throws NullPointerException if {@code delegate} or {@code onOverflow} is {@code null}.
     * @throws IllegalArgumentException if {@code maxPending} is not positive.
     */
    static <T> SourceQueueWithComplete<T> of(final SourceQueueWithComplete<T> delegate, final int maxPending,
            final Consumer<T> onOverflow) {

        return new SequentialSourceQueue<>(checkNotNull(delegate, "delegate"),
                checkArgument(maxPending, max -> max > 0, () -> "The maxPending offers must be positive!"),
                checkNotNull(onOverflow, "onOverflow"));
    }

    @Override
    public CompletionStage<QueueOfferResult> offer(final T elem) {
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet();
            onOverflow.accept(elem);
            return CompletableFuture.completedFuture(QueueOfferResult.dropped());
        }
        final CompletableFuture<QueueOfferResult> result = new CompletableFuture<>();
        // the element leaves the chain as soon as its offer resolved, whatever the outcome
        result.whenComplete((offerResult, offerFailure) -> pending.decrementAndGet());
        // the next offer waits for THIS one, whatever its outcome
        final CompletionStage<?> previous = tail.getAndSet(result);
        previous.whenComplete((ignored, previousFailure) -> {
            // remember the link handed to the delegate, so that fail() can resolve it, see there
            inFlight.set(result);
            try {
                delegate.offer(elem).whenComplete((offerResult, offerFailure) -> {
                    if (offerFailure != null) {
                        result.completeExceptionally(offerFailure);
                    } else {
                        result.complete(offerResult);
                    }
                });
            } catch (final RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    @Override
    public void complete() {
        // completing before the pending offers are enqueued would drop them; queue the completion behind them
        final CompletableFuture<Done> completed = new CompletableFuture<>();
        final CompletionStage<?> previous = tail.getAndSet(completed);
        previous.whenComplete((ignored, previousFailure) -> {
            try {
                delegate.complete();
            } finally {
                completed.complete(Done.getInstance());
            }
        });
    }

    @Override
    public void fail(final Throwable ex) {
        delegate.fail(ex);
        // a failed Pekko QueueSource never resolves the offer that is parked in it, which would leave the link
        // waiting for that offer - and the whole chain behind it - unresolved forever: resolve it here so that the
        // chain unwinds. Links dispatched afterwards reach the failed delegate and fail fast.
        inFlight.get().completeExceptionally(ex);
        // offers issued from now on must not wait for a link that is being dispatched concurrently
        tail.getAndSet(CompletableFuture.failedFuture(ex));
    }

    @Override
    public CompletionStage<Done> watchCompletion() {
        return delegate.watchCompletion();
    }

}

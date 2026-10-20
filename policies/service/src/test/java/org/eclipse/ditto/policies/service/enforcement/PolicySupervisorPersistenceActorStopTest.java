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
package org.eclipse.ditto.policies.service.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.apache.pekko.actor.AbstractActor;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.Props;
import org.apache.pekko.testkit.TestProbe;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.eclipse.ditto.base.model.auth.AuthorizationContext;
import org.eclipse.ditto.base.model.auth.AuthorizationSubject;
import org.eclipse.ditto.base.model.auth.DittoAuthorizationContextType;
import org.eclipse.ditto.base.model.common.HttpStatus;
import org.eclipse.ditto.base.model.exceptions.DittoRuntimeExceptionBuilder;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.signals.Signal;
import org.eclipse.ditto.base.model.signals.commands.streaming.SubscribeForPersistedEvents;
import org.eclipse.ditto.base.model.signals.events.Event;
import org.eclipse.ditto.base.service.actors.ShutdownBehaviour;
import org.eclipse.ditto.internal.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.internal.utils.persistence.mongo.streaming.MongoReadJournal;
import org.eclipse.ditto.internal.utils.persistentactors.AbstractPersistenceSupervisor;
import org.eclipse.ditto.internal.utils.tracing.DittoTracingInitResource;
import org.eclipse.ditto.policies.api.Permission;
import org.eclipse.ditto.policies.model.EffectedPermissions;
import org.eclipse.ditto.policies.model.PoliciesResourceType;
import org.eclipse.ditto.policies.model.Policy;
import org.eclipse.ditto.policies.model.PolicyEntry;
import org.eclipse.ditto.policies.model.PolicyId;
import org.eclipse.ditto.policies.model.Resource;
import org.eclipse.ditto.policies.model.Subject;
import org.eclipse.ditto.policies.model.SubjectId;
import org.eclipse.ditto.policies.model.SubjectIssuer;
import org.eclipse.ditto.policies.model.signals.commands.PolicyCommand;
import org.eclipse.ditto.policies.model.signals.commands.exceptions.PolicyUnavailableException;
import org.eclipse.ditto.policies.model.signals.commands.modify.CreatePolicy;
import org.eclipse.ditto.policies.model.signals.commands.modify.CreatePolicyResponse;
import org.eclipse.ditto.policies.service.common.config.DittoPoliciesConfig;
import org.eclipse.ditto.policies.service.common.config.PolicyConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;

import com.typesafe.config.ConfigFactory;

import scala.concurrent.duration.FiniteDuration;

/**
 * A persistence actor stops without answering when its recovery or a journal write fails, e.g. under persistence
 * overload. Its supervisor must then answer the signal that was waiting for that actor, and the signals stashed behind
 * it, at once with {@link PolicyUnavailableException}. It must not wait for the ask timeout
 * ({@code timeout-during-recovery}, 45 s by default) and then hand the stashed signals to the restarted persistence
 * actor after their senders have given up.
 */
public final class PolicySupervisorPersistenceActorStopTest {

    @ClassRule
    public static final DittoTracingInitResource DITTO_TRACING_INIT_RESOURCE =
            DittoTracingInitResource.disableDittoTracing();

    /**
     * Far below the 45 s {@code timeout-during-recovery} that an unrecovered persistence actor is asked with.
     */
    private static final Duration AT_ONCE = Duration.ofSeconds(3);

    private static final PolicyId POLICY_ID = PolicyId.of("my.namespace", "policyId");
    private static final SubjectId SUBJECT_ID = SubjectId.newInstance(SubjectIssuer.GOOGLE, "someId");
    private static final Policy POLICY = Policy.newBuilder(POLICY_ID)
            .set(PolicyEntry.newInstance("defaultLabel", Collections.singleton(Subject.newInstance(SUBJECT_ID)),
                    Collections.singleton(Resource.newInstance(PoliciesResourceType.policyResource("/"),
                            EffectedPermissions.newInstance(Permission.DEFAULT_POLICY_PERMISSIONS,
                                    Collections.emptySet())))))
            .build();

    private ActorSystem system;

    @Before
    public void init() {
        // no restart of a stopped persistence actor within a test: signals after the stop meet the back-off
        system = ActorSystem.create("test", ConfigFactory.parseString(
                        "ditto.policies.policy.supervisor.exponential-backoff { min = 10h, max = 10h }")
                .withFallback(ConfigFactory.load("test")));
    }

    @After
    public void shutdown() {
        if (system != null) {
            TestKit.shutdownActorSystem(system);
        }
    }

    @Test
    public void commandWhosePersistenceActorStopsWithoutAnswerIsAnsweredUnavailableAtOnce() {
        new TestKit(system) {{
            final TestProbe persistenceActorInbox = new TestProbe(system);
            final ActorRef supervisor =
                    startSupervisor(Props.create(StopsOnCommand.class, persistenceActorInbox.ref()));

            supervisor.tell(createPolicy("first"), getRef());

            persistenceActorInbox.expectMsgClass(CreatePolicy.class);
            expectUnavailable(this, "first");
        }};
    }

    @Test
    public void signalStashedBehindThatCommandIsAnsweredAtOnceAndNeverForwarded() {
        new TestKit(system) {{
            final TestProbe persistenceActorInbox = new TestProbe(system);
            final ActorRef supervisor =
                    startSupervisor(Props.create(StopsOnCommand.class, persistenceActorInbox.ref()));

            supervisor.tell(createPolicy("first"), getRef());
            supervisor.tell(createPolicy("second"), getRef()); // stashed until "first" is answered

            persistenceActorInbox.expectMsgClass(CreatePolicy.class);
            expectUnavailable(this, "first");
            expectUnavailable(this, "second");
            persistenceActorInbox.expectNoMessage(FiniteDuration.apply(1, TimeUnit.SECONDS));
        }};
    }

    @Test
    public void answerSentBeforeThePersistenceActorStopsIsKept() {
        // answer and stop happen in one message handling; repeated to expose an ordering race
        for (int round = 0; round < 20; round++) {
            final String correlationId = "round-" + round;
            new TestKit(system) {{
                final ActorRef supervisor = startSupervisor(Props.create(AnswersThenStops.class));

                supervisor.tell(createPolicy(correlationId), getRef());

                final CreatePolicyResponse response = expectMsgClass(AT_ONCE, CreatePolicyResponse.class);
                assertThat(response.getDittoHeaders().getCorrelationId()).contains(correlationId);
            }};
        }
    }

    @Test
    public void persistenceActorThatIsAliveButSilentIsStillGivenItsAskTimeout() {
        new TestKit(system) {{
            final ActorRef supervisor = startSupervisor(Props.create(Silent.class));

            supervisor.tell(createPolicy("first"), getRef());

            expectNoMessage(AT_ONCE);
        }};
    }

    private ActorRef startSupervisor(final Props persistenceActorProps) {
        final PolicyConfig policyConfig =
                DittoPoliciesConfig.of(DefaultScopedConfig.dittoScoped(system.settings().config()))
                        .getPolicyConfig();
        return system.actorOf(Props.create(TestPolicySupervisor.class,
                () -> new TestPolicySupervisor(policyConfig, persistenceActorProps)));
    }

    private static CreatePolicy createPolicy(final String correlationId) {
        return CreatePolicy.of(POLICY, DittoHeaders.newBuilder()
                .authorizationContext(AuthorizationContext.newInstance(DittoAuthorizationContextType.UNSPECIFIED,
                        AuthorizationSubject.newInstance(SUBJECT_ID)))
                .correlationId(correlationId)
                .build());
    }

    private static void expectUnavailable(final TestKit testKit, final String correlationId) {
        final PolicyUnavailableException unavailable =
                testKit.expectMsgClass(AT_ONCE, PolicyUnavailableException.class);
        assertThat(unavailable.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getDittoHeaders().getCorrelationId()).contains(correlationId);
    }

    /**
     * A persistence actor whose journal write (or recovery) fails: it receives the command and stops unanswered.
     */
    public static final class StopsOnCommand extends AbstractActor {

        private final ActorRef inbox;

        public StopsOnCommand(final ActorRef inbox) {
            this.inbox = inbox;
        }

        @Override
        public Receive createReceive() {
            return receiveBuilder()
                    .match(PolicyCommand.class, command -> {
                        inbox.tell(command, getSelf());
                        getContext().stop(getSelf());
                    })
                    .build();
        }
    }

    /**
     * A persistence actor that answers and then stops in the same message handling.
     */
    public static final class AnswersThenStops extends AbstractActor {

        @Override
        public Receive createReceive() {
            return receiveBuilder()
                    .match(CreatePolicy.class, createPolicy -> {
                        getSender().tell(CreatePolicyResponse.of(createPolicy.getEntityId(), createPolicy.getPolicy(),
                                createPolicy.getDittoHeaders()), getSelf());
                        getContext().stop(getSelf());
                    })
                    .build();
        }
    }

    /**
     * A persistence actor that is alive but never answers, e.g. one that is still recovering.
     */
    public static final class Silent extends AbstractActor {

        @Override
        public Receive createReceive() {
            return receiveBuilder().matchAny(message -> {}).build();
        }
    }

    /**
     * Lets every signal pass unchanged, command responses included ({@code CommandResponse} is a {@code Signal}).
     */
    public static final class PassThroughEnforcer extends AbstractActor {

        @Override
        public Receive createReceive() {
            return receiveBuilder()
                    .match(Signal.class, signal -> getSender().tell(signal, getSelf()))
                    .matchAny(message -> {}) // e.g. PA_RECOVERED
                    .build();
        }
    }

    private static final class TestPolicySupervisor
            extends AbstractPersistenceSupervisor<PolicyId, PolicyCommand<?>> {

        private final Props persistenceActorProps;

        private TestPolicySupervisor(final PolicyConfig policyConfig, final Props persistenceActorProps) {
            super(null, null, null, Mockito.mock(MongoReadJournal.class), policyConfig.getSupervisorConfig());
            this.persistenceActorProps = persistenceActorProps;
        }

        @Override
        protected PolicyId getEntityId() {
            return POLICY_ID;
        }

        @Override
        protected Props getPersistenceActorProps(final PolicyId entityId) {
            return persistenceActorProps;
        }

        @Override
        protected Props getPersistenceEnforcerProps(final PolicyId entityId) {
            return Props.create(PassThroughEnforcer.class);
        }

        @Override
        protected ShutdownBehaviour getShutdownBehaviour(final PolicyId entityId) {
            return ShutdownBehaviour.fromId(entityId, getContext().getSystem().deadLetters(), getSelf());
        }

        @Override
        protected boolean applyPersistedEventFilter(final Event<?> event,
                final SubscribeForPersistedEvents subscribe) {
            return true;
        }

        @Override
        protected DittoRuntimeExceptionBuilder<?> getUnavailableExceptionBuilder(@Nullable final PolicyId entityId) {
            return PolicyUnavailableException.newBuilder(null != entityId ? entityId : POLICY_ID);
        }
    }

}

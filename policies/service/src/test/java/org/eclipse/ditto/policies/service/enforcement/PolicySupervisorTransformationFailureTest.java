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
import org.eclipse.ditto.base.model.signals.commands.streaming.SubscribeForPersistedEvents;
import org.eclipse.ditto.base.model.signals.events.Event;
import org.eclipse.ditto.base.service.actors.ShutdownBehaviour;
import org.eclipse.ditto.internal.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.internal.utils.persistence.mongo.streaming.MongoReadJournal;
import org.eclipse.ditto.internal.utils.persistentactors.AbstractPersistenceSupervisor;
import org.eclipse.ditto.internal.utils.tracing.DittoTracingInitResource;
import org.eclipse.ditto.policies.api.Permission;
import org.eclipse.ditto.policies.api.commands.sudo.SudoRetrievePolicy;
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
import org.eclipse.ditto.policies.model.signals.commands.modify.ModifyPolicy;
import org.eclipse.ditto.policies.service.common.config.DittoPoliciesConfig;
import org.eclipse.ditto.policies.service.common.config.PolicyConfig;
import org.eclipse.ditto.policies.service.enforcement.pre.ModifyToCreatePolicyTransformer;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;

import com.typesafe.config.ConfigFactory;

/**
 * A {@code ModifyPolicy} first goes through {@link ModifyToCreatePolicyTransformer}, which asks the persistence actor
 * whether the policy exists. When the persistence is overloaded, that actor stops without replying (failed recovery or
 * journal write, e.g. an open circuit breaker) or is not running (back-off), so the existence check fails. The
 * supervisor must answer with {@link PolicyUnavailableException} (503), not with an internal error (500).
 */
public final class PolicySupervisorTransformationFailureTest {

    @ClassRule
    public static final DittoTracingInitResource DITTO_TRACING_INIT_RESOURCE =
            DittoTracingInitResource.disableDittoTracing();

    private static final PolicyId POLICY_ID = PolicyId.of("my.namespace", "policyId");
    private static final String CORRELATION_ID = "transformation-failure";
    private static final SubjectId SUBJECT_ID = SubjectId.newInstance(SubjectIssuer.GOOGLE, "someId");
    private static final DittoHeaders DITTO_HEADERS = DittoHeaders.newBuilder()
            .authorizationContext(AuthorizationContext.newInstance(DittoAuthorizationContextType.UNSPECIFIED,
                    AuthorizationSubject.newInstance(SUBJECT_ID)))
            .correlationId(CORRELATION_ID)
            .build();
    private static final Policy POLICY = Policy.newBuilder(POLICY_ID)
            .set(PolicyEntry.newInstance("defaultLabel", Collections.singleton(Subject.newInstance(SUBJECT_ID)),
                    Collections.singleton(Resource.newInstance(PoliciesResourceType.policyResource("/"),
                            EffectedPermissions.newInstance(Permission.DEFAULT_POLICY_PERMISSIONS,
                                    Collections.emptySet())))))
            .build();

    private ActorSystem system;

    @Before
    public void init() {
        system = ActorSystem.create("test", ConfigFactory.parseString(
                        "ditto.extensions.signal-transformers-provider.extension-config.signal-transformers = [\""
                                + ModifyToCreatePolicyTransformer.class.getName() + "\"]")
                .withFallback(ConfigFactory.load("test")));
    }

    @After
    public void shutdown() {
        if (system != null) {
            TestKit.shutdownActorSystem(system);
        }
    }

    @Test
    public void unansweredExistenceCheckIsAnsweredUnavailable() {
        new TestKit(system) {{
            final TestProbe persistenceActor = new TestProbe(system);
            final ActorRef supervisor = startSupervisor(persistenceActor.ref(), null);

            supervisor.tell(ModifyPolicy.of(POLICY_ID, POLICY, DITTO_HEADERS), getRef());

            persistenceActor.expectMsgClass(SudoRetrievePolicy.class); // the existence check, never answered
            expectUnavailable(this, Duration.ofSeconds(10)); // after the transformer's 5 s ask
        }};
    }

    @Test
    public void terminatedPersistenceActorIsAnsweredUnavailable() {
        new TestKit(system) {{
            final TestProbe persistenceActor = new TestProbe(system);
            watch(persistenceActor.ref());
            system.stop(persistenceActor.ref());
            expectTerminated(persistenceActor.ref());
            final ActorRef supervisor = startSupervisor(persistenceActor.ref(), null);

            supervisor.tell(ModifyPolicy.of(POLICY_ID, POLICY, DITTO_HEADERS), getRef());

            expectUnavailable(this, Duration.ofSeconds(3));
        }};
    }

    @Test
    public void persistenceActorThatKeepsStoppingIsAnsweredUnavailable() {
        new TestKit(system) {{
            final ActorRef supervisor = startSupervisor(null, Props.create(StopsOnStart.class));
            expectNoMessage(Duration.ofMillis(500)); // the first child has stopped; the supervisor is in back-off

            supervisor.tell(ModifyPolicy.of(POLICY_ID, POLICY, DITTO_HEADERS), getRef());

            expectUnavailable(this, Duration.ofSeconds(10));
        }};
    }

    private ActorRef startSupervisor(@Nullable final ActorRef persistenceActor,
            @Nullable final Props persistenceActorProps) {

        final PolicyConfig policyConfig =
                DittoPoliciesConfig.of(DefaultScopedConfig.dittoScoped(system.settings().config()))
                        .getPolicyConfig();
        return system.actorOf(Props.create(TestPolicySupervisor.class,
                () -> new TestPolicySupervisor(policyConfig, persistenceActor, persistenceActorProps)));
    }

    private static void expectUnavailable(final TestKit testKit, final Duration max) {
        final PolicyUnavailableException unavailable = testKit.expectMsgClass(max, PolicyUnavailableException.class);
        assertThat(unavailable.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getDittoHeaders().getCorrelationId()).contains(CORRELATION_ID);
    }

    /** A persistence actor that stops at once, as one does whose recovery fails because the persistence is overloaded. */
    public static final class StopsOnStart extends AbstractActor {

        @Override
        public void preStart() {
            getContext().stop(getSelf());
        }

        @Override
        public Receive createReceive() {
            return receiveBuilder().build();
        }
    }

    /** Never answers; the transformation fails before any enforcement. */
    public static final class SilentEnforcer extends AbstractActor {

        @Override
        public Receive createReceive() {
            return receiveBuilder().matchAny(message -> {}).build();
        }
    }

    private static final class TestPolicySupervisor
            extends AbstractPersistenceSupervisor<PolicyId, PolicyCommand<?>> {

        @Nullable private final Props persistenceActorProps;

        private TestPolicySupervisor(final PolicyConfig policyConfig, @Nullable final ActorRef persistenceActor,
                @Nullable final Props persistenceActorProps) {
            super(persistenceActor, null, null, Mockito.mock(MongoReadJournal.class),
                    policyConfig.getSupervisorConfig());
            this.persistenceActorProps = persistenceActorProps;
        }

        @Override
        protected PolicyId getEntityId() {
            return POLICY_ID;
        }

        @Override
        protected Props getPersistenceActorProps(final PolicyId entityId) {
            if (null == persistenceActorProps) {
                throw new IllegalStateException("The persistence actor was passed in; it must not be started.");
            }
            return persistenceActorProps;
        }

        @Override
        protected Props getPersistenceEnforcerProps(final PolicyId entityId) {
            return Props.create(SilentEnforcer.class);
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

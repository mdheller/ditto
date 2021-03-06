/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.connectivity.messaging.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import javax.jms.JMSException;
import javax.jms.Session;

import org.apache.qpid.jms.JmsConnection;
import org.apache.qpid.jms.JmsSession;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionId;
import org.eclipse.ditto.services.connectivity.messaging.TestConstants;
import org.eclipse.ditto.services.connectivity.messaging.WithMockServers;
import org.eclipse.ditto.services.connectivity.messaging.internal.ClientConnected;
import org.eclipse.ditto.services.connectivity.messaging.internal.ClientDisconnected;
import org.eclipse.ditto.services.connectivity.messaging.internal.ConnectionFailure;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;

/**
 * Tests the {@link JMSConnectionHandlingActor}.
 */
@RunWith(MockitoJUnitRunner.class)
public class JmsConnectionHandlingActorTest extends WithMockServers {

    @SuppressWarnings("NullableProblems") private static ActorSystem actorSystem;

    private static final ConnectionId connectionId = TestConstants.createRandomConnectionId();
    private static Connection connection;

    @Mock private final Session mockSession = Mockito.mock(Session.class);
    @Mock private final JmsConnection mockConnection = Mockito.mock(JmsConnection.class);
    private final JmsConnectionFactory jmsConnectionFactory = (connection1, exceptionListener) -> mockConnection;

    @BeforeClass
    public static void setUp() {
        actorSystem = ActorSystem.create("AkkaTestSystem", TestConstants.CONFIG);
        connection = TestConstants.createConnection(connectionId);
    }

    @AfterClass
    public static void tearDown() {
        TestKit.shutdownActorSystem(actorSystem, scala.concurrent.duration.Duration.apply(5, TimeUnit.SECONDS),
                false);
    }

    @Before
    public void init() throws JMSException {
        when(mockConnection.createSession(Session.CLIENT_ACKNOWLEDGE)).thenReturn(mockSession);
    }

    @Test
    public void handleFailureOnJmsConnect() throws JMSException {
        new TestKit(actorSystem) {{

            final IllegalStateException exception = new IllegalStateException("failureOnJmsConnect");
            doThrow(exception).when(mockConnection).start();

            final Props props = JMSConnectionHandlingActor.props(connection, e -> {}, jmsConnectionFactory);
            final ActorRef connectionHandlingActor = watch(actorSystem.actorOf(props));

            final TestProbe origin = TestProbe.apply(actorSystem);

            connectionHandlingActor.tell(new AmqpClientActor.JmsConnect(origin.ref()), getRef());

            final ConnectionFailure connectionFailure1 = expectMsgClass(ConnectionFailure.class);
            assertThat(connectionFailure1.getOrigin()).contains(origin.ref());
            assertThat(connectionFailure1.getFailure().cause()).isSameAs(exception);
        }};
    }

    @Test
    public void handleFailureWhenCreatingConsumers() throws JMSException {
        new TestKit(actorSystem) {{

            final IllegalStateException exception = new IllegalStateException("failureOnCreateConsumer");
            doThrow(exception).when(mockSession).createConsumer(any());

            final Props props = JMSConnectionHandlingActor.props(connection, e -> {}, jmsConnectionFactory);
            final ActorRef connectionHandlingActor = watch(actorSystem.actorOf(props));

            final TestProbe origin = TestProbe.apply(actorSystem);

            connectionHandlingActor.tell(new AmqpClientActor.JmsConnect(origin.ref()), getRef());

            final ConnectionFailure connectionFailure1 = expectMsgClass(ConnectionFailure.class);
            assertThat(connectionFailure1.getOrigin()).contains(origin.ref());
            assertThat(connectionFailure1.getFailure().cause()).isSameAs(exception);
        }};
    }

    @Test
    public void handleJmsConnect() throws JMSException {
        new TestKit(actorSystem) {{

            final Props props = JMSConnectionHandlingActor.props(connection, e -> {}, jmsConnectionFactory);
            final ActorRef connectionHandlingActor = watch(actorSystem.actorOf(props));

            final TestProbe origin = TestProbe.apply(actorSystem);

            connectionHandlingActor.tell(new AmqpClientActor.JmsConnect(origin.ref()), getRef());

            final ClientConnected connected = expectMsgClass(ClientConnected.class);
            assertThat(connected.getOrigin()).contains(origin.ref());

            verify(mockConnection).start();
            verify(mockSession, times(connection.getSources()
                    .stream()
                    .mapToInt(s -> s.getAddresses().size() * s.getConsumerCount())
                    .sum())).createConsumer(any());
        }};
    }


    @Test
    public void handleRecoverSession() throws JMSException {
        new TestKit(actorSystem) {{

            final Props props = JMSConnectionHandlingActor.props(connection, e -> {}, jmsConnectionFactory);
            final ActorRef connectionHandlingActor = watch(actorSystem.actorOf(props));

            final TestProbe origin = TestProbe.apply(actorSystem);

            final JmsSession existingSession = Mockito.mock(JmsSession.class);
            connectionHandlingActor.tell(new AmqpClientActor.JmsRecoverSession(origin.ref(), mockConnection, existingSession),
                    getRef());

            final AmqpClientActor.JmsSessionRecovered recovered = expectMsgClass(AmqpClientActor.JmsSessionRecovered.class);
            assertThat(recovered.getOrigin()).contains(origin.ref());
            assertThat(recovered.getSession()).isSameAs(mockSession);

            verify(existingSession).close();
            verify(mockConnection).createSession(Session.CLIENT_ACKNOWLEDGE);
            verify(mockSession, times(connection.getSources()
                    .stream()
                    .mapToInt(s -> s.getAddresses().size() * s.getConsumerCount())
                    .sum())).createConsumer(any());
        }};
    }


    @Test
    public void handleRecoverSessionFails() throws JMSException {
        new TestKit(actorSystem) {{

            final JmsConnection failsToCreateSession = Mockito.mock(JmsConnection.class);
            when(failsToCreateSession.createSession(Session.CLIENT_ACKNOWLEDGE)).thenThrow(new JMSException("failed to create session"));

            final Props props = JMSConnectionHandlingActor.props(connection, e -> {}, jmsConnectionFactory);
            final ActorRef connectionHandlingActor = watch(actorSystem.actorOf(props));
            connectionHandlingActor.tell(new AmqpClientActor.JmsRecoverSession(getRef(), failsToCreateSession, mockSession),
                    getRef());

            expectMsgClass(ConnectionFailure.class);
            verify(mockSession).close();
            verify(failsToCreateSession).createSession(Session.CLIENT_ACKNOWLEDGE);
        }};
    }

    @Test
    public void handleJmsDisconnect() throws JMSException {
        new TestKit(actorSystem) {{

            final Props props = JMSConnectionHandlingActor.props(connection, e -> {}, jmsConnectionFactory);
            final ActorRef connectionHandlingActor = watch(actorSystem.actorOf(props));

            final TestProbe origin = TestProbe.apply(actorSystem);

            connectionHandlingActor.tell(new AmqpClientActor.JmsDisconnect(origin.ref(), mockConnection), getRef());

            final ClientDisconnected disconnected = expectMsgClass(ClientDisconnected.class);
            assertThat(disconnected.getOrigin()).contains(origin.ref());

            verify(mockConnection).close();
        }};
    }

    @Test
    public void handleFailureDuringJmsDisconnect() throws JMSException {
        new TestKit(actorSystem) {{

            final JMSException exception = new JMSException("failureOnJmsConnect");
            doThrow(exception).when(mockConnection).stop();

            final Props props = JMSConnectionHandlingActor.props(connection, e -> {}, jmsConnectionFactory);
            final ActorRef connectionHandlingActor = watch(actorSystem.actorOf(props));

            final TestProbe origin = TestProbe.apply(actorSystem);

            connectionHandlingActor.tell(new AmqpClientActor.JmsDisconnect(origin.ref(), mockConnection), getRef());

            final ClientDisconnected disconnected = expectMsgClass(ClientDisconnected.class);
            assertThat(disconnected.getOrigin()).contains(origin.ref());

            verify(mockConnection).close();
        }};
    }
}

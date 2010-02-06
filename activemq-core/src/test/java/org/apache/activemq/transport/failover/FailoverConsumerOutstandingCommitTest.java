/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.transport.failover;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerPlugin;
import org.apache.activemq.broker.BrokerPluginSupport;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.command.TransactionId;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.After;
import org.junit.Test;

public class FailoverConsumerOutstandingCommitTest {
	
    private static final Log LOG = LogFactory.getLog(FailoverConsumerOutstandingCommitTest.class);
	private static final String QUEUE_NAME = "FailoverWithOutstandingCommit";
    private static final String MESSAGE_TEXT = "Test message ";
	private String url = "tcp://localhost:61616";
	final int prefetch = 10;
	BrokerService broker;
	
	public void startCleanBroker() throws Exception {
	    startBroker(true);
	}
	
	@After
	public void stopBroker() throws Exception {
	    if (broker != null) {
	        broker.stop();
	    }
	}
	
	public void startBroker(boolean deleteAllMessagesOnStartup) throws Exception {
	    broker = createBroker(deleteAllMessagesOnStartup);
        broker.start();
	}

	public BrokerService createBroker(boolean deleteAllMessagesOnStartup) throws Exception {   
	    broker = new BrokerService();
	    broker.addConnector(url);
	    broker.setDeleteAllMessagesOnStartup(deleteAllMessagesOnStartup);
	    PolicyMap policyMap = new PolicyMap();
	    PolicyEntry defaultEntry = new PolicyEntry();
	    
	    // optimizedDispatche and sync dispatch ensure that the dispatch happens
	    // before the commit reply that the consumer.clearDispatchList is waiting for.
	    defaultEntry.setOptimizedDispatch(true);
        policyMap.setDefaultEntry(defaultEntry);
        broker.setDestinationPolicy(policyMap);
	    
	    return broker;
	}

	@Test
	public void testFailoverConsumerDups() throws Exception {
	    doTestFailoverConsumerDups(true);
	}
	
	public void doTestFailoverConsumerDups(final boolean watchTopicAdvisories) throws Exception {
	    
        broker = createBroker(true);
            
        broker.setPlugins(new BrokerPlugin[] {
                new BrokerPluginSupport() {
                    @Override
                    public void commitTransaction(ConnectionContext context,
                            TransactionId xid, boolean onePhase) throws Exception {
                        // so commit will hang as if reply is lost
                        context.setDontSendReponse(true);
                        Executors.newSingleThreadExecutor().execute(new Runnable() {   
                            public void run() {
                                LOG.info("Stopping broker before commit...");
                                try {
                                    broker.stop();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }
                }
        });
        broker.start();
        
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("failover:(" + url + ")");
        cf.setWatchTopicAdvisories(watchTopicAdvisories);
        cf.setDispatchAsync(false);
        
        final ActiveMQConnection connection = (ActiveMQConnection) cf.createConnection();
        connection.start();
        
        final Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        final Queue destination = producerSession.createQueue(QUEUE_NAME + "?consumer.prefetchSize=" + prefetch);
        
        final Session consumerSession = connection.createSession(true, Session.AUTO_ACKNOWLEDGE);


        final CountDownLatch commitDoneLatch = new CountDownLatch(1);
        final CountDownLatch messagesReceived = new CountDownLatch(2);

        final MessageConsumer testConsumer = consumerSession.createConsumer(destination);
        testConsumer.setMessageListener(new MessageListener() {

            public void onMessage(Message message) {
                LOG.info("consume one and commit");
               
                assertNotNull("got message", message);
               
                try {
                    consumerSession.commit();
                } catch (JMSException e) {
                    e.printStackTrace();
                }
                commitDoneLatch.countDown();
                messagesReceived.countDown();
                LOG.info("done commit");
            }
        });
        
        produceMessage(producerSession, destination, prefetch * 2);
     
        // will be stopped by the plugin
        broker.waitUntilStopped();
        broker = createBroker(false);
        broker.start();

        assertTrue("consumer added through failover", commitDoneLatch.await(20, TimeUnit.SECONDS));
        assertTrue("another message was recieved after failover", messagesReceived.await(20, TimeUnit.SECONDS));
        
        connection.close();
    }

    @Test
    public void TestFailoverConsumerOutstandingSendTxIncomplete() throws Exception {
        doTestFailoverConsumerOutstandingSendTx(false);
    }
	
    @Test
    public void TestFailoverConsumerOutstandingSendTxComplete() throws Exception {
        doTestFailoverConsumerOutstandingSendTx(true);
    }
    
    public void doTestFailoverConsumerOutstandingSendTx(final boolean doActualBrokerCommit) throws Exception {
        final boolean watchTopicAdvisories = true;
        broker = createBroker(true);

        broker.setPlugins(new BrokerPlugin[] { new BrokerPluginSupport() {
            @Override
            public void commitTransaction(ConnectionContext context,
                    TransactionId xid, boolean onePhase) throws Exception {
                if (doActualBrokerCommit) {
                    LOG.info("doing actual broker commit...");
                    super.commitTransaction(context, xid, onePhase);
                }
                // so commit will hang as if reply is lost
                context.setDontSendReponse(true);
                Executors.newSingleThreadExecutor().execute(new Runnable() {
                    public void run() {
                        LOG.info("Stopping broker before commit...");
                        try {
                            broker.stop();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        } });
        broker.start();

        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("failover:(" + url + ")");
        cf.setWatchTopicAdvisories(watchTopicAdvisories);
        cf.setDispatchAsync(false);

        final ActiveMQConnection connection = (ActiveMQConnection) cf.createConnection();
        connection.start();

        final Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        final Queue destination = producerSession.createQueue(QUEUE_NAME
                + "?consumer.prefetchSize=" + prefetch);

        final Session consumerSession = connection.createSession(true, Session.SESSION_TRANSACTED);

        final CountDownLatch commitDoneLatch = new CountDownLatch(1);
        final CountDownLatch messagesReceived = new CountDownLatch(3);
        final AtomicBoolean gotCommitException = new AtomicBoolean(false);
        final ArrayList<TextMessage> receivedMessages = new ArrayList<TextMessage>();
        final MessageConsumer testConsumer = consumerSession.createConsumer(destination);
        testConsumer.setMessageListener(new MessageListener() {

            public void onMessage(Message message) {
                LOG.info("consume one and commit: " + message);
                assertNotNull("got message", message);
                receivedMessages.add((TextMessage) message);
                try {
                    produceMessage(consumerSession, destination, 1);
                    consumerSession.commit();
                } catch (JMSException e) {
                    LOG.info("commit exception", e);
                    gotCommitException.set(true);
                }
                commitDoneLatch.countDown();
                messagesReceived.countDown();
                LOG.info("done commit");
            }
        });

        produceMessage(producerSession, destination, prefetch * 2);

        // will be stopped by the plugin
        broker.waitUntilStopped();
        broker = createBroker(false);
        broker.start();

        assertTrue("commit done through failover", commitDoneLatch.await(20, TimeUnit.SECONDS));
        assertTrue("commit failed", gotCommitException.get());
        assertTrue("another message was received after failover", messagesReceived.await(20, TimeUnit.SECONDS));
        assertEquals("get message 0 first", MESSAGE_TEXT + "0", receivedMessages.get(0).getText());
        // it was redelivered
        assertEquals("get message 0 second", MESSAGE_TEXT + "0", receivedMessages.get(1).getText());
        assertTrue("another message was received", messagesReceived.await(20, TimeUnit.SECONDS));
        assertEquals("get message 1 eventually", MESSAGE_TEXT + "1", receivedMessages.get(2).getText());
        
        connection.close();
    }

    @Test
    public void testRollbackFailoverConsumerTx() throws Exception {
        broker = createBroker(true);
        broker.start();

        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("failover:(" + url + ")");
        cf.setConsumerFailoverRedeliveryWaitPeriod(10000);
        final ActiveMQConnection connection = (ActiveMQConnection) cf.createConnection();
        connection.start();

        final Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        final Queue destination = producerSession.createQueue(QUEUE_NAME);

        final Session consumerSession = connection.createSession(true, Session.SESSION_TRANSACTED);
        final MessageConsumer testConsumer = consumerSession.createConsumer(destination);
        assertNull("no message yet", testConsumer.receiveNoWait());
        
        produceMessage(producerSession, destination, 1);
        producerSession.close();

        // consume then rollback after restart
        Message msg = testConsumer.receive(5000);
        assertNotNull(msg);
        
        // restart with outstanding delivered message
        broker.stop();
        broker.waitUntilStopped();
        broker = createBroker(false);
        broker.start();
        
        consumerSession.rollback();
        
        // receive again
        msg = testConsumer.receive(10000);
        assertNotNull("got message again after rollback", msg);

        consumerSession.commit();
        
        // close before sweep
        consumerSession.close();
        msg = receiveMessage(cf, destination);
        assertNull("should be nothing left after commit", msg);
        connection.close();
    }

    private Message receiveMessage(ActiveMQConnectionFactory cf,
            Queue destination) throws Exception {
        final ActiveMQConnection connection = (ActiveMQConnection) cf.createConnection();
        connection.start();
        final Session consumerSession = connection.createSession(true, Session.SESSION_TRANSACTED);
        final MessageConsumer consumer = consumerSession.createConsumer(destination);
        Message msg = consumer.receive(5000);
        consumerSession.commit();
        connection.close();
        return msg;
    }

    private void produceMessage(final Session producerSession, Queue destination, long count)
        throws JMSException {
        MessageProducer producer = producerSession.createProducer(destination);
        for (int i=0; i<count; i++) {
            TextMessage message = producerSession.createTextMessage(MESSAGE_TEXT + i);
            producer.send(message);
        }
        producer.close();
    }
}

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.objectweb.jtests.jms.framework;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.QueueConnectionFactory;
import javax.jms.Session;
import javax.jms.Topic;
import javax.jms.TopicConnectionFactory;
import javax.naming.Context;
import javax.naming.InitialContext;

import java.util.Hashtable;

import org.apache.activemq.artemis.jndi.ActiveMQInitialContextFactory;
import org.junit.After;
import org.junit.Before;

/**
 * Creates convenient Unified JMS 1.1 objects which can be needed for tests.
 * <br />
 * This class defines the setUp and tearDown methods so
 * that JMS administrated objects and  other "ready to use" JMS objects (that is to say destinations,
 * sessions, producers and consumers) are available conveniently for the test cases.
 * <br />
 * Classes which want that convenience should extend <code>UnifiedTestCase</code> instead of
 * <code>JMSTestCase</code>.
 *
 * @since JMS 1.1
 */
public abstract class UnifiedTestCase extends JMSTestCase
{

   protected Context ctx;

   private static final String CF_NAME = "testCF";

   private static final String TCF_NAME = "testTCF";

   private static final String QCF_NAME = "testQCF";

   private static final String DESTINATION_NAME = "testDestination";

   private static final String QUEUE_NAME = "testJoramQueue";

   private static final String TOPIC_NAME = "testJoramTopic";

   // //////////////////
   // Unified Domain //
   // //////////////////

   /**
    * Destination used by a producer
    */
   protected Destination producerDestination;

   /**
    * Producer
    */
   protected MessageProducer producer;

   /**
    * ConnectionFactory of the producer
    */
   protected ConnectionFactory producerCF;

   /**
    * Connection of the producer
    */
   protected Connection producerConnection;

   /**
    * Session of the producer (non transacted, AUTO_ACKNOWLEDGE)
    */
   protected Session producerSession;

   /**
    * Destination used by a consumer
    */
   protected Destination consumerDestination;

   /**
    * Consumer on destination
    */
   protected MessageConsumer consumer;

   /**
    * ConnectionFactory of the consumer
    */
   protected ConnectionFactory consumerCF;

   /**
    * Connection of the consumer
    */
   protected Connection consumerConnection;

   /**
    * Session of the consumer (non transacted, AUTO_ACKNOWLEDGE)
    */
   protected Session consumerSession;

   // //////////////
   // PTP Domain //
   // //////////////

   /**
    * QueueConnectionFactory
    */
   protected QueueConnectionFactory queueConnectionFactory;

   /**
    * Queue
    */
   protected Queue queue;

   // //////////////////
   // Pub/Sub Domain //
   // //////////////////

   /**
    * TopicConnectionFactory
    */
   protected TopicConnectionFactory topicConnectionFactory;

   /**
    * Topic
    */
   protected Topic topic;

   /**
    * Create all administrated objects connections and sessions ready to use for tests.
    * <br />
    * Start connections.
    */
   @Override
   @Before
   public void setUp() throws Exception
   {
      super.setUp();

      try
      {
         // ...and creates administrated objects and binds them
         admin.createConnectionFactory(UnifiedTestCase.CF_NAME);
         admin.createQueueConnectionFactory(UnifiedTestCase.QCF_NAME);
         admin.createTopicConnectionFactory(UnifiedTestCase.TCF_NAME);
         // destination for unified domain is a queue
         admin.createQueue(UnifiedTestCase.DESTINATION_NAME);
         admin.createQueue(UnifiedTestCase.QUEUE_NAME);
         admin.createTopic(UnifiedTestCase.TOPIC_NAME);

         Hashtable props = new Hashtable<>();
         props.put(Context.INITIAL_CONTEXT_FACTORY, ActiveMQInitialContextFactory.class.getCanonicalName());
         props.put("connectionFactory." + UnifiedTestCase.CF_NAME, "tcp://127.0.0.1:61616");
         props.put("connectionFactory." + UnifiedTestCase.QCF_NAME, "tcp://127.0.0.1:61616?type=QUEUE_CF");
         props.put("connectionFactory." + UnifiedTestCase.TCF_NAME, "tcp://127.0.0.1:61616?type=TOPIC_CF");
         props.put("queue." + UnifiedTestCase.DESTINATION_NAME, UnifiedTestCase.DESTINATION_NAME);
         props.put("queue." + UnifiedTestCase.QUEUE_NAME, UnifiedTestCase.QUEUE_NAME);
         props.put("topic." + UnifiedTestCase.TOPIC_NAME, UnifiedTestCase.TOPIC_NAME);
         Context ctx = new InitialContext(props);

         producerCF = (ConnectionFactory)ctx.lookup(UnifiedTestCase.CF_NAME);
         // we see destination of the unified domain as a javax.jms.Destination
         // instead of a javax.jms.Queue to be more generic
         producerDestination = (Destination)ctx.lookup(UnifiedTestCase.DESTINATION_NAME);
         producerConnection = producerCF.createConnection();
         producerSession = producerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
         producer = producerSession.createProducer(producerDestination);

         consumerCF = (ConnectionFactory)ctx.lookup(UnifiedTestCase.CF_NAME);
         // we see destination of the unified domain as a javax.jms.Destination
         // instead of a javax.jms.Queue to be more generic
         consumerDestination = (Destination)ctx.lookup(UnifiedTestCase.DESTINATION_NAME);
         consumerConnection = consumerCF.createConnection();
         consumerSession = consumerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
         consumer = consumerSession.createConsumer(consumerDestination);

         queueConnectionFactory = (QueueConnectionFactory)ctx.lookup(UnifiedTestCase.QCF_NAME);
         queue = (Queue)ctx.lookup(UnifiedTestCase.QUEUE_NAME);

         topicConnectionFactory = (TopicConnectionFactory)ctx.lookup(UnifiedTestCase.TCF_NAME);
         topic = (Topic)ctx.lookup(UnifiedTestCase.TOPIC_NAME);

         producerConnection.start();
         consumerConnection.start();
         // end of client step
      }
      catch (Exception e)
      {
         throw new RuntimeException(e);
      }
   }

   /**
    *  Close connections and delete administrated objects
    */
   @Override
   @After
   public void tearDown() throws Exception
   {
      try
      {
         consumerConnection.close();
         producerConnection.close();

         admin.deleteConnectionFactory(UnifiedTestCase.CF_NAME);
         admin.deleteQueueConnectionFactory(UnifiedTestCase.QCF_NAME);
         admin.deleteTopicConnectionFactory(UnifiedTestCase.TCF_NAME);
         admin.deleteQueue(UnifiedTestCase.DESTINATION_NAME);
         admin.deleteQueue(UnifiedTestCase.QUEUE_NAME);
         admin.deleteTopic(UnifiedTestCase.TOPIC_NAME);
      }
      catch (Exception ignored)
      {
      }
      finally
      {
         producerDestination = null;
         producer = null;
         producerCF = null;
         producerSession = null;
         producerConnection = null;

         consumerDestination = null;
         consumer = null;
         consumerCF = null;
         consumerSession = null;
         consumerConnection = null;

         queueConnectionFactory = null;
         queue = null;

         topicConnectionFactory = null;
         topic = null;
      }

      super.tearDown();
   }
}

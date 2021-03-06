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
package org.apache.activemq.artemis.core.client.impl;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.artemis.api.core.ActiveMQBuffer;
import org.apache.activemq.artemis.api.core.ActiveMQBuffers;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.FailoverEventListener;
import org.apache.activemq.artemis.api.core.client.SendAcknowledgementHandler;
import org.apache.activemq.artemis.api.core.client.SessionFailureListener;
import org.apache.activemq.artemis.core.client.ActiveMQClientLogger;
import org.apache.activemq.artemis.core.client.ActiveMQClientMessageBundle;
import org.apache.activemq.artemis.core.remoting.FailureListener;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.spi.core.remoting.ConsumerContext;
import org.apache.activemq.artemis.spi.core.remoting.SessionContext;
import org.apache.activemq.artemis.utils.ConfirmationWindowWarning;
import org.apache.activemq.artemis.utils.TokenBucketLimiterImpl;
import org.apache.activemq.artemis.utils.XidCodecSupport;

public final class ClientSessionImpl implements ClientSessionInternal, FailureListener
{
   private final Map<String, String> metadata = new HashMap<String, String>();

   private final ClientSessionFactoryInternal sessionFactory;

   private final String name;

   private final String username;

   private final String password;

   private final boolean xa;

   private final Executor executor;

   // to be sent to consumers as consumers will need a separate consumer for flow control
   private final Executor flowControlExecutor;

   /**
    * All access to producers are guarded (i.e. synchronized) on itself.
    */
   private final Set<ClientProducerInternal> producers = new HashSet<ClientProducerInternal>();

   // Consumers must be an ordered map so if we fail we recreate them in the same order with the same ids
   private final Map<ConsumerContext, ClientConsumerInternal> consumers = new LinkedHashMap<ConsumerContext, ClientConsumerInternal>();

   private volatile boolean closed;

   private final boolean autoCommitAcks;

   private final boolean preAcknowledge;

   private final boolean autoCommitSends;

   private final boolean blockOnAcknowledge;

   private final boolean autoGroup;

   private final int ackBatchSize;

   private final int consumerWindowSize;

   private final int consumerMaxRate;

   private final int confirmationWindowSize;

   private final int producerMaxRate;

   private final boolean blockOnNonDurableSend;

   private final boolean blockOnDurableSend;

   private final int minLargeMessageSize;

   private final boolean compressLargeMessages;

   private volatile int initialMessagePacketSize;

   private final boolean cacheLargeMessageClient;

   private final SessionContext sessionContext;

   // For testing only
   private boolean forceNotSameRM;

   private final ClientProducerCreditManager producerCreditManager;

   private volatile boolean started;

   private volatile boolean rollbackOnly;

   private volatile boolean workDone;

   private final String groupID;

   private volatile boolean inClose;

   private volatile boolean mayAttemptToFailover = true;

   private volatile SimpleString defaultAddress;

   /**
    * Current XID. this will be used in case of failover
    */
   private Xid currentXID;


   private final AtomicInteger concurrentCall = new AtomicInteger(0);

   private final ConfirmationWindowWarning confirmationWindowWarning;

   ClientSessionImpl(final ClientSessionFactoryInternal sessionFactory,
                     final String name,
                     final String username,
                     final String password,
                     final boolean xa,
                     final boolean autoCommitSends,
                     final boolean autoCommitAcks,
                     final boolean preAcknowledge,
                     final boolean blockOnAcknowledge,
                     final boolean autoGroup,
                     final int ackBatchSize,
                     final int consumerWindowSize,
                     final int consumerMaxRate,
                     final int confirmationWindowSize,
                     final int producerWindowSize,
                     final int producerMaxRate,
                     final boolean blockOnNonDurableSend,
                     final boolean blockOnDurableSend,
                     final boolean cacheLargeMessageClient,
                     final int minLargeMessageSize,
                     final boolean compressLargeMessages,
                     final int initialMessagePacketSize,
                     final String groupID,
                     final SessionContext sessionContext,
                     final Executor executor,
                     final Executor flowControlExecutor) throws ActiveMQException
   {
      this.sessionFactory = sessionFactory;

      this.name = name;

      this.username = username;

      this.password = password;

      this.executor = executor;

      this.flowControlExecutor = flowControlExecutor;

      this.xa = xa;

      this.autoCommitAcks = autoCommitAcks;

      this.preAcknowledge = preAcknowledge;

      this.autoCommitSends = autoCommitSends;

      this.blockOnAcknowledge = blockOnAcknowledge;

      this.autoGroup = autoGroup;

      this.ackBatchSize = ackBatchSize;

      this.consumerWindowSize = consumerWindowSize;

      this.consumerMaxRate = consumerMaxRate;

      this.confirmationWindowSize = confirmationWindowSize;

      this.producerMaxRate = producerMaxRate;

      this.blockOnNonDurableSend = blockOnNonDurableSend;

      this.blockOnDurableSend = blockOnDurableSend;

      this.cacheLargeMessageClient = cacheLargeMessageClient;

      this.minLargeMessageSize = minLargeMessageSize;

      this.compressLargeMessages = compressLargeMessages;

      this.initialMessagePacketSize = initialMessagePacketSize;

      this.groupID = groupID;

      producerCreditManager = new ClientProducerCreditManagerImpl(this, producerWindowSize);

      this.sessionContext = sessionContext;

      sessionContext.setSession(this);

      confirmationWindowWarning = sessionFactory.getConfirmationWindowWarning();
   }

   // ClientSession implementation
   // -----------------------------------------------------------------

   public void createQueue(final SimpleString address, final SimpleString queueName) throws ActiveMQException
   {
      internalCreateQueue(address, queueName, null, false, false);
   }

   public void createQueue(final SimpleString address, final SimpleString queueName, final boolean durable) throws ActiveMQException
   {
      internalCreateQueue(address, queueName, null, durable, false);
   }

   public void createQueue(final String address, final String queueName, final boolean durable) throws ActiveMQException
   {
      createQueue(SimpleString.toSimpleString(address), SimpleString.toSimpleString(queueName), durable);
   }

   public void createSharedQueue(SimpleString address,
                                 SimpleString queueName,
                                 boolean durable) throws ActiveMQException
   {
      createSharedQueue(address, queueName, null, durable);
   }

   public void createSharedQueue(SimpleString address,
                                 SimpleString queueName,
                                 SimpleString filterString,
                                 boolean durable) throws ActiveMQException
   {

      checkClosed();


      startCall();
      try
      {
         sessionContext.createSharedQueue(address, queueName, filterString, durable);
      }
      finally
      {
         endCall();
      }

   }


   public void createQueue(final SimpleString address,
                           final SimpleString queueName,
                           final SimpleString filterString,
                           final boolean durable) throws ActiveMQException
   {
      internalCreateQueue(address, queueName, filterString, durable, false);
   }

   public void createQueue(final String address,
                           final String queueName,
                           final String filterString,
                           final boolean durable) throws ActiveMQException
   {
      createQueue(SimpleString.toSimpleString(address), SimpleString.toSimpleString(queueName), SimpleString.toSimpleString(filterString), durable);
   }

   public void createTemporaryQueue(final SimpleString address, final SimpleString queueName) throws ActiveMQException
   {
      internalCreateQueue(address, queueName, null, false, true);
   }

   public void createTemporaryQueue(final String address, final String queueName) throws ActiveMQException
   {
      internalCreateQueue(SimpleString.toSimpleString(address),
                          SimpleString.toSimpleString(queueName),
                          null,
                          false,
                          true);
   }

   public void createTemporaryQueue(final SimpleString address, final SimpleString queueName, final SimpleString filter) throws ActiveMQException
   {
      internalCreateQueue(address, queueName, filter, false, true);
   }

   public void createTemporaryQueue(final String address, final String queueName, final String filter) throws ActiveMQException
   {
      internalCreateQueue(SimpleString.toSimpleString(address),
                          SimpleString.toSimpleString(queueName),
                          SimpleString.toSimpleString(filter),
                          false,
                          true);
   }

   public void deleteQueue(final SimpleString queueName) throws ActiveMQException
   {
      checkClosed();

      startCall();
      try
      {
         sessionContext.deleteQueue(queueName);
      }
      finally
      {
         endCall();
      }
   }

   public void deleteQueue(final String queueName) throws ActiveMQException
   {
      deleteQueue(SimpleString.toSimpleString(queueName));
   }

   public QueueQuery queueQuery(final SimpleString queueName) throws ActiveMQException
   {
      checkClosed();


      startCall();
      try
      {
         return sessionContext.queueQuery(queueName);
      }
      finally
      {
         endCall();
      }

   }

   public AddressQuery addressQuery(final SimpleString address) throws ActiveMQException
   {
      checkClosed();


      return sessionContext.addressQuery(address);
   }

   public ClientConsumer createConsumer(final SimpleString queueName) throws ActiveMQException
   {
      return createConsumer(queueName, null, false);
   }

   public ClientConsumer createConsumer(final String queueName) throws ActiveMQException
   {
      return createConsumer(SimpleString.toSimpleString(queueName));
   }

   public ClientConsumer createConsumer(final SimpleString queueName, final SimpleString filterString) throws ActiveMQException
   {
      return createConsumer(queueName, filterString, consumerWindowSize, consumerMaxRate, false);
   }

   public void createQueue(final String address, final String queueName) throws ActiveMQException
   {
      createQueue(SimpleString.toSimpleString(address), SimpleString.toSimpleString(queueName));
   }

   public ClientConsumer createConsumer(final String queueName, final String filterString) throws ActiveMQException
   {
      return createConsumer(SimpleString.toSimpleString(queueName), SimpleString.toSimpleString(filterString));
   }

   public ClientConsumer createConsumer(final SimpleString queueName,
                                        final SimpleString filterString,
                                        final boolean browseOnly) throws ActiveMQException
   {
      return createConsumer(queueName, filterString, consumerWindowSize, consumerMaxRate, browseOnly);
   }

   public ClientConsumer createConsumer(final SimpleString queueName, final boolean browseOnly) throws ActiveMQException
   {
      return createConsumer(queueName, null, consumerWindowSize, consumerMaxRate, browseOnly);
   }

   public ClientConsumer createConsumer(final String queueName, final String filterString, final boolean browseOnly) throws ActiveMQException
   {
      return createConsumer(SimpleString.toSimpleString(queueName),
                            SimpleString.toSimpleString(filterString),
                            browseOnly);
   }

   public ClientConsumer createConsumer(final String queueName, final boolean browseOnly) throws ActiveMQException
   {
      return createConsumer(SimpleString.toSimpleString(queueName), null, browseOnly);
   }

   /**
    * Note, we DO NOT currently support direct consumers (i.e. consumers where delivery occurs on
    * the remoting thread).
    * <p>
    * Direct consumers have issues with blocking and failover. E.g. if direct then inside
    * MessageHandler call a blocking method like rollback or acknowledge (blocking) This can block
    * until failover completes, which disallows the thread to be used to deliver any responses to
    * the client during that period, so failover won't occur. If we want direct consumers we need to
    * rethink how they work.
    */
   public ClientConsumer createConsumer(final SimpleString queueName,
                                        final SimpleString filterString,
                                        final int windowSize,
                                        final int maxRate,
                                        final boolean browseOnly) throws ActiveMQException
   {
      return internalCreateConsumer(queueName, filterString, windowSize, maxRate, browseOnly);
   }

   public ClientConsumer createConsumer(final String queueName,
                                        final String filterString,
                                        final int windowSize,
                                        final int maxRate,
                                        final boolean browseOnly) throws ActiveMQException
   {
      return createConsumer(SimpleString.toSimpleString(queueName), SimpleString.toSimpleString(filterString), windowSize, maxRate, browseOnly);
   }

   public ClientProducer createProducer() throws ActiveMQException
   {
      return createProducer((SimpleString) null);
   }

   public ClientProducer createProducer(final SimpleString address) throws ActiveMQException
   {
      return createProducer(address, producerMaxRate);
   }

   public ClientProducer createProducer(final String address) throws ActiveMQException
   {
      return createProducer(SimpleString.toSimpleString(address));
   }

   public ClientProducer createProducer(final SimpleString address, final int maxRate) throws ActiveMQException
   {
      return internalCreateProducer(address, maxRate);
   }

   public ClientProducer createProducer(final String address, final int rate) throws ActiveMQException
   {
      return createProducer(SimpleString.toSimpleString(address), rate);
   }

   public XAResource getXAResource()
   {
      return this;
   }

   private void rollbackOnFailover(boolean outcomeKnown) throws ActiveMQException
   {
      rollback(false);

      if (outcomeKnown)
      {
         throw ActiveMQClientMessageBundle.BUNDLE.txRolledBack();
      }

      throw ActiveMQClientMessageBundle.BUNDLE.txOutcomeUnknown();
   }

   public void commit() throws ActiveMQException
   {
      checkClosed();

      if (ActiveMQClientLogger.LOGGER.isTraceEnabled())
      {
         ActiveMQClientLogger.LOGGER.trace("Sending commit");
      }

      /*
      * we have failed over since any work was done so we should rollback
      * */
      if (rollbackOnly)
      {
         rollbackOnFailover(true);
      }

      flushAcks();
      /*
      * if we have failed over whilst flushing the acks then we should rollback and throw exception before attempting to
      * commit as committing might actually commit something but we we wouldn't know and rollback after the commit
      * */
      if (rollbackOnly)
      {
         rollbackOnFailover(true);
      }
      try
      {
         sessionContext.simpleCommit();
      }
      catch (ActiveMQException e)
      {
         if (e.getType() == ActiveMQExceptionType.UNBLOCKED || rollbackOnly)
         {
            // The call to commit was unlocked on failover, we therefore rollback the tx,
            // and throw a transaction rolled back exception instead
            //or
            //if we have been set to rollbackonly then we have probably failed over and don't know if the tx has committed
            rollbackOnFailover(false);
         }
         else
         {
            throw e;
         }
      }

      //oops, we have failed over during the commit and don't know what happened
      if (rollbackOnly)
      {
         rollbackOnFailover(false);
      }

      workDone = false;
   }

   public boolean isRollbackOnly()
   {
      return rollbackOnly;
   }

   public void rollback() throws ActiveMQException
   {
      rollback(false);
   }

   public void rollback(final boolean isLastMessageAsDelivered) throws ActiveMQException
   {
      if (ActiveMQClientLogger.LOGGER.isTraceEnabled())
      {
         ActiveMQClientLogger.LOGGER.trace("calling rollback(isLastMessageAsDelivered=" + isLastMessageAsDelivered + ")");
      }
      checkClosed();

      // We do a "JMS style" rollback where the session is stopped, and the buffer is cancelled back
      // first before rolling back
      // This ensures messages are received in the same order after rollback w.r.t. to messages in the buffer
      // For core we could just do a straight rollback, it really depends if we want JMS style semantics or not...

      boolean wasStarted = started;

      if (wasStarted)
      {
         stop();
      }


      // We need to make sure we don't get any inflight messages
      for (ClientConsumerInternal consumer : cloneConsumers())
      {
         consumer.clear(true);
      }

      // Acks must be flushed here *after connection is stopped and all onmessages finished executing
      flushAcks();

      sessionContext.simpleRollback(isLastMessageAsDelivered);

      if (wasStarted)
      {
         start();
      }

      rollbackOnly = false;
   }

   public ClientMessage createMessage(final byte type,
                                      final boolean durable,
                                      final long expiration,
                                      final long timestamp,
                                      final byte priority)
   {
      return new ClientMessageImpl(type, durable, expiration, timestamp, priority, initialMessagePacketSize);
   }

   public ClientMessage createMessage(final byte type, final boolean durable)
   {
      return this.createMessage(type, durable, 0, System.currentTimeMillis(), (byte) 4);
   }

   public ClientMessage createMessage(final boolean durable)
   {
      return this.createMessage((byte) 0, durable);
   }

   public boolean isClosed()
   {
      return closed;
   }

   public boolean isAutoCommitSends()
   {
      return autoCommitSends;
   }

   public boolean isAutoCommitAcks()
   {
      return autoCommitAcks;
   }

   public boolean isBlockOnAcknowledge()
   {
      return blockOnAcknowledge;
   }

   public boolean isXA()
   {
      return xa;
   }

   public void resetIfNeeded() throws ActiveMQException
   {
      if (rollbackOnly)
      {
         ActiveMQClientLogger.LOGGER.resettingSessionAfterFailure();
         rollback(false);
      }
   }

   public ClientSessionImpl start() throws ActiveMQException
   {
      checkClosed();

      if (!started)
      {
         for (ClientConsumerInternal clientConsumerInternal : cloneConsumers())
         {
            clientConsumerInternal.start();
         }

         sessionContext.sessionStart();

         started = true;
      }

      return this;
   }

   public void stop() throws ActiveMQException
   {
      stop(true);
   }

   public void stop(final boolean waitForOnMessage) throws ActiveMQException
   {
      checkClosed();

      if (started)
      {
         for (ClientConsumerInternal clientConsumerInternal : cloneConsumers())
         {
            clientConsumerInternal.stop(waitForOnMessage);
         }

         sessionContext.sessionStop();

         started = false;
      }
   }

   public void addFailureListener(final SessionFailureListener listener)
   {
      sessionFactory.addFailureListener(listener);
   }

   public boolean removeFailureListener(final SessionFailureListener listener)
   {
      return sessionFactory.removeFailureListener(listener);
   }

   public void addFailoverListener(FailoverEventListener listener)
   {
      sessionFactory.addFailoverListener(listener);
   }

   public boolean removeFailoverListener(FailoverEventListener listener)
   {
      return sessionFactory.removeFailoverListener(listener);
   }

   public int getVersion()
   {
      return sessionContext.getServerVersion();
   }

   public boolean isClosing()
   {
      return inClose;
   }

   @Override
   public String getNodeId()
   {
      return sessionFactory.getLiveNodeId();
   }

   // ClientSessionInternal implementation
   // ------------------------------------------------------------

   public int getMinLargeMessageSize()
   {
      return minLargeMessageSize;
   }

   public boolean isCompressLargeMessages()
   {
      return compressLargeMessages;
   }

   /**
    * @return the cacheLargeMessageClient
    */
   public boolean isCacheLargeMessageClient()
   {
      return cacheLargeMessageClient;
   }

   public String getName()
   {
      return name;
   }

   /**
    * Acknowledges all messages received by the consumer so far.
    */
   public void acknowledge(final ClientConsumer consumer, final Message message) throws ActiveMQException
   {
      // if we're pre-acknowledging then we don't need to do anything
      if (preAcknowledge)
      {
         return;
      }

      checkClosed();
      if (ActiveMQClientLogger.LOGGER.isDebugEnabled())
      {
         ActiveMQClientLogger.LOGGER.debug("client ack messageID = " + message.getMessageID());
      }

      startCall();
      try
      {
         sessionContext.sendACK(false, blockOnAcknowledge, consumer, message);
      }
      finally
      {
         endCall();
      }
   }

   public void individualAcknowledge(final ClientConsumer consumer, final Message message) throws ActiveMQException
   {
      // if we're pre-acknowledging then we don't need to do anything
      if (preAcknowledge)
      {
         return;
      }

      checkClosed();

      startCall();
      try
      {

         sessionContext.sendACK(true, blockOnAcknowledge, consumer, message);
      }
      finally
      {
         endCall();
      }
   }

   public void expire(final ClientConsumer consumer, final Message message) throws ActiveMQException
   {
      checkClosed();

      // We don't send expiries for pre-ack since message will already have been acked on server
      if (!preAcknowledge)
      {
         sessionContext.expireMessage(consumer, message);
      }
   }

   public void addConsumer(final ClientConsumerInternal consumer)
   {
      synchronized (consumers)
      {
         consumers.put(consumer.getConsumerContext(), consumer);
      }
   }

   public void addProducer(final ClientProducerInternal producer)
   {
      synchronized (producers)
      {
         producers.add(producer);
      }
   }

   public void removeConsumer(final ClientConsumerInternal consumer) throws ActiveMQException
   {
      synchronized (consumers)
      {
         consumers.remove(consumer.getConsumerContext());
      }
   }

   public void removeProducer(final ClientProducerInternal producer)
   {
      synchronized (producers)
      {
         producers.remove(producer);
      }
   }

   public void handleReceiveMessage(final ConsumerContext consumerID, final ClientMessageInternal message) throws Exception
   {
      ClientConsumerInternal consumer = getConsumer(consumerID);

      if (consumer != null)
      {
         consumer.handleMessage(message);
      }
   }

   public void handleReceiveLargeMessage(final ConsumerContext consumerID, ClientLargeMessageInternal clientLargeMessage, long largeMessageSize) throws Exception
   {
      ClientConsumerInternal consumer = getConsumer(consumerID);

      if (consumer != null)
      {
         consumer.handleLargeMessage(clientLargeMessage, largeMessageSize);
      }
   }

   public void handleReceiveContinuation(final ConsumerContext consumerID, byte[] chunk, int flowControlSize, boolean isContinues) throws Exception
   {
      ClientConsumerInternal consumer = getConsumer(consumerID);

      if (consumer != null)
      {
         consumer.handleLargeMessageContinuation(chunk, flowControlSize, isContinues);
      }
   }

   @Override
   public void handleConsumerDisconnect(ConsumerContext context) throws ActiveMQException
   {
      final ClientConsumerInternal consumer = getConsumer(context);

      if (consumer != null)
      {
         executor.execute(new Runnable()
         {
            @Override
            public void run()
            {
               try
               {
                  consumer.close();
               }
               catch (ActiveMQException e)
               {
                  ActiveMQClientLogger.LOGGER.unableToCloseConsumer(e);
               }
            }
         });
      }
   }

   public void close() throws ActiveMQException
   {
      if (closed)
      {
         ActiveMQClientLogger.LOGGER.debug("Session was already closed, giving up now, this=" + this);
         return;
      }

      if (ActiveMQClientLogger.LOGGER.isDebugEnabled())
      {
         ActiveMQClientLogger.LOGGER.debug("Calling close on session " + this);
      }

      try
      {
         closeChildren();

         synchronized (this)
         {
            producerCreditManager.close();
         }
         inClose = true;
         sessionContext.sessionClose();
      }
      catch (Throwable e)
      {
         // Session close should always return without exception

         // Note - we only log at trace
         ActiveMQClientLogger.LOGGER.trace("Failed to close session", e);
      }

      doCleanup(false);
   }

   public synchronized void cleanUp(boolean failingOver) throws ActiveMQException
   {
      if (closed)
      {
         return;
      }

      producerCreditManager.close();

      cleanUpChildren();

      doCleanup(failingOver);
   }

   public ClientSessionImpl setSendAcknowledgementHandler(final SendAcknowledgementHandler handler)
   {
      sessionContext.setSendAcknowledgementHandler(handler);
      return this;
   }

   public void preHandleFailover(RemotingConnection connection)
   {
      // We lock the channel to prevent any packets to be added to the re-send
      // cache during the failover process
      //we also do this before the connection fails over to give the session a chance to block for failover
      sessionContext.lockCommunications();
   }

   // Needs to be synchronized to prevent issues with occurring concurrently with close()

   public void handleFailover(final RemotingConnection backupConnection, ActiveMQException cause)
   {
      synchronized (this)
      {
         if (closed)
         {
            return;
         }

         boolean resetCreditManager = false;

         try
         {

            // TODO remove this and encapsulate it

            boolean reattached = sessionContext.reattachOnNewConnection(backupConnection);

            if (!reattached)
            {

               if (ActiveMQClientLogger.LOGGER.isDebugEnabled())
               {
                  ActiveMQClientLogger.LOGGER.debug("ClientSession couldn't be reattached, creating a new session");
               }

               for (ClientConsumerInternal consumer : cloneConsumers())
               {
                  consumer.clearAtFailover();
               }


               // The session wasn't found on the server - probably we're failing over onto a backup server where the
               // session won't exist or the target server has been restarted - in this case the session will need to be
               // recreated,
               // and we'll need to recreate any consumers

               // It could also be that the server hasn't been restarted, but the session is currently executing close,
               // and
               // that
               // has already been executed on the server, that's why we can't find the session- in this case we *don't*
               // want
               // to recreate the session, we just want to unblock the blocking call
               if (!inClose && mayAttemptToFailover)
               {
                  sessionContext.recreateSession(username, password,
                                                 minLargeMessageSize, xa, autoCommitSends,
                                                 autoCommitAcks, preAcknowledge, defaultAddress);

                  for (Map.Entry<ConsumerContext, ClientConsumerInternal> entryx : consumers.entrySet())
                  {

                     ClientConsumerInternal consumerInternal = entryx.getValue();

                     sessionContext.recreateConsumerOnServer(consumerInternal);
                  }

                  if ((!autoCommitAcks || !autoCommitSends) && workDone)
                  {
                     // this is protected by a lock, so we can guarantee nothing will sneak here
                     // while we do our work here
                     rollbackOnly = true;
                  }
                  if (currentXID != null)
                  {
                     sessionContext.xaFailed(currentXID);
                     rollbackOnly = true;
                  }

                  // Now start the session if it was already started
                  if (started)
                  {
                     for (ClientConsumerInternal consumer : cloneConsumers())
                     {
                        consumer.clearAtFailover();
                        consumer.start();
                     }

                     sessionContext.restartSession();
                  }

                  resetCreditManager = true;
               }

               sessionContext.returnBlocking(cause);
            }
         }
         catch (Throwable t)
         {
            ActiveMQClientLogger.LOGGER.failedToHandleFailover(t);
         }
         finally
         {
            sessionContext.releaseCommunications();
         }

         if (resetCreditManager)
         {
            producerCreditManager.reset();

            // Also need to send more credits for consumers, otherwise the system could hand with the server
            // not having any credits to send
         }
      }

      HashMap<String, String> metaDataToSend;

      synchronized (metadata)
      {
         metaDataToSend = new HashMap<String, String>(metadata);
      }

      sessionContext.resetMetadata(metaDataToSend);


   }

   public void addMetaData(String key, String data) throws ActiveMQException
   {
      synchronized (metadata)
      {
         metadata.put(key, data);
      }

      sessionContext.addSessionMetadata(key, data);
   }

   public void addUniqueMetaData(String key, String data) throws ActiveMQException
   {
      sessionContext.addUniqueMetaData(key, data);
   }

   public ClientSessionFactory getSessionFactory()
   {
      return sessionFactory;
   }

   public void setAddress(final Message message, final SimpleString address)
   {
      if (defaultAddress == null)
      {
         defaultAddress = address;

         message.setAddress(address);
      }
      else
      {
         if (!address.equals(defaultAddress))
         {
            message.setAddress(address);
         }
         else
         {
            message.setAddress(null);
         }
      }
   }

   public void setPacketSize(final int packetSize)
   {
      if (packetSize > this.initialMessagePacketSize)
      {
         this.initialMessagePacketSize = (int) (packetSize * 1.2);
      }
   }

   public void workDone()
   {
      workDone = true;
   }

   public void sendProducerCreditsMessage(final int credits, final SimpleString address)
   {
      sessionContext.sendProducerCreditsMessage(credits, address);
   }

   public synchronized ClientProducerCredits getCredits(final SimpleString address, final boolean anon)
   {
      ClientProducerCredits credits = producerCreditManager.getCredits(address, anon, sessionContext);

      return credits;
   }

   public void returnCredits(final SimpleString address)
   {
      producerCreditManager.returnCredits(address);
   }

   public void handleReceiveProducerCredits(final SimpleString address, final int credits)
   {
      producerCreditManager.receiveCredits(address, credits);
   }

   public void handleReceiveProducerFailCredits(final SimpleString address, int credits)
   {
      producerCreditManager.receiveFailCredits(address, credits);
   }

   public ClientProducerCreditManager getProducerCreditManager()
   {
      return producerCreditManager;
   }

   public void startCall()
   {
      if (concurrentCall.incrementAndGet() > 1)
      {
         ActiveMQClientLogger.LOGGER.invalidConcurrentSessionUsage(new Exception("trace"));
      }
   }

   public void endCall()
   {
      concurrentCall.decrementAndGet();
   }

   // CommandConfirmationHandler implementation ------------------------------------

   // TODO: this will be encapsulated by the SessionContext

   // XAResource implementation
   // --------------------------------------------------------------------

   public void commit(final Xid xid, final boolean onePhase) throws XAException
   {
      if (ActiveMQClientLogger.LOGGER.isTraceEnabled())
      {
         ActiveMQClientLogger.LOGGER.trace("call commit(xid=" + convert(xid));
      }
      checkXA();

      // we should never throw rollback if we have already prepared
      if (rollbackOnly)
      {
         ActiveMQClientLogger.LOGGER.commitAfterFailover();
      }

      // Note - don't need to flush acks since the previous end would have
      // done this

      startCall();
      try
      {
         sessionContext.xaCommit(xid, onePhase);
         workDone = false;
      }
      catch (XAException xae)
      {
         throw xae;
      }
      catch (Throwable t)
      {
         ActiveMQClientLogger.LOGGER.failoverDuringCommit();

         // Any error on commit -> RETRY
         // We can't rollback a Prepared TX for definition
         XAException xaException = new XAException(XAException.XA_RETRY);
         xaException.initCause(t);
         throw xaException;
      }
      finally
      {
         endCall();
      }
   }

   public void end(final Xid xid, final int flags) throws XAException
   {
      if (ActiveMQClientLogger.LOGGER.isTraceEnabled())
      {
         ActiveMQClientLogger.LOGGER.trace("Calling end:: " + convert(xid) + ", flags=" + convertTXFlag(flags));
      }

      checkXA();

      try
      {
         if (rollbackOnly)
         {
            try
            {
               rollback();
            }
            catch (Throwable ignored)
            {
               ActiveMQClientLogger.LOGGER.debug("Error on rollback during end call!", ignored);
            }
            throw new XAException(XAException.XA_RBOTHER);
         }

         try
         {
            flushAcks();

            startCall();
            try
            {
               sessionContext.xaEnd(xid, flags);
            }
            finally
            {
               endCall();
            }
         }
         catch (XAException xae)
         {
            throw xae;
         }
         catch (Throwable t)
         {
            ActiveMQClientLogger.LOGGER.errorCallingEnd(t);
            // This could occur if the TM interrupts the thread
            XAException xaException = new XAException(XAException.XAER_RMERR);
            xaException.initCause(t);
            throw xaException;
         }
      }
      finally
      {
         currentXID = null;
      }
   }

   public void forget(final Xid xid) throws XAException
   {
      checkXA();
      startCall();
      try
      {
         sessionContext.xaForget(xid);
      }
      catch (XAException xae)
      {
         throw xae;
      }
      catch (Throwable t)
      {
         // This could occur if the TM interrupts the thread
         XAException xaException = new XAException(XAException.XAER_RMERR);
         xaException.initCause(t);
         throw xaException;
      }
      finally
      {
         endCall();
      }
   }

   public int getTransactionTimeout() throws XAException
   {
      checkXA();

      try
      {
         return sessionContext.recoverSessionTimeout();
      }
      catch (Throwable t)
      {
         // This could occur if the TM interrupts the thread
         XAException xaException = new XAException(XAException.XAER_RMERR);
         xaException.initCause(t);
         throw xaException;
      }
   }

   public boolean setTransactionTimeout(final int seconds) throws XAException
   {
      checkXA();

      try
      {
         return sessionContext.configureTransactionTimeout(seconds);
      }
      catch (Throwable t)
      {
         // This could occur if the TM interrupts the thread
         XAException xaException = new XAException(XAException.XAER_RMERR);
         xaException.initCause(t);
         throw xaException;
      }
   }

   public boolean isSameRM(final XAResource xares) throws XAException
   {
      checkXA();

      if (forceNotSameRM)
      {
         return false;
      }

      ClientSessionInternal other = getSessionInternalFromXAResource(xares);

      if (other == null)
      {
         return false;
      }

      String liveNodeId = sessionFactory.getLiveNodeId();
      String otherLiveNodeId = ((ClientSessionFactoryInternal) other.getSessionFactory()).getLiveNodeId();

      if (liveNodeId != null && otherLiveNodeId != null)
      {
         return liveNodeId.equals(otherLiveNodeId);
      }

      //we shouldn't get here, live node id should always be set
      return sessionFactory == other.getSessionFactory();
   }

   private ClientSessionInternal getSessionInternalFromXAResource(final XAResource xares)
   {
      if (xares == null)
      {
         return null;
      }
      if (xares instanceof ClientSessionInternal)
      {
         return (ClientSessionInternal) xares;
      }
      else if (xares instanceof ActiveMQXAResource)
      {
         return getSessionInternalFromXAResource(((ActiveMQXAResource)xares).getResource());
      }
      return null;
   }

   public int prepare(final Xid xid) throws XAException
   {
      checkXA();
      if (ActiveMQClientLogger.LOGGER.isTraceEnabled())
      {
         ActiveMQClientLogger.LOGGER.trace("Calling prepare:: " + convert(xid));
      }


      if (rollbackOnly)
      {
         throw new XAException(XAException.XA_RBOTHER);
      }

      // Note - don't need to flush acks since the previous end would have
      // done this

      startCall();
      try
      {
         return sessionContext.xaPrepare(xid);
      }
      catch (XAException xae)
      {
         throw xae;
      }
      catch (ActiveMQException e)
      {
         if (e.getType() == ActiveMQExceptionType.UNBLOCKED)
         {
            // Unblocked on failover
            try
            {
               // will retry once after failover & unblock
               return sessionContext.xaPrepare(xid);
            }
            catch (Throwable t)
            {
               // ignore and rollback
            }
            ActiveMQClientLogger.LOGGER.failoverDuringPrepareRollingBack();
            try
            {
               rollback(false);
            }
            catch (Throwable t)
            {
               // This could occur if the TM interrupts the thread
               XAException xaException = new XAException(XAException.XAER_RMERR);
               xaException.initCause(t);
               throw xaException;
            }

            ActiveMQClientLogger.LOGGER.errorDuringPrepare(e);

            throw new XAException(XAException.XA_RBOTHER);
         }

         ActiveMQClientLogger.LOGGER.errorDuringPrepare(e);

         // This should never occur
         XAException xaException = new XAException(XAException.XAER_RMERR);
         xaException.initCause(e);
         throw xaException;
      }
      catch (Throwable t)
      {
         ActiveMQClientLogger.LOGGER.errorDuringPrepare(t);

         // This could occur if the TM interrupts the thread
         XAException xaException = new XAException(XAException.XAER_RMERR);
         xaException.initCause(t);
         throw xaException;
      }
      finally
      {
         endCall();
      }
   }

   public Xid[] recover(final int flags) throws XAException
   {
      checkXA();

      if ((flags & XAResource.TMSTARTRSCAN) == XAResource.TMSTARTRSCAN)
      {
         try
         {
            return sessionContext.xaScan();
         }
         catch (Throwable t)
         {
            // This could occur if the TM interrupts the thread
            XAException xaException = new XAException(XAException.XAER_RMERR);
            xaException.initCause(t);
            throw xaException;
         }
      }

      return new Xid[0];
   }

   public void rollback(final Xid xid) throws XAException
   {
      checkXA();

      if (ActiveMQClientLogger.LOGGER.isTraceEnabled())
      {
         ActiveMQClientLogger.LOGGER.trace("Calling rollback:: " + convert(xid));
      }

      try
      {
         boolean wasStarted = started;

         if (wasStarted)
         {
            stop(false);
         }

         // We need to make sure we don't get any inflight messages
         for (ClientConsumerInternal consumer : cloneConsumers())
         {
            consumer.clear(false);
         }

         flushAcks();

         try
         {
            sessionContext.xaRollback(xid, wasStarted);
         }
         finally
         {
            if (wasStarted)
            {
               start();
            }
         }

         workDone = false;
      }
      catch (XAException xae)
      {
         throw xae;
      }
      catch (ActiveMQException e)
      {
         if (e.getType() == ActiveMQExceptionType.UNBLOCKED)
         {
            // Unblocked on failover
            throw new XAException(XAException.XA_RETRY);
         }

         // This should never occur
         XAException xaException = new XAException(XAException.XAER_RMERR);
         xaException.initCause(e);
         throw xaException;
      }
      catch (Throwable t)
      {
         // This could occur if the TM interrupts the thread
         XAException xaException = new XAException(XAException.XAER_RMERR);
         xaException.initCause(t);
         throw xaException;
      }
   }

   public void start(final Xid xid, final int flags) throws XAException
   {
      if (ActiveMQClientLogger.LOGGER.isTraceEnabled())
      {
         ActiveMQClientLogger.LOGGER.trace("Calling start:: " + convert(xid) + " clientXID=" + xid + " flags = " + convertTXFlag(flags));
      }

      checkXA();

      try
      {
         sessionContext.xaStart(xid, flags);

         this.currentXID = xid;
      }
      catch (XAException xae)
      {
         throw xae;
      }
      catch (ActiveMQException e)
      {
         // we can retry this only because we know for sure that no work would have been done
         if (e.getType() == ActiveMQExceptionType.UNBLOCKED)
         {
            try
            {
               sessionContext.xaStart(xid, flags);
            }
            catch (XAException xae)
            {
               throw xae;
            }
            catch (Throwable t)
            {
               // This could occur if the TM interrupts the thread
               XAException xaException = new XAException(XAException.XAER_RMERR);
               xaException.initCause(t);
               throw xaException;
            }
         }

         // This should never occur
         XAException xaException = new XAException(XAException.XAER_RMERR);
         xaException.initCause(e);
         throw xaException;
      }
      catch (Throwable t)
      {
         // This could occur if the TM interrupts the thread
         XAException xaException = new XAException(XAException.XAER_RMERR);
         xaException.initCause(t);
         throw xaException;
      }
   }

   // FailureListener implementation --------------------------------------------

   public void connectionFailed(final ActiveMQException me, boolean failedOver)
   {
      try
      {
         cleanUp(false);
      }
      catch (Exception e)
      {
         ActiveMQClientLogger.LOGGER.failedToCleanupSession(e);
      }
   }

   public void connectionFailed(final ActiveMQException me, boolean failedOver, String scaleDownTargetNodeID)
   {
      connectionFailed(me, failedOver);
   }

   // Public
   // ----------------------------------------------------------------------------

   public void setForceNotSameRM(final boolean force)
   {
      forceNotSameRM = force;
   }

   public RemotingConnection getConnection()
   {
      return sessionContext.getRemotingConnection();
   }

   @Override
   public String toString()
   {
      StringBuilder buffer = new StringBuilder();
      synchronized (metadata)
      {
         for (Map.Entry<String, String> entry : metadata.entrySet())
         {
            buffer.append(entry.getKey() + "=" + entry.getValue() + ",");
         }
      }

      return "ClientSessionImpl [name=" + name +
         ", username=" +
         username +
         ", closed=" +
         closed +
         ", factory = " + this.sessionFactory +
         ", metaData=(" +
         buffer +
         ")]@" +
         Integer.toHexString(hashCode());
   }

   /**
    * @param queueName
    * @param filterString
    * @param windowSize
    * @param browseOnly
    * @return
    * @throws ActiveMQException
    */
   private ClientConsumer internalCreateConsumer(final SimpleString queueName,
                                                 final SimpleString filterString,
                                                 final int windowSize,
                                                 final int maxRate,
                                                 final boolean browseOnly) throws ActiveMQException
   {
      checkClosed();

      ClientConsumerInternal consumer = sessionContext.createConsumer(queueName, filterString, windowSize, maxRate,
                                                                      ackBatchSize, browseOnly, executor, flowControlExecutor);

      addConsumer(consumer);

      // Now we send window size credits to start the consumption
      // We even send it if windowSize == -1, since we need to start the
      // consumer

      // TODO: this could semantically change on other servers. I know for instance on stomp this is just an ignore
      if (windowSize != 0)
      {
         sessionContext.sendConsumerCredits(consumer, windowSize);
      }

      return consumer;
   }

   private ClientProducer internalCreateProducer(final SimpleString address, final int maxRate) throws ActiveMQException
   {
      checkClosed();

      ClientProducerInternal producer = new ClientProducerImpl(this,
                                                               address,
                                                               maxRate == -1 ? null
                                                                  : new TokenBucketLimiterImpl(maxRate, false),
                                                               autoCommitSends && blockOnNonDurableSend,
                                                               autoCommitSends && blockOnDurableSend,
                                                               autoGroup,
                                                               groupID == null ? null : new SimpleString(groupID),
                                                               minLargeMessageSize,
                                                               sessionContext);

      addProducer(producer);

      return producer;
   }

   private void internalCreateQueue(final SimpleString address,
                                    final SimpleString queueName,
                                    final SimpleString filterString,
                                    final boolean durable,
                                    final boolean temp) throws ActiveMQException
   {
      checkClosed();

      if (durable && temp)
      {
         throw ActiveMQClientMessageBundle.BUNDLE.queueMisConfigured();
      }

      startCall();
      try
      {
         sessionContext.createQueue(address, queueName, filterString, durable, temp);
      }
      finally
      {
         endCall();
      }
   }

   private void checkXA() throws XAException
   {
      if (!xa)
      {
         ActiveMQClientLogger.LOGGER.sessionNotXA();
         throw new XAException(XAException.XAER_RMERR);
      }
   }

   private void checkClosed() throws ActiveMQException
   {
      if (closed || inClose)
      {
         throw ActiveMQClientMessageBundle.BUNDLE.sessionClosed();
      }
   }

   private ClientConsumerInternal getConsumer(final ConsumerContext consumerContext)
   {
      synchronized (consumers)
      {
         ClientConsumerInternal consumer = consumers.get(consumerContext);
         return consumer;
      }
   }

   private void doCleanup(boolean failingOver)
   {
      if (ActiveMQClientLogger.LOGGER.isDebugEnabled())
      {
         ActiveMQClientLogger.LOGGER.debug("calling cleanup on " + this);
      }

      synchronized (this)
      {
         closed = true;

         sessionContext.cleanup();
      }

      sessionFactory.removeSession(this, failingOver);
   }

   private void cleanUpChildren() throws ActiveMQException
   {
      Set<ClientConsumerInternal> consumersClone = cloneConsumers();

      for (ClientConsumerInternal consumer : consumersClone)
      {
         consumer.cleanUp();
      }

      Set<ClientProducerInternal> producersClone = cloneProducers();

      for (ClientProducerInternal producer : producersClone)
      {
         producer.cleanUp();
      }
   }

   /**
    * Not part of the interface, used on tests only
    *
    * @return
    */
   public Set<ClientProducerInternal> cloneProducers()
   {
      Set<ClientProducerInternal> producersClone;

      synchronized (producers)
      {
         producersClone = new HashSet<ClientProducerInternal>(producers);
      }
      return producersClone;
   }

   /**
    * Not part of the interface, used on tests only
    *
    * @return
    */
   public Set<ClientConsumerInternal> cloneConsumers()
   {
      synchronized (consumers)
      {
         return new HashSet<ClientConsumerInternal>(consumers.values());
      }
   }

   private void closeChildren() throws ActiveMQException
   {
      Set<ClientConsumerInternal> consumersClone = cloneConsumers();

      for (ClientConsumer consumer : consumersClone)
      {
         consumer.close();
      }

      Set<ClientProducerInternal> producersClone = cloneProducers();

      for (ClientProducer producer : producersClone)
      {
         producer.close();
      }
   }

   private void flushAcks() throws ActiveMQException
   {
      for (ClientConsumerInternal consumer : cloneConsumers())
      {
         consumer.flushAcks();
      }
   }

   /**
    * If you ever tried to debug XIDs you will know what this is about.
    * This will serialize and deserialize the XID to the same way it's going to be printed on server logs
    * or print-data.
    * <p/>
    * This will convert to the same XID deserialized on the Server, hence we will be able to debug eventual stuff
    *
    * @param xid
    * @return
    */
   public static Object convert(Xid xid)
   {
      ActiveMQBuffer buffer = ActiveMQBuffers.dynamicBuffer(200);
      XidCodecSupport.encodeXid(xid, buffer);

      Object obj = XidCodecSupport.decodeXid(buffer);

      return "xid=" + obj + ",clientXID=" + xid;
   }

   private String convertTXFlag(final int flags)
   {
      if (flags == XAResource.TMSUSPEND)
      {
         return "SESS_XA_SUSPEND";
      }
      else if (flags == XAResource.TMSUCCESS)
      {
         return "TMSUCCESS";
      }
      else if (flags == XAResource.TMFAIL)
      {
         return "TMFAIL";
      }
      else if (flags == XAResource.TMJOIN)
      {
         return "TMJOIN";
      }
      else if (flags == XAResource.TMRESUME)
      {
         return "TMRESUME";
      }
      else if (flags == XAResource.TMNOFLAGS)
      {
         // Don't need to flush since the previous end will have done this
         return "TMNOFLAGS";
      }
      else
      {
         return "XAER_INVAL(" + flags + ")";
      }
   }

   @Override
   public void setStopSignal()
   {
      mayAttemptToFailover = false;
   }

   @Override
   public boolean isConfirmationWindowEnabled()
   {
      if (confirmationWindowWarning.disabled)
      {
         if (!confirmationWindowWarning.warningIssued.get())
         {
            ActiveMQClientLogger.LOGGER.confirmationWindowDisabledWarning();
            confirmationWindowWarning.warningIssued.set(true);
         }
         return false;
      }
      return true;
   }

   @Override
   public void scheduleConfirmation(final SendAcknowledgementHandler handler, final Message message)
   {
      executor.execute(new Runnable()
      {
         @Override
         public void run()
         {
            handler.sendAcknowledged(message);
         }
      });
   }
}

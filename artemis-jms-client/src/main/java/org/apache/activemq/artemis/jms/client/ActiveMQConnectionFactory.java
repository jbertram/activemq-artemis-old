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
package org.apache.activemq.artemis.jms.client;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.JMSRuntimeException;
import javax.jms.JMSSecurityException;
import javax.jms.JMSSecurityRuntimeException;
import javax.jms.QueueConnection;
import javax.jms.TopicConnection;
import javax.jms.XAConnection;
import javax.jms.XAConnectionFactory;
import javax.jms.XAJMSContext;
import javax.jms.XAQueueConnection;
import javax.jms.XATopicConnection;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import java.io.Externalizable;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.URI;

import org.apache.activemq.artemis.api.core.DiscoveryGroupConfiguration;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.UDPBroadcastEndpointFactory;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.api.jms.JMSFactoryType;
import org.apache.activemq.artemis.jms.referenceable.ConnectionFactoryObjectFactory;
import org.apache.activemq.artemis.jms.referenceable.SerializableObjectRefAddr;
import org.apache.activemq.artemis.uri.ConnectionFactoryParser;
import org.apache.activemq.artemis.uri.ServerLocatorParser;

/**
 * ActiveMQ implementation of a JMS ConnectionFactory.
 */
public class ActiveMQConnectionFactory implements Externalizable, Referenceable, ConnectionFactory, XAConnectionFactory
{
   private ServerLocator serverLocator;

   private String clientID;

   private int dupsOKBatchSize = ActiveMQClient.DEFAULT_ACK_BATCH_SIZE;

   private int transactionBatchSize = ActiveMQClient.DEFAULT_ACK_BATCH_SIZE;

   private  boolean readOnly;

   public void writeExternal(ObjectOutput out) throws IOException
   {
      ConnectionFactoryParser parser = new ConnectionFactoryParser();
      String scheme;
      if (serverLocator.getDiscoveryGroupConfiguration() != null)
      {
         if (serverLocator.getDiscoveryGroupConfiguration().getBroadcastEndpointFactory() instanceof UDPBroadcastEndpointFactory)
         {
            scheme = "udp";
         }
         else
         {
            scheme = "jgroups";
         }
      }
      else
      {
         scheme = "tcp";
      }
      try
      {
         URI uri = parser.createSchema(scheme, this);
         out.writeUTF(uri.toASCIIString());
      }
      catch (Exception e)
      {
         if (e instanceof IOException)
         {
            throw (IOException) e;
         }
         throw new IOException(e);
      }
   }

   public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException
   {
      String url = in.readUTF();
      ConnectionFactoryParser parser = new ConnectionFactoryParser();
      ServerLocatorParser locatorParser = new ServerLocatorParser();
      try
      {
         URI uri = new URI(url);
         serverLocator = locatorParser.newObject(uri, null);
         parser.populateObject(uri, this);
      }
      catch (Exception e)
      {
         throw new InvalidObjectException(e.getMessage());
      }
   }

   public ActiveMQConnectionFactory()
   {
      serverLocator = null;
   }

   public ActiveMQConnectionFactory(final ServerLocator serverLocator)
   {
      this.serverLocator = serverLocator;

      serverLocator.disableFinalizeCheck();
   }

   public ActiveMQConnectionFactory(final boolean ha, final DiscoveryGroupConfiguration groupConfiguration)
   {
      if (ha)
      {
         serverLocator = ActiveMQClient.createServerLocatorWithHA(groupConfiguration);
      }
      else
      {
         serverLocator = ActiveMQClient.createServerLocatorWithoutHA(groupConfiguration);
      }

      serverLocator.disableFinalizeCheck();
   }

   public ActiveMQConnectionFactory(final boolean ha, final TransportConfiguration... initialConnectors)
   {
      if (ha)
      {
         serverLocator = ActiveMQClient.createServerLocatorWithHA(initialConnectors);
      }
      else
      {
         serverLocator = ActiveMQClient.createServerLocatorWithoutHA(initialConnectors);
      }

      serverLocator.disableFinalizeCheck();
   }

   // ConnectionFactory implementation -------------------------------------------------------------

   public Connection createConnection() throws JMSException
   {
      return createConnection(null, null);
   }

   public Connection createConnection(final String username, final String password) throws JMSException
   {
      return createConnectionInternal(username, password, false, ActiveMQConnection.TYPE_GENERIC_CONNECTION);
   }

   @Override
   public JMSContext createContext()
   {
      return createContext(null, null);
   }

   @Override
   public JMSContext createContext(final int sessionMode)
   {
      return createContext(null, null, sessionMode);
   }

   @Override
   public JMSContext createContext(final String userName, final String password)
   {
      return createContext(userName, password, JMSContext.AUTO_ACKNOWLEDGE);
   }

   @Override
   public JMSContext createContext(String userName, String password, int sessionMode)
   {
      validateSessionMode(sessionMode);
      try
      {
         ActiveMQConnection connection =
            createConnectionInternal(userName, password, false, ActiveMQConnection.TYPE_GENERIC_CONNECTION);
         return connection.createContext(sessionMode);
      }
      catch (JMSSecurityException e)
      {
         throw new JMSSecurityRuntimeException(e.getMessage(), e.getErrorCode(), e);
      }
      catch (JMSException e)
      {
         throw JmsExceptionUtils.convertToRuntimeException(e);
      }
   }

   /**
    * @param mode
    */
   private static void validateSessionMode(int mode)
   {
      switch (mode)
      {
         case JMSContext.AUTO_ACKNOWLEDGE:
         case JMSContext.CLIENT_ACKNOWLEDGE:
         case JMSContext.DUPS_OK_ACKNOWLEDGE:
         case JMSContext.SESSION_TRANSACTED:
         {
            return;
         }
         default:
            throw new JMSRuntimeException("Invalid Session Mode: " + mode);
      }
   }

   // QueueConnectionFactory implementation --------------------------------------------------------

   public QueueConnection createQueueConnection() throws JMSException
   {
      return createQueueConnection(null, null);
   }

   public QueueConnection createQueueConnection(final String username, final String password) throws JMSException
   {
      return createConnectionInternal(username, password, false, ActiveMQConnection.TYPE_QUEUE_CONNECTION);
   }

   // TopicConnectionFactory implementation --------------------------------------------------------

   public TopicConnection createTopicConnection() throws JMSException
   {
      return createTopicConnection(null, null);
   }

   public TopicConnection createTopicConnection(final String username, final String password) throws JMSException
   {
      return createConnectionInternal(username, password, false, ActiveMQConnection.TYPE_TOPIC_CONNECTION);
   }

   // XAConnectionFactory implementation -----------------------------------------------------------

   public XAConnection createXAConnection() throws JMSException
   {
      return createXAConnection(null, null);
   }

   public XAConnection createXAConnection(final String username, final String password) throws JMSException
   {
      return (XAConnection) createConnectionInternal(username, password, true, ActiveMQConnection.TYPE_GENERIC_CONNECTION);
   }

   @Override
   public XAJMSContext createXAContext()
   {
      return createXAContext(null, null);
   }

   @Override
   public XAJMSContext createXAContext(String userName, String password)
   {
      try
      {
         ActiveMQConnection connection =
            createConnectionInternal(userName, password, true, ActiveMQConnection.TYPE_GENERIC_CONNECTION);
         return connection.createXAContext();
      }
      catch (JMSSecurityException e)
      {
         throw new JMSSecurityRuntimeException(e.getMessage(), e.getErrorCode(), e);
      }
      catch (JMSException e)
      {
         throw JmsExceptionUtils.convertToRuntimeException(e);
      }
   }

   // XAQueueConnectionFactory implementation ------------------------------------------------------

   public XAQueueConnection createXAQueueConnection() throws JMSException
   {
      return createXAQueueConnection(null, null);
   }

   public XAQueueConnection createXAQueueConnection(final String username, final String password) throws JMSException
   {
      return (XAQueueConnection) createConnectionInternal(username, password, true, ActiveMQConnection.TYPE_QUEUE_CONNECTION);
   }

   // XATopicConnectionFactory implementation ------------------------------------------------------

   public XATopicConnection createXATopicConnection() throws JMSException
   {
      return createXATopicConnection(null, null);
   }

   public XATopicConnection createXATopicConnection(final String username, final String password) throws JMSException
   {
      return (XATopicConnection) createConnectionInternal(username, password, true, ActiveMQConnection.TYPE_TOPIC_CONNECTION);
   }

   @Override
   public Reference getReference() throws NamingException
   {
      return new Reference(this.getClass().getCanonicalName(),
                           new SerializableObjectRefAddr("ActiveMQ-CF", this),
                           ConnectionFactoryObjectFactory.class.getCanonicalName(),
                           null);
   }

   // Public ---------------------------------------------------------------------------------------

   public boolean isHA()
   {
      return serverLocator.isHA();
   }

   public synchronized String getConnectionLoadBalancingPolicyClassName()
   {
      return serverLocator.getConnectionLoadBalancingPolicyClassName();
   }

   public synchronized void setConnectionLoadBalancingPolicyClassName(final String connectionLoadBalancingPolicyClassName)
   {
      checkWrite();
      serverLocator.setConnectionLoadBalancingPolicyClassName(connectionLoadBalancingPolicyClassName);
   }

   public synchronized TransportConfiguration[] getStaticConnectors()
   {
      return serverLocator.getStaticTransportConfigurations();
   }

   public synchronized DiscoveryGroupConfiguration getDiscoveryGroupConfiguration()
   {
      return serverLocator.getDiscoveryGroupConfiguration();
   }

   public synchronized String getClientID()
   {
      return clientID;
   }

   public synchronized void setClientID(final String clientID)
   {
      checkWrite();
      this.clientID = clientID;
   }

   public synchronized int getDupsOKBatchSize()
   {
      return dupsOKBatchSize;
   }

   public synchronized void setDupsOKBatchSize(final int dupsOKBatchSize)
   {
      checkWrite();
      this.dupsOKBatchSize = dupsOKBatchSize;
   }

   public synchronized int getTransactionBatchSize()
   {
      return transactionBatchSize;
   }

   public synchronized void setTransactionBatchSize(final int transactionBatchSize)
   {
      checkWrite();
      this.transactionBatchSize = transactionBatchSize;
   }

   public synchronized long getClientFailureCheckPeriod()
   {
      return serverLocator.getClientFailureCheckPeriod();
   }

   public synchronized void setClientFailureCheckPeriod(final long clientFailureCheckPeriod)
   {
      checkWrite();
      serverLocator.setClientFailureCheckPeriod(clientFailureCheckPeriod);
   }

   public synchronized long getConnectionTTL()
   {
      return serverLocator.getConnectionTTL();
   }

   public synchronized void setConnectionTTL(final long connectionTTL)
   {
      checkWrite();
      serverLocator.setConnectionTTL(connectionTTL);
   }

   public synchronized long getCallTimeout()
   {
      return serverLocator.getCallTimeout();
   }

   public synchronized void setCallTimeout(final long callTimeout)
   {
      checkWrite();
      serverLocator.setCallTimeout(callTimeout);
   }

   public synchronized long getCallFailoverTimeout()
   {
      return serverLocator.getCallFailoverTimeout();
   }

   public synchronized void setCallFailoverTimeout(final long callTimeout)
   {
      checkWrite();
      serverLocator.setCallFailoverTimeout(callTimeout);
   }

   public synchronized int getConsumerWindowSize()
   {
      return serverLocator.getConsumerWindowSize();
   }

   public synchronized void setConsumerWindowSize(final int consumerWindowSize)
   {
      checkWrite();
      serverLocator.setConsumerWindowSize(consumerWindowSize);
   }

   public synchronized int getConsumerMaxRate()
   {
      return serverLocator.getConsumerMaxRate();
   }

   public synchronized void setConsumerMaxRate(final int consumerMaxRate)
   {
      checkWrite();
      serverLocator.setConsumerMaxRate(consumerMaxRate);
   }

   public synchronized int getConfirmationWindowSize()
   {
      return serverLocator.getConfirmationWindowSize();
   }

   public synchronized void setConfirmationWindowSize(final int confirmationWindowSize)
   {
      checkWrite();
      serverLocator.setConfirmationWindowSize(confirmationWindowSize);
   }

   public synchronized int getProducerMaxRate()
   {
      return serverLocator.getProducerMaxRate();
   }

   public synchronized void setProducerMaxRate(final int producerMaxRate)
   {
      checkWrite();
      serverLocator.setProducerMaxRate(producerMaxRate);
   }

   public synchronized int getProducerWindowSize()
   {
      return serverLocator.getProducerWindowSize();
   }

   public synchronized void setProducerWindowSize(final int producerWindowSize)
   {
      checkWrite();
      serverLocator.setProducerWindowSize(producerWindowSize);
   }

   /**
    * @param cacheLargeMessagesClient
    */
   public synchronized void setCacheLargeMessagesClient(final boolean cacheLargeMessagesClient)
   {
      checkWrite();
      serverLocator.setCacheLargeMessagesClient(cacheLargeMessagesClient);
   }

   public synchronized boolean isCacheLargeMessagesClient()
   {
      return serverLocator.isCacheLargeMessagesClient();
   }

   public synchronized int getMinLargeMessageSize()
   {
      return serverLocator.getMinLargeMessageSize();
   }

   public synchronized void setMinLargeMessageSize(final int minLargeMessageSize)
   {
      checkWrite();
      serverLocator.setMinLargeMessageSize(minLargeMessageSize);
   }

   public synchronized boolean isBlockOnAcknowledge()
   {
      return serverLocator.isBlockOnAcknowledge();
   }

   public synchronized void setBlockOnAcknowledge(final boolean blockOnAcknowledge)
   {
      checkWrite();
      serverLocator.setBlockOnAcknowledge(blockOnAcknowledge);
   }

   public synchronized boolean isBlockOnNonDurableSend()
   {
      return serverLocator.isBlockOnNonDurableSend();
   }

   public synchronized void setBlockOnNonDurableSend(final boolean blockOnNonDurableSend)
   {
      checkWrite();
      serverLocator.setBlockOnNonDurableSend(blockOnNonDurableSend);
   }

   public synchronized boolean isBlockOnDurableSend()
   {
      return serverLocator.isBlockOnDurableSend();
   }

   public synchronized void setBlockOnDurableSend(final boolean blockOnDurableSend)
   {
      checkWrite();
      serverLocator.setBlockOnDurableSend(blockOnDurableSend);
   }

   public synchronized boolean isAutoGroup()
   {
      return serverLocator.isAutoGroup();
   }

   public synchronized void setAutoGroup(final boolean autoGroup)
   {
      checkWrite();
      serverLocator.setAutoGroup(autoGroup);
   }

   public synchronized boolean isPreAcknowledge()
   {
      return serverLocator.isPreAcknowledge();
   }

   public synchronized void setPreAcknowledge(final boolean preAcknowledge)
   {
      checkWrite();
      serverLocator.setPreAcknowledge(preAcknowledge);
   }

   public synchronized long getRetryInterval()
   {
      return serverLocator.getRetryInterval();
   }

   public synchronized void setRetryInterval(final long retryInterval)
   {
      checkWrite();
      serverLocator.setRetryInterval(retryInterval);
   }

   public synchronized long getMaxRetryInterval()
   {
      return serverLocator.getMaxRetryInterval();
   }

   public synchronized void setMaxRetryInterval(final long retryInterval)
   {
      checkWrite();
      serverLocator.setMaxRetryInterval(retryInterval);
   }

   public synchronized double getRetryIntervalMultiplier()
   {
      return serverLocator.getRetryIntervalMultiplier();
   }

   public synchronized void setRetryIntervalMultiplier(final double retryIntervalMultiplier)
   {
      checkWrite();
      serverLocator.setRetryIntervalMultiplier(retryIntervalMultiplier);
   }

   public synchronized int getReconnectAttempts()
   {
      return serverLocator.getReconnectAttempts();
   }

   public synchronized void setReconnectAttempts(final int reconnectAttempts)
   {
      checkWrite();
      serverLocator.setReconnectAttempts(reconnectAttempts);
   }

   public synchronized void setInitialConnectAttempts(final int reconnectAttempts)
   {
      checkWrite();
      serverLocator.setInitialConnectAttempts(reconnectAttempts);
   }

   public synchronized int getInitialConnectAttempts()
   {
      return serverLocator.getInitialConnectAttempts();
   }

   public synchronized boolean isFailoverOnInitialConnection()
   {
      return serverLocator.isFailoverOnInitialConnection();
   }

   public synchronized void setFailoverOnInitialConnection(final boolean failover)
   {
      checkWrite();
      serverLocator.setFailoverOnInitialConnection(failover);
   }

   public synchronized boolean isUseGlobalPools()
   {
      return serverLocator.isUseGlobalPools();
   }

   public synchronized void setUseGlobalPools(final boolean useGlobalPools)
   {
      checkWrite();
      serverLocator.setUseGlobalPools(useGlobalPools);
   }

   public synchronized int getScheduledThreadPoolMaxSize()
   {
      return serverLocator.getScheduledThreadPoolMaxSize();
   }

   public synchronized void setScheduledThreadPoolMaxSize(final int scheduledThreadPoolMaxSize)
   {
      checkWrite();
      serverLocator.setScheduledThreadPoolMaxSize(scheduledThreadPoolMaxSize);
   }

   public synchronized int getThreadPoolMaxSize()
   {
      return serverLocator.getThreadPoolMaxSize();
   }

   public synchronized void setThreadPoolMaxSize(final int threadPoolMaxSize)
   {
      checkWrite();
      serverLocator.setThreadPoolMaxSize(threadPoolMaxSize);
   }

   public synchronized int getInitialMessagePacketSize()
   {
      return serverLocator.getInitialMessagePacketSize();
   }

   public synchronized void setInitialMessagePacketSize(final int size)
   {
      checkWrite();
      serverLocator.setInitialMessagePacketSize(size);
   }

   public void setGroupID(final String groupID)
   {
      serverLocator.setGroupID(groupID);
   }

   public String getGroupID()
   {
      return serverLocator.getGroupID();
   }

   public boolean isCompressLargeMessage()
   {
      return serverLocator.isCompressLargeMessage();
   }

   public void setCompressLargeMessage(boolean avoidLargeMessages)
   {
      serverLocator.setCompressLargeMessage(avoidLargeMessages);
   }

   public void close()
   {
      ServerLocator locator0 = serverLocator;
      if (locator0 != null)
         locator0.close();
   }

   public ServerLocator getServerLocator()
   {
      return serverLocator;
   }

   public int getFactoryType()
   {
      return JMSFactoryType.CF.intValue();
   }

   // Package protected ----------------------------------------------------------------------------

   // Protected ------------------------------------------------------------------------------------

   protected synchronized ActiveMQConnection createConnectionInternal(final String username,
                                                                     final String password,
                                                                     final boolean isXA,
                                                                     final int type) throws JMSException
   {
      readOnly = true;

      ClientSessionFactory factory;

      try
      {
         factory = serverLocator.createSessionFactory();
      }
      catch (Exception e)
      {
         JMSException jmse = new JMSException("Failed to create session factory");

         jmse.initCause(e);
         jmse.setLinkedException(e);

         throw jmse;
      }

      ActiveMQConnection connection = null;

      if (isXA)
      {
         if (type == ActiveMQConnection.TYPE_GENERIC_CONNECTION)
         {
            connection = new ActiveMQXAConnection(username,
                                                 password,
                                                 type,
                                                 clientID,
                                                 dupsOKBatchSize,
                                                 transactionBatchSize,
                                                 factory);
         }
         else if (type == ActiveMQConnection.TYPE_QUEUE_CONNECTION)
         {
            connection =
               new ActiveMQXAConnection(username,
                                       password,
                                       type,
                                       clientID,
                                       dupsOKBatchSize,
                                       transactionBatchSize,
                                       factory);
         }
         else if (type == ActiveMQConnection.TYPE_TOPIC_CONNECTION)
         {
            connection =
               new ActiveMQXAConnection(username,
                                       password,
                                       type,
                                       clientID,
                                       dupsOKBatchSize,
                                       transactionBatchSize,
                                       factory);
         }
      }
      else
      {
         if (type == ActiveMQConnection.TYPE_GENERIC_CONNECTION)
         {
            connection = new ActiveMQConnection(username,
                                               password,
                                               type,
                                               clientID,
                                               dupsOKBatchSize,
                                               transactionBatchSize,
                                               factory);
         }
         else if (type == ActiveMQConnection.TYPE_QUEUE_CONNECTION)
         {
            connection =
               new ActiveMQConnection(username,
                                     password,
                                     type,
                                     clientID,
                                     dupsOKBatchSize,
                                     transactionBatchSize,
                                     factory);
         }
         else if (type == ActiveMQConnection.TYPE_TOPIC_CONNECTION)
         {
            connection =
               new ActiveMQConnection(username,
                                     password,
                                     type,
                                     clientID,
                                     dupsOKBatchSize,
                                     transactionBatchSize,
                                     factory);
         }
      }

      if (connection == null)
      {
         throw new JMSException("Failed to create connection: invalid type " + type);
      }
      connection.setReference(this);

      try
      {
         connection.authorize();
      }
      catch (JMSException e)
      {
         try
         {
            connection.close();
         }
         catch (JMSException me)
         {
         }
         throw e;
      }

      return connection;
   }

   @Override
   public String toString()
   {
      return "ActiveMQConnectionFactory [serverLocator=" + serverLocator +
         ", clientID=" +
         clientID +
         ", consumerWindowSize = " +
         getConsumerWindowSize() +
         ", dupsOKBatchSize=" +
         dupsOKBatchSize +
         ", transactionBatchSize=" +
         transactionBatchSize +
         ", readOnly=" +
         readOnly +
         "]";
   }


   // Private --------------------------------------------------------------------------------------

   private void checkWrite()
   {
      if (readOnly)
      {
         throw new IllegalStateException("Cannot set attribute on ActiveMQConnectionFactory after it has been used");
      }
   }

   @Override
   protected void finalize() throws Throwable
   {
      try
      {
         serverLocator.close();
      }
      catch (Exception e)
      {
         e.printStackTrace();
         //not much we can do here
      }
      super.finalize();
   }
}

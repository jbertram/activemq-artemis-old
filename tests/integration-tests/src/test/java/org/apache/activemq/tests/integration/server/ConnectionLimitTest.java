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
package org.apache.activemq.tests.integration.server;

import org.apache.activemq.api.core.ActiveMQConnectionTimedOutException;
import org.apache.activemq.api.core.ActiveMQNotConnectedException;
import org.apache.activemq.api.core.TransportConfiguration;
import org.apache.activemq.api.core.client.ClientSession;
import org.apache.activemq.api.core.client.ClientSessionFactory;
import org.apache.activemq.api.core.client.ServerLocator;
import org.apache.activemq.core.config.Configuration;
import org.apache.activemq.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.core.server.ActiveMQServer;
import org.apache.activemq.core.server.ActiveMQServers;
import org.apache.activemq.tests.util.UnitTestCase;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ConnectionLimitTest extends UnitTestCase
{
   private ActiveMQServer server;

   private TransportConfiguration liveTC;

   @Override
   @Before
   public void setUp() throws Exception
   {
      super.setUp();

      Map nettyParams = new HashMap();
      nettyParams.put(TransportConstants.CONNECTIONS_ALLOWED, 1);

      Map invmParams = new HashMap();
      invmParams.put(org.apache.activemq.core.remoting.impl.invm.TransportConstants.CONNECTIONS_ALLOWED, 1);

      Configuration configuration = createBasicConfig()
              .addAcceptorConfiguration(new TransportConfiguration(NETTY_ACCEPTOR_FACTORY, nettyParams))
              .addAcceptorConfiguration(new TransportConfiguration(INVM_ACCEPTOR_FACTORY, invmParams));

      server = addServer(ActiveMQServers.newActiveMQServer(configuration, false));
      server.start();
   }

   @Test
   public void testInVMConnectionLimit() throws Exception
   {
      ServerLocator locator = addServerLocator(createNonHALocator(false));
      ClientSessionFactory clientSessionFactory = locator.createSessionFactory();

      try
      {
         ClientSessionFactory extraClientSessionFactory = locator.createSessionFactory();
         fail("creating a session factory here should fail");
      }
      catch (Exception e)
      {
         assertTrue(e instanceof ActiveMQNotConnectedException);
      }
   }

   @Test
   public void testNettyConnectionLimit() throws Exception
   {
      ServerLocator locator = addServerLocator(createNonHALocator(true));
      locator.setCallTimeout(3000);
      ClientSessionFactory clientSessionFactory = locator.createSessionFactory();
      ClientSession clientSession = addClientSession(clientSessionFactory.createSession());
      ClientSessionFactory extraClientSessionFactory = locator.createSessionFactory();

      try
      {
         ClientSession extraClientSession = addClientSession(extraClientSessionFactory.createSession());
         fail("creating a session here should fail");
      }
      catch (Exception e)
      {
         assertTrue(e instanceof ActiveMQConnectionTimedOutException);
      }
   }
}

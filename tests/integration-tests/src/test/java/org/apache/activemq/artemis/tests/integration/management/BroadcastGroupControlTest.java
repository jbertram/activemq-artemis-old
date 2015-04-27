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
package org.apache.activemq.artemis.tests.integration.management;

import org.apache.activemq.artemis.tests.util.UnitTestCase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;

import org.apache.activemq.artemis.api.core.BroadcastGroupConfiguration;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.UDPBroadcastEndpointFactory;
import org.apache.activemq.artemis.api.core.management.BroadcastGroupControl;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.tests.util.RandomUtil;
import org.apache.activemq.artemis.api.core.Pair;
import org.apache.activemq.artemis.utils.json.JSONArray;

public class BroadcastGroupControlTest extends ManagementTestBase
{

   private ActiveMQServer service;

   // Static --------------------------------------------------------

   public static BroadcastGroupConfiguration randomBroadcastGroupConfiguration(final List<String> connectorInfos)
   {
      return new BroadcastGroupConfiguration()
         .setName(RandomUtil.randomString())
         .setBroadcastPeriod(RandomUtil.randomPositiveInt())
         .setConnectorInfos(connectorInfos)
         .setEndpointFactory(new UDPBroadcastEndpointFactory()
                                   .setGroupAddress("231.7.7.7")
                                   .setGroupPort(1199)
                                   .setLocalBindPort(1198));
   }

   public static Pair<String, String> randomPair()
   {
      return new Pair<String, String>(RandomUtil.randomString(), RandomUtil.randomString());
   }

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   @Test
   public void testAttributes() throws Exception
   {
      TransportConfiguration connectorConfiguration = new TransportConfiguration(UnitTestCase.NETTY_CONNECTOR_FACTORY);
      List<String> connectorInfos = new ArrayList<String>();
      connectorInfos.add(connectorConfiguration.getName());
      BroadcastGroupConfiguration broadcastGroupConfig = BroadcastGroupControlTest.randomBroadcastGroupConfiguration(connectorInfos);

      Configuration conf = createBasicConfig()
         .addConnectorConfiguration(connectorConfiguration.getName(), connectorConfiguration)
         .addBroadcastGroupConfiguration(broadcastGroupConfig)
         .addAcceptorConfiguration(new TransportConfiguration(UnitTestCase.INVM_ACCEPTOR_FACTORY));
      service = addServer(ActiveMQServers.newActiveMQServer(conf, mbeanServer, false));
      service.start();

      BroadcastGroupControl broadcastGroupControl = createManagementControl(broadcastGroupConfig.getName());

      UDPBroadcastEndpointFactory udpCfg = (UDPBroadcastEndpointFactory) broadcastGroupConfig.getEndpointFactory();
      Assert.assertEquals(broadcastGroupConfig.getName(), broadcastGroupControl.getName());
      Assert.assertEquals(udpCfg.getGroupAddress(), broadcastGroupControl.getGroupAddress());
      Assert.assertEquals(udpCfg.getGroupPort(), broadcastGroupControl.getGroupPort());
      Assert.assertEquals(udpCfg.getLocalBindPort(), broadcastGroupControl.getLocalBindPort());
      Assert.assertEquals(broadcastGroupConfig.getBroadcastPeriod(), broadcastGroupControl.getBroadcastPeriod());

      Object[] connectorPairs = broadcastGroupControl.getConnectorPairs();
      Assert.assertEquals(1, connectorPairs.length);

      String connectorPairData = (String)connectorPairs[0];
      Assert.assertEquals(broadcastGroupConfig.getConnectorInfos().get(0), connectorPairData);
      String jsonString = broadcastGroupControl.getConnectorPairsAsJSON();
      Assert.assertNotNull(jsonString);
      JSONArray array = new JSONArray(jsonString);
      Assert.assertEquals(1, array.length());
      Assert.assertEquals(broadcastGroupConfig.getConnectorInfos().get(0), array.getString(0));

      Assert.assertTrue(broadcastGroupControl.isStarted());
   }

   @Test
   public void testStartStop() throws Exception
   {
      TransportConfiguration connectorConfiguration = new TransportConfiguration(UnitTestCase.NETTY_CONNECTOR_FACTORY);
      List<String> connectorInfos = new ArrayList<String>();
      connectorInfos.add(connectorConfiguration.getName());
      BroadcastGroupConfiguration broadcastGroupConfig = BroadcastGroupControlTest.randomBroadcastGroupConfiguration(connectorInfos);

      Configuration conf = createBasicConfig()
         .addConnectorConfiguration(connectorConfiguration.getName(), connectorConfiguration)
         .addBroadcastGroupConfiguration(broadcastGroupConfig)
         .addAcceptorConfiguration(new TransportConfiguration(UnitTestCase.INVM_ACCEPTOR_FACTORY));
      service = addServer(ActiveMQServers.newActiveMQServer(conf, mbeanServer, false));
      service.start();

      BroadcastGroupControl broadcastGroupControl = createManagementControl(broadcastGroupConfig.getName());

      // started by the server
      Assert.assertTrue(broadcastGroupControl.isStarted());

      broadcastGroupControl.stop();
      Assert.assertFalse(broadcastGroupControl.isStarted());

      broadcastGroupControl.start();
      Assert.assertTrue(broadcastGroupControl.isStarted());
   }

   protected BroadcastGroupControl createManagementControl(final String name) throws Exception
   {
      return ManagementControlHelper.createBroadcastGroupControl(name, mbeanServer);
   }
}

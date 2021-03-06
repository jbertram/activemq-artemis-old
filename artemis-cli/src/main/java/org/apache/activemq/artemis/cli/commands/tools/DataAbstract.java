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

package org.apache.activemq.artemis.cli.commands.tools;

import io.airlift.airline.Option;
import org.apache.activemq.artemis.cli.commands.Configurable;

/** Abstract class for places where you need bindings, journal paging and large messages configuration */
public abstract class DataAbstract extends Configurable
{
   @Option(name = "--bindings", description = "The folder used for bindings (default from broker.xml)")
   public String binding;

   @Option(name = "--journal", description = "The folder used for messages journal (default from broker.xml)")
   public String journal;

   @Option(name = "--paging", description = "The folder used for paging (default from broker.xml)")
   public String paging;

   @Option(name = "--large-messages", description = "The folder used for large-messages (default from broker.xml)")
   public String largeMessges;


   public String getLargeMessages() throws Exception
   {
      if (largeMessges == null)
      {
         largeMessges = getFileConfiguration().getLargeMessagesDirectory();
      }

      return largeMessges;
   }


   public String getBinding() throws Exception
   {
      if (binding == null)
      {
         binding = getFileConfiguration().getBindingsDirectory();
      }

      return binding;
   }

   public String getJournal() throws Exception
   {
      if (journal == null)
      {
         journal = getFileConfiguration().getJournalDirectory();
      }

      return journal;
   }

   public String getPaging() throws Exception
   {
      if (paging == null)
      {
         paging = getFileConfiguration().getPagingDirectory();
      }

      return paging;
   }

}

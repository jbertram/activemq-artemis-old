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
package org.apache.activemq.artemis.core.transaction.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.transaction.xa.Xid;

import org.apache.activemq.artemis.core.server.ActiveMQServerLogger;
import org.apache.activemq.artemis.core.transaction.ResourceManager;
import org.apache.activemq.artemis.core.transaction.Transaction;

public class ResourceManagerImpl implements ResourceManager
{
   private final ConcurrentMap<Xid, Transaction> transactions = new ConcurrentHashMap<Xid, Transaction>();

   private final List<HeuristicCompletionHolder> heuristicCompletions = new ArrayList<HeuristicCompletionHolder>();

   private final int defaultTimeoutSeconds;

   private boolean started = false;

   private TxTimeoutHandler task;

   private final long txTimeoutScanPeriod;

   private final ScheduledExecutorService scheduledThreadPool;

   public ResourceManagerImpl(final int defaultTimeoutSeconds,
                              final long txTimeoutScanPeriod,
                              final ScheduledExecutorService scheduledThreadPool)
   {
      this.defaultTimeoutSeconds = defaultTimeoutSeconds;
      this.txTimeoutScanPeriod = txTimeoutScanPeriod;
      this.scheduledThreadPool = scheduledThreadPool;
   }

   // ActiveMQComponent implementation

   @Override
   public void start() throws Exception
   {
      if (started)
      {
         return;
      }
      task = new TxTimeoutHandler();
      Future<?> future = scheduledThreadPool.scheduleAtFixedRate(task,
                                                                 txTimeoutScanPeriod,
                                                                 txTimeoutScanPeriod,
                                                                 TimeUnit.MILLISECONDS);
      task.setFuture(future);

      started = true;
   }

   @Override
   public void stop() throws Exception
   {
      if (!started)
      {
         return;
      }
      if (task != null)
      {
         task.close();
      }

      started = false;
   }

   @Override
   public boolean isStarted()
   {
      return started;
   }

   // ResourceManager implementation ---------------------------------------------

   public Transaction getTransaction(final Xid xid)
   {
      return transactions.get(xid);
   }

   public boolean putTransaction(final Xid xid, final Transaction tx)
   {
      return transactions.putIfAbsent(xid, tx) == null;
   }

   public Transaction removeTransaction(final Xid xid)
   {
      return transactions.remove(xid);
   }

   public int getTimeoutSeconds()
   {
      return defaultTimeoutSeconds;
   }

   public List<Xid> getPreparedTransactions()
   {
      List<Xid> xids = new ArrayList<Xid>();

      for (Map.Entry<Xid, Transaction> entry : transactions.entrySet())
      {
         if (entry.getValue().getState() == Transaction.State.PREPARED)
         {
            xids.add(entry.getKey());
         }
      }
      return xids;
   }

   public Map<Xid, Long> getPreparedTransactionsWithCreationTime()
   {
      Map<Xid, Long> xidsWithCreationTime = new HashMap<Xid, Long>();

      for (Map.Entry<Xid, Transaction> entry : transactions.entrySet())
      {
         xidsWithCreationTime.put(entry.getKey(), entry.getValue().getCreateTime());
      }
      return xidsWithCreationTime;
   }

   public void putHeuristicCompletion(final long recordID, final Xid xid, final boolean isCommit)
   {
      heuristicCompletions.add(new HeuristicCompletionHolder(recordID, xid, isCommit));
   }

   public List<Xid> getHeuristicCommittedTransactions()
   {
      return getHeuristicCompletedTransactions(true);
   }

   public List<Xid> getHeuristicRolledbackTransactions()
   {
      return getHeuristicCompletedTransactions(false);
   }

   public long removeHeuristicCompletion(final Xid xid)
   {
      Iterator<HeuristicCompletionHolder> iterator = heuristicCompletions.iterator();
      while (iterator.hasNext())
      {
         ResourceManagerImpl.HeuristicCompletionHolder holder = iterator.next();
         if (holder.xid.equals(xid))
         {
            iterator.remove();
            return holder.recordID;
         }
      }
      return -1;
   }

   private List<Xid> getHeuristicCompletedTransactions(final boolean isCommit)
   {
      List<Xid> xids = new ArrayList<Xid>();
      for (HeuristicCompletionHolder holder : heuristicCompletions)
      {
         if (holder.isCommit == isCommit)
         {
            xids.add(holder.xid);
         }
      }
      return xids;
   }

   private class TxTimeoutHandler implements Runnable
   {
      private boolean closed = false;

      private Future<?> future;

      public void run()
      {
         if (closed)
         {
            return;
         }

         Set<Transaction> timedoutTransactions = new HashSet<Transaction>();

         long now = System.currentTimeMillis();

         for (Transaction tx : transactions.values())
         {
            if (tx.hasTimedOut(now, defaultTimeoutSeconds))
            {
               transactions.remove(tx.getXid());
               ActiveMQServerLogger.LOGGER.unexpectedXid(tx.getXid());
               timedoutTransactions.add(tx);
            }
         }

         for (Transaction failedTransaction : timedoutTransactions)
         {
            try
            {
               failedTransaction.rollback();
            }
            catch (Exception e)
            {
               ActiveMQServerLogger.LOGGER.errorTimingOutTX(e, failedTransaction.getXid());
            }
         }
      }

      synchronized void setFuture(final Future<?> future)
      {
         this.future = future;
      }

      void close()
      {
         if (future != null)
         {
            future.cancel(false);
         }

         closed = true;
      }

   }

   private static final class HeuristicCompletionHolder
   {
      public final boolean isCommit;

      public final Xid xid;

      public final long recordID;

      public HeuristicCompletionHolder(final long recordID, final Xid xid, final boolean isCommit)
      {
         this.recordID = recordID;
         this.xid = xid;
         this.isCommit = isCommit;
      }
   }
}

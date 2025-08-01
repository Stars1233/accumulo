/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.test.functional;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.apache.accumulo.core.util.LazySingletons.RANDOM;

import java.util.HashMap;
import java.util.UUID;

import org.apache.accumulo.core.cli.ConfigOpts;
import org.apache.accumulo.core.clientImpl.thrift.ClientService;
import org.apache.accumulo.core.clientImpl.thrift.TInfo;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.conf.SiteConfiguration;
import org.apache.accumulo.core.data.ResourceGroupId;
import org.apache.accumulo.core.fate.zookeeper.ZooReaderWriter;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.core.lock.ServiceLock;
import org.apache.accumulo.core.lock.ServiceLock.LockLossReason;
import org.apache.accumulo.core.lock.ServiceLock.LockWatcher;
import org.apache.accumulo.core.lock.ServiceLockData;
import org.apache.accumulo.core.lock.ServiceLockData.ThriftService;
import org.apache.accumulo.core.manager.thrift.TabletServerStatus;
import org.apache.accumulo.core.metrics.MetricsInfo;
import org.apache.accumulo.core.rpc.clients.ThriftClientTypes;
import org.apache.accumulo.core.securityImpl.thrift.TCredentials;
import org.apache.accumulo.core.tabletscan.thrift.TabletScanClientService;
import org.apache.accumulo.core.tabletserver.thrift.TabletServerClientService;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.client.ClientServiceHandler;
import org.apache.accumulo.server.rpc.ServerAddress;
import org.apache.accumulo.server.rpc.TServerUtils;
import org.apache.accumulo.server.rpc.ThriftProcessorTypes;
import org.apache.accumulo.server.rpc.ThriftServerType;
import org.apache.thrift.TMultiplexedProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.net.HostAndPort;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Tablet server that creates a lock in zookeeper, responds to one status request, and then hangs on
 * subsequent requests. Exits with code zero if halted.
 */
public class ZombieTServer {

  public static class ZombieTServerThriftClientHandler
      extends org.apache.accumulo.test.performance.NullTserver.NullTServerTabletClientHandler
      implements TabletServerClientService.Iface, TabletScanClientService.Iface {

    int statusCount = 0;

    boolean halted = false;

    @Override
    public synchronized void fastHalt(TInfo tinfo, TCredentials credentials, String lock) {
      halted = true;
      notifyAll();
    }

    @Override
    public TabletServerStatus getTabletServerStatus(TInfo tinfo, TCredentials credentials) {
      synchronized (this) {
        if (statusCount++ < 1) {
          TabletServerStatus result = new TabletServerStatus();
          result.tableMap = new HashMap<>();
          return result;
        }
      }
      try {
        Thread.sleep(DAYS.toMillis(Integer.MAX_VALUE));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        log.info("probably received shutdown, interrupted during infinite sleep", ex);
      }
      return null;
    }

    @Override
    public synchronized void halt(TInfo tinfo, TCredentials credentials, String lock) {
      halted = true;
      notifyAll();
    }

  }

  private static final Logger log = LoggerFactory.getLogger(ZombieTServer.class);

  public static void main(String[] args) throws Exception {
    int port = RANDOM.get().nextInt(30000) + 2000;
    var context = new ServerContext(SiteConfiguration.auto());
    final ClientServiceHandler csh = new ClientServiceHandler(context);
    final ZombieTServerThriftClientHandler tch = new ZombieTServerThriftClientHandler();

    TMultiplexedProcessor muxProcessor = new TMultiplexedProcessor();
    muxProcessor.registerProcessor(ThriftClientTypes.CLIENT.getServiceName(),
        ThriftProcessorTypes.CLIENT.getTProcessor(ClientService.Processor.class,
            ClientService.Iface.class, csh, context));
    muxProcessor.registerProcessor(ThriftClientTypes.TABLET_SERVER.getServiceName(),
        ThriftProcessorTypes.TABLET_SERVER.getTProcessor(TabletServerClientService.Processor.class,
            TabletServerClientService.Iface.class, tch, context));
    muxProcessor.registerProcessor(ThriftProcessorTypes.TABLET_SCAN.getServiceName(),
        ThriftProcessorTypes.TABLET_SCAN.getTProcessor(TabletScanClientService.Processor.class,
            TabletScanClientService.Iface.class, tch, context));

    ServerAddress serverPort = TServerUtils.createThriftServer(context.getConfiguration(),
        ThriftServerType.CUSTOM_HS_HA, muxProcessor, "ZombieTServer", 2,
        ThreadPools.DEFAULT_TIMEOUT_MILLISECS, 1000, 10 * 1024 * 1024, null, null, -1,
        context.getConfiguration().getCount(Property.RPC_BACKLOG), context.getMetricsInfo(), false,
        HostAndPort.fromParts(ConfigOpts.BIND_ALL_ADDRESSES, port));
    serverPort.startThriftServer("walking dead");

    String addressString = serverPort.address.toString();

    var zLockPath = context.getServerPaths().createTabletServerPath(ResourceGroupId.DEFAULT,
        serverPort.address);
    ZooReaderWriter zoo = context.getZooSession().asReaderWriter();
    zoo.putPersistentData(zLockPath.toString(), new byte[] {}, NodeExistsPolicy.SKIP);

    ServiceLock zlock = new ServiceLock(context.getZooSession(), zLockPath, UUID.randomUUID());

    MetricsInfo metricsInfo = context.getMetricsInfo();
    metricsInfo.init(MetricsInfo.serviceTags(context.getInstanceName(), "zombie.server",
        serverPort.address, ResourceGroupId.DEFAULT));

    LockWatcher lw = new LockWatcher() {

      @SuppressFBWarnings(value = "DM_EXIT",
          justification = "System.exit() is a bad idea here, but okay for now, since it's a test")
      @Override
      public void lostLock(final LockLossReason reason) {
        try {
          tch.halt(TraceUtil.traceInfo(), null, null);
        } catch (Exception ex) {
          log.error("Exception", ex);
          System.exit(1);
        }
      }

      @SuppressFBWarnings(value = "DM_EXIT",
          justification = "System.exit() is a bad idea here, but okay for now, since it's a test")
      @Override
      public void unableToMonitorLockNode(Exception e) {
        try {
          tch.halt(TraceUtil.traceInfo(), null, null);
        } catch (Exception ex) {
          log.error("Exception", ex);
          System.exit(1);
        }
      }
    };

    if (zlock.tryLock(lw, new ServiceLockData(UUID.randomUUID(), addressString, ThriftService.TSERV,
        ResourceGroupId.DEFAULT))) {
      log.debug("Obtained tablet server lock {}", zlock.getLockPath());
    }
    // modify metadata
    synchronized (tch) {
      while (!tch.halted) {
        tch.wait();
      }
    }
    System.exit(0);
  }
}

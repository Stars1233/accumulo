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
package org.apache.accumulo.server.manager;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.apache.accumulo.core.fate.zookeeper.ZooUtil.NodeMissingPolicy.SKIP;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.clientImpl.thrift.ThriftSecurityException;
import org.apache.accumulo.core.data.ResourceGroupId;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.lock.ServiceLock;
import org.apache.accumulo.core.lock.ServiceLockData;
import org.apache.accumulo.core.lock.ServiceLockPaths;
import org.apache.accumulo.core.lock.ServiceLockPaths.AddressSelector;
import org.apache.accumulo.core.lock.ServiceLockPaths.ResourceGroupPredicate;
import org.apache.accumulo.core.lock.ServiceLockPaths.ServiceLockPath;
import org.apache.accumulo.core.manager.thrift.TabletServerStatus;
import org.apache.accumulo.core.metadata.TServerInstance;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.rpc.clients.ThriftClientTypes;
import org.apache.accumulo.core.tablet.thrift.TUnloadTabletGoal;
import org.apache.accumulo.core.tablet.thrift.TabletManagementClientService;
import org.apache.accumulo.core.tabletserver.thrift.TabletServerClientService;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.core.util.AddressUtil;
import org.apache.accumulo.core.util.Halt;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.core.zookeeper.ZcStat;
import org.apache.accumulo.core.zookeeper.ZooCache.ZooCacheWatcher;
import org.apache.accumulo.server.ServerContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransport;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NotEmptyException;
import org.apache.zookeeper.WatchedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HostAndPort;

public class LiveTServerSet implements ZooCacheWatcher {

  public interface Listener {
    void update(LiveTServerSet current, Set<TServerInstance> deleted, Set<TServerInstance> added);
  }

  private static final Logger log = LoggerFactory.getLogger(LiveTServerSet.class);

  private final AtomicReference<Listener> cback;
  private final ServerContext context;

  public class TServerConnection {
    private final HostAndPort address;

    public TServerConnection(HostAndPort addr) {
      address = addr;
    }

    public HostAndPort getAddress() {
      return address;
    }

    private String lockString(ServiceLock mlock) {
      return mlock.getLockID().serialize();
    }

    private void loadTablet(TabletManagementClientService.Client client, ServiceLock lock,
        KeyExtent extent) throws TException {
      client.loadTablet(TraceUtil.traceInfo(), context.rpcCreds(), lockString(lock),
          extent.toThrift());
    }

    public void assignTablet(ServiceLock lock, KeyExtent extent) throws TException {
      if (extent.isMeta()) {
        // see ACCUMULO-3597
        try (TTransport transport = ThriftUtil.createTransport(address, context)) {
          TabletManagementClientService.Client client =
              ThriftUtil.createClient(ThriftClientTypes.TABLET_MGMT, transport);
          loadTablet(client, lock, extent);
        }
      } else {
        TabletManagementClientService.Client client =
            ThriftUtil.getClient(ThriftClientTypes.TABLET_MGMT, address, context);
        try {
          loadTablet(client, lock, extent);
        } finally {
          ThriftUtil.returnClient(client, context);
        }
      }
    }

    public void unloadTablet(ServiceLock lock, KeyExtent extent, TUnloadTabletGoal goal,
        long requestTime) throws TException {
      TabletManagementClientService.Client client =
          ThriftUtil.getClient(ThriftClientTypes.TABLET_MGMT, address, context);
      try {
        client.unloadTablet(TraceUtil.traceInfo(), context.rpcCreds(), lockString(lock),
            extent.toThrift(), goal, requestTime);
      } finally {
        ThriftUtil.returnClient(client, context);
      }
    }

    public TabletServerStatus getTableMap(boolean usePooledConnection)
        throws TException, ThriftSecurityException {

      if (usePooledConnection) {
        throw new UnsupportedOperationException();
      }

      long start = System.currentTimeMillis();

      try (TTransport transport = ThriftUtil.createTransport(address, context)) {
        TabletServerClientService.Client client =
            ThriftUtil.createClient(ThriftClientTypes.TABLET_SERVER, transport);
        TabletServerStatus status =
            client.getTabletServerStatus(TraceUtil.traceInfo(), context.rpcCreds());
        if (status != null) {
          status.setResponseTime(System.currentTimeMillis() - start);
        }
        return status;
      }
    }

    public void halt(ServiceLock lock) throws TException, ThriftSecurityException {
      TabletServerClientService.Client client =
          ThriftUtil.getClient(ThriftClientTypes.TABLET_SERVER, address, context);
      try {
        client.halt(TraceUtil.traceInfo(), context.rpcCreds(), lockString(lock));
      } finally {
        ThriftUtil.returnClient(client, context);
      }
    }

    public void fastHalt(ServiceLock lock) throws TException {
      TabletServerClientService.Client client =
          ThriftUtil.getClient(ThriftClientTypes.TABLET_SERVER, address, context);
      try {
        client.fastHalt(TraceUtil.traceInfo(), context.rpcCreds(), lockString(lock));
      } finally {
        ThriftUtil.returnClient(client, context);
      }
    }

    public void flush(ServiceLock lock, TableId tableId, byte[] startRow, byte[] endRow)
        throws TException {
      TabletServerClientService.Client client =
          ThriftUtil.getClient(ThriftClientTypes.TABLET_SERVER, address, context);
      try {
        client.flush(TraceUtil.traceInfo(), context.rpcCreds(), lockString(lock),
            tableId.canonical(), startRow == null ? null : ByteBuffer.wrap(startRow),
            endRow == null ? null : ByteBuffer.wrap(endRow));
      } finally {
        ThriftUtil.returnClient(client, context);
      }
    }

  }

  static class TServerInfo {
    final TServerConnection connection;
    final TServerInstance instance;
    final ResourceGroupId resourceGroup;

    TServerInfo(TServerInstance instance, TServerConnection connection,
        ResourceGroupId resourceGroup) {
      this.connection = connection;
      this.instance = instance;
      this.resourceGroup = resourceGroup;
    }
  }

  // The set of active tservers with locks, indexed by their name in zookeeper. When the contents of
  // this map are modified, tServersSnapshot should be set to null.
  private final Map<String,TServerInfo> current = new HashMap<>();

  private LiveTServersSnapshot tServersSnapshot = null;

  // The set of entries in zookeeper without locks, and the first time each was noticed
  private final Map<ServiceLockPath,Long> locklessServers = new HashMap<>();

  public LiveTServerSet(ServerContext context) {
    this.cback = new AtomicReference<>(null);
    this.context = context;
  }

  private Listener getCback() {
    // fail fast if not yet set
    return Objects.requireNonNull(cback.get());
  }

  public synchronized void startListeningForTabletServerChanges(Listener cback) {
    Objects.requireNonNull(cback);
    if (this.cback.compareAndSet(null, cback)) {
      this.context.getZooCache().addZooCacheWatcher(this);
    } else if (this.cback.get() != cback) {
      throw new IllegalStateException("Attempted to set different cback object");
    }
    scanServers();
    ThreadPools.watchCriticalScheduledTask(this.context.getScheduledExecutor()
        .scheduleWithFixedDelay(this::scanServers, 5000, 5000, TimeUnit.MILLISECONDS));
  }

  public synchronized void scanServers() {
    try {
      final Set<TServerInstance> updates = new HashSet<>();
      final Set<TServerInstance> doomed = new HashSet<>();
      final Set<ServiceLockPath> tservers =
          context.getServerPaths().getTabletServer(rg -> true, AddressSelector.all(), false);

      locklessServers.keySet().retainAll(tservers);

      for (ServiceLockPath tserverPath : tservers) {
        checkServer(updates, doomed, tserverPath);
      }

      this.getCback().update(this, doomed, updates);
    } catch (Exception ex) {
      log.error("{}", ex.getMessage(), ex);
    }
  }

  private void deleteServerNode(String serverNode) throws InterruptedException, KeeperException {
    try {
      context.getZooSession().asReaderWriter().delete(serverNode);
    } catch (NotEmptyException ex) {
      // acceptable race condition:
      // tserver created the lock under this server's node after our last check
      // we'll see it at the next check
    }
  }

  private synchronized void checkServer(final Set<TServerInstance> updates,
      final Set<TServerInstance> doomed, final ServiceLockPath tserverPath)
      throws InterruptedException, KeeperException {

    // invalidate the snapshot forcing it to be recomputed the next time its requested
    tServersSnapshot = null;

    final TServerInfo info = current.get(tserverPath.getServer());

    ZcStat stat = new ZcStat();
    Optional<ServiceLockData> sld =
        ServiceLock.getLockData(context.getZooCache(), tserverPath, stat);

    if (sld.isEmpty()) {
      log.trace("lock does not exist for server: {}", tserverPath.getServer());
      if (info != null) {
        doomed.add(info.instance);
        current.remove(tserverPath.getServer());
        log.trace("removed {} from current set and adding to doomed list", tserverPath.getServer());
      }

      Long firstSeen = locklessServers.get(tserverPath);
      if (firstSeen == null) {
        locklessServers.put(tserverPath, System.currentTimeMillis());
        log.trace("first seen, added {} to list of lockless servers", tserverPath.getServer());
      } else if (System.currentTimeMillis() - firstSeen > MINUTES.toMillis(10)) {
        deleteServerNode(tserverPath.toString());
        locklessServers.remove(tserverPath);
        log.trace(
            "deleted zookeeper node for server: {}, has been without lock for over 10 minutes",
            tserverPath.getServer());
      }
    } else {
      log.trace("Lock exists for server: {}, adding to current set", tserverPath.getServer());
      locklessServers.remove(tserverPath);
      HostAndPort address = sld.orElseThrow().getAddress(ServiceLockData.ThriftService.TSERV);
      ResourceGroupId resourceGroup =
          sld.orElseThrow().getGroup(ServiceLockData.ThriftService.TSERV);
      TServerInstance instance = new TServerInstance(address, stat.getEphemeralOwner());

      if (info == null) {
        updates.add(instance);
        TServerInfo tServerInfo =
            new TServerInfo(instance, new TServerConnection(address), resourceGroup);
        current.put(tserverPath.getServer(), tServerInfo);
      } else if (!info.instance.equals(instance)) {
        doomed.add(info.instance);
        updates.add(instance);
        TServerInfo tServerInfo =
            new TServerInfo(instance, new TServerConnection(address), resourceGroup);
        current.put(tserverPath.getServer(), tServerInfo);
      }
    }
  }

  @Override
  public void accept(WatchedEvent event) {

    log.trace("Received event: {}", event);
    // its important that these event are propagated by ZooCache, because this ensures when reading
    // zoocache that is has already processed the event and cleared
    // relevant nodes before code below reads from zoocache
    if (event.getPath() != null && event.getPath().startsWith(Constants.ZTSERVERS)) {
      if (event.getPath().equals(Constants.ZTSERVERS)) {
        scanServers();
      } else if (event.getPath().contains(Constants.ZTSERVERS)) {
        // It's possible that the path contains more than the tserver address, it
        // could contain it's children. We need to fix the path before parsing it
        // path should be: Constants.ZTSERVERS + "/" + resourceGroup + "/" address
        String pathToUse = null;
        String remaining = event.getPath().substring(Constants.ZTSERVERS.length() + 1);
        int numSlashes = StringUtils.countMatches(remaining, '/');
        if (numSlashes == 1) {
          // event path is the server
          pathToUse = event.getPath();
        } else if (numSlashes > 1) {
          // event path is the children of the server, maybe zlock
          int idx = remaining.indexOf("/");
          String rg = remaining.substring(0, idx);
          String server = remaining.substring(idx + 1, remaining.indexOf("/", idx + 1));
          pathToUse = Constants.ZTSERVERS + "/" + rg + "/" + server;
        } else {
          // malformed path
          pathToUse = null;
          log.debug("Received event for path that can't be parsed, path: " + event.getPath());
        }
        if (pathToUse != null) {
          try {
            final ServiceLockPath slp =
                ServiceLockPaths.parse(Optional.of(Constants.ZTSERVERS), pathToUse);
            if (slp.getType().equals(Constants.ZTSERVERS)) {
              try {
                log.trace("Processing event for server: {}", slp.toString());
                final Set<TServerInstance> updates = new HashSet<>();
                final Set<TServerInstance> doomed = new HashSet<>();
                checkServer(updates, doomed, slp);
                this.getCback().update(this, doomed, updates);
              } catch (Exception ex) {
                log.error("Error processing event for tserver: " + slp.toString(), ex);
              }
            }
          } catch (IllegalArgumentException e) {
            log.debug("Received event for path that can't be parsed, path: {} pathToUse:{} ",
                event.getPath(), pathToUse);
          }
        }
      } else {
        // we don't care about other paths
      }
    }

  }

  public synchronized TServerConnection getConnection(TServerInstance server) {
    if (server == null) {
      return null;
    }
    TServerInfo tServerInfo = getSnapshot().tserversInfo.get(server);
    if (tServerInfo == null) {
      return null;
    }
    return tServerInfo.connection;
  }

  public synchronized ResourceGroupId getResourceGroup(TServerInstance server) {
    if (server == null) {
      return null;
    }
    TServerInfo tServerInfo = getSnapshot().tserversInfo.get(server);
    if (tServerInfo == null) {
      return null;
    }
    return tServerInfo.resourceGroup;
  }

  public static class LiveTServersSnapshot {
    private final Set<TServerInstance> tservers;
    private final Map<ResourceGroupId,Set<TServerInstance>> tserverGroups;

    // TServerInfo is only for internal use, so this field is private w/o a getter.
    private final Map<TServerInstance,TServerInfo> tserversInfo;

    @VisibleForTesting
    public LiveTServersSnapshot(Set<TServerInstance> currentServers,
        Map<ResourceGroupId,Set<TServerInstance>> serverGroups) {
      this.tserversInfo = null;
      this.tservers = Set.copyOf(currentServers);
      Map<ResourceGroupId,Set<TServerInstance>> copy = new HashMap<>();
      serverGroups.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
      this.tserverGroups = Collections.unmodifiableMap(copy);
    }

    public LiveTServersSnapshot(Map<TServerInstance,TServerInfo> currentServers,
        Map<ResourceGroupId,Set<TServerInstance>> serverGroups) {
      this.tserversInfo = Map.copyOf(currentServers);
      this.tservers = this.tserversInfo.keySet();
      Map<ResourceGroupId,Set<TServerInstance>> copy = new HashMap<>();
      serverGroups.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
      this.tserverGroups = Collections.unmodifiableMap(copy);
    }

    public Set<TServerInstance> getTservers() {
      return tservers;
    }

    public Map<ResourceGroupId,Set<TServerInstance>> getTserverGroups() {
      return tserverGroups;
    }
  }

  public synchronized LiveTServersSnapshot getSnapshot() {
    if (tServersSnapshot == null) {
      HashMap<TServerInstance,TServerInfo> tServerInstances = new HashMap<>();
      Map<ResourceGroupId,Set<TServerInstance>> tserversGroups = new HashMap<>();
      current.values().forEach(tServerInfo -> {
        tServerInstances.put(tServerInfo.instance, tServerInfo);
        tserversGroups.computeIfAbsent(tServerInfo.resourceGroup, rg -> new HashSet<>())
            .add(tServerInfo.instance);
      });
      tServersSnapshot = new LiveTServersSnapshot(tServerInstances, tserversGroups);
    }
    return tServersSnapshot;
  }

  public synchronized Set<TServerInstance> getCurrentServers() {
    Set<TServerInstance> current = new HashSet<>(getSnapshot().getTservers());
    return current;
  }

  public synchronized int size() {
    return current.size();
  }

  public synchronized TServerInstance find(String tabletServer) {
    return find(current, tabletServer);
  }

  static TServerInstance find(Map<String,TServerInfo> servers, String tabletServer) {
    HostAndPort addr;
    String sessionId = null;
    if (tabletServer.charAt(tabletServer.length() - 1) == ']') {
      int index = tabletServer.indexOf('[');
      if (index == -1) {
        throw new IllegalArgumentException("Could not parse tabletserver '" + tabletServer + "'");
      }
      addr = AddressUtil.parseAddress(tabletServer.substring(0, index));
      // Strip off the last bracket
      sessionId = tabletServer.substring(index + 1, tabletServer.length() - 1);
    } else {
      addr = AddressUtil.parseAddress(tabletServer);
    }
    for (Entry<String,TServerInfo> entry : servers.entrySet()) {
      if (entry.getValue().instance.getHostAndPort().equals(addr)) {
        // Return the instance if we have no desired session ID, or we match the desired session ID
        if (sessionId == null || sessionId.equals(entry.getValue().instance.getSession())) {
          return entry.getValue().instance;
        }
      }
    }
    return null;
  }

  public synchronized void remove(TServerInstance server) {

    // invalidate the snapshot forcing it to be recomputed the next time its requested
    tServersSnapshot = null;

    Optional<ResourceGroupId> resourceGroup = Optional.empty();
    Optional<HostAndPort> address = Optional.empty();
    for (Entry<String,TServerInfo> entry : current.entrySet()) {
      if (entry.getValue().instance.equals(server)) {
        address = Optional.of(HostAndPort.fromString(entry.getKey()));
        resourceGroup = Optional.of(entry.getValue().resourceGroup);
        break;
      }
    }
    if (resourceGroup.isEmpty() || address.isEmpty()) {
      return;
    }
    current.remove(address.orElseThrow().toString());

    ResourceGroupPredicate rgPredicate = resourceGroup.map(rg -> {
      ResourceGroupPredicate rgp = rg2 -> rg.equals(rg2);
      return rgp;
    }).orElse(rg -> true);
    AddressSelector addrPredicate =
        address.map(AddressSelector::exact).orElse(AddressSelector.all());
    Set<ServiceLockPath> paths =
        context.getServerPaths().getTabletServer(rgPredicate, addrPredicate, false);
    if (paths.isEmpty() || paths.size() > 1) {
      log.error("Zero or many zookeeper entries match input arguments.");
    } else {
      ServiceLockPath slp = paths.iterator().next();
      log.info("Removing zookeeper lock for {}", slp);
      try {
        context.getZooSession().asReaderWriter().recursiveDelete(slp.toString(), SKIP);
      } catch (Exception e) {
        Halt.halt(-1, "error removing tablet server lock", e);
      }
      context.getZooCache().clear(slp.toString());
    }
  }
}

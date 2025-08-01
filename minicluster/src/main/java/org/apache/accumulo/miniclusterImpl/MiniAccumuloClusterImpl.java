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
package org.apache.accumulo.miniclusterImpl;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.toList;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.accumulo.cluster.AccumuloCluster;
import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.clientImpl.ClientContext;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.ClientProperty;
import org.apache.accumulo.core.conf.ConfigurationCopy;
import org.apache.accumulo.core.conf.ConfigurationTypeHelper;
import org.apache.accumulo.core.conf.DefaultConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.conf.SiteConfiguration;
import org.apache.accumulo.core.data.InstanceId;
import org.apache.accumulo.core.data.ResourceGroupId;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil;
import org.apache.accumulo.core.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.core.lock.ServiceLock;
import org.apache.accumulo.core.lock.ServiceLock.AccumuloLockWatcher;
import org.apache.accumulo.core.lock.ServiceLock.LockLossReason;
import org.apache.accumulo.core.lock.ServiceLockData;
import org.apache.accumulo.core.lock.ServiceLockData.ThriftService;
import org.apache.accumulo.core.lock.ServiceLockPaths.AddressSelector;
import org.apache.accumulo.core.lock.ServiceLockPaths.ServiceLockPath;
import org.apache.accumulo.core.manager.thrift.ManagerGoalState;
import org.apache.accumulo.core.manager.thrift.ManagerMonitorInfo;
import org.apache.accumulo.core.rpc.clients.ThriftClientTypes;
import org.apache.accumulo.core.spi.common.ServiceEnvironment;
import org.apache.accumulo.core.spi.compaction.CompactionPlanner;
import org.apache.accumulo.core.spi.compaction.CompactionServiceId;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.core.util.ConfigurationImpl;
import org.apache.accumulo.core.util.Pair;
import org.apache.accumulo.core.util.compaction.CompactionPlannerInitParams;
import org.apache.accumulo.core.util.compaction.CompactionServicesConfig;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.core.zookeeper.ZooSession;
import org.apache.accumulo.manager.state.SetGoalState;
import org.apache.accumulo.minicluster.MiniAccumuloCluster;
import org.apache.accumulo.minicluster.ServerType;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.init.Initialize;
import org.apache.accumulo.server.util.AccumuloStatus;
import org.apache.accumulo.server.util.PortUtils;
import org.apache.accumulo.server.util.ZooZap;
import org.apache.accumulo.start.Main;
import org.apache.accumulo.start.spi.KeywordExecutable;
import org.apache.accumulo.start.util.MiniDFSUtil;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * This class provides the backing implementation for {@link MiniAccumuloCluster}, and may contain
 * features for internal testing which have not yet been promoted to the public API. It's best to
 * use {@link MiniAccumuloCluster} whenever possible. Use of this class risks API breakage between
 * versions.
 *
 * @since 1.6.0
 */
public class MiniAccumuloClusterImpl implements AccumuloCluster {
  private static final Logger log = LoggerFactory.getLogger(MiniAccumuloClusterImpl.class);

  private final Set<Pair<ServerType,Integer>> debugPorts = new HashSet<>();
  private final java.nio.file.Path zooCfgFile;
  private final String dfsUri;
  private final MiniAccumuloConfigImpl config;
  private final Supplier<Properties> clientProperties;
  private final SiteConfiguration siteConfig;
  private final AtomicReference<MiniDFSCluster> miniDFS = new AtomicReference<>();
  private final List<Process> cleanup = new ArrayList<>();
  private final MiniAccumuloClusterControl clusterControl;
  private final Supplier<ServerContext> context;
  private final AtomicBoolean serverContextCreated = new AtomicBoolean(false);

  private boolean initialized = false;
  private volatile ExecutorService executor;
  private ServiceLock miniLock;
  private ZooSession miniLockZk;
  private AccumuloClient client;
  private volatile State clusterState = State.STOPPED;

  /**
   *
   * @param dir An empty or nonexistent temp directory that Accumulo and Zookeeper can store data
   *        in. Creating the directory is left to the user. Java 7, Guava, and Junit provide methods
   *        for creating temporary directories.
   * @param rootPassword Initial root password for instance.
   */
  public MiniAccumuloClusterImpl(File dir, String rootPassword) throws IOException {
    this(new MiniAccumuloConfigImpl(dir, rootPassword));
  }

  /**
   * @param config initial configuration
   */
  public MiniAccumuloClusterImpl(MiniAccumuloConfigImpl config) throws IOException {

    // Set the TabletGroupWatcher interval to 5s for all MAC instances unless set by
    // the test.
    if (!config.getSiteConfig()
        .containsKey(Property.MANAGER_TABLET_GROUP_WATCHER_INTERVAL.getKey())) {
      config.setProperty(Property.MANAGER_TABLET_GROUP_WATCHER_INTERVAL, "5s");
    }

    this.config = config.initialize();
    this.clientProperties = Suppliers.memoize(
        () -> Accumulo.newClientProperties().from(config.getClientPropsFile().toPath()).build());

    if (Boolean.valueOf(config.getSiteConfig().get(Property.TSERV_NATIVEMAP_ENABLED.getKey()))
        && config.getNativeLibPaths().length == 0
        && !config.getSystemProperties().containsKey("accumulo.native.lib.path")) {
      throw new IllegalStateException(
          "MAC configured to use native maps, but native library path was not provided.");
    }

    mkdirs(config.getConfDir().toPath());
    mkdirs(config.getLogDir().toPath());
    mkdirs(config.getLibDir().toPath());
    mkdirs(config.getLibExtDir().toPath());

    if (!config.useExistingInstance()) {
      if (!config.useExistingZooKeepers()) {
        mkdirs(config.getZooKeeperDir().toPath());
      }
      mkdirs(config.getAccumuloDir().toPath());
    }

    java.nio.file.Path confDir = config.getConfDir().toPath();
    if (config.getUseMiniDFS()) {
      java.nio.file.Path configPath = config.getAccumuloDir().toPath();
      java.nio.file.Path nn = configPath.resolve("nn");
      mkdirs(nn);
      java.nio.file.Path dn = configPath.resolve("dn");
      mkdirs(dn);
      java.nio.file.Path dfs = configPath.resolve("dfs");
      mkdirs(dfs);
      Configuration conf = new Configuration();
      conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY, nn.toAbsolutePath().toString());
      conf.set(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, dn.toAbsolutePath().toString());
      conf.set(DFSConfigKeys.DFS_REPLICATION_KEY, "1");
      conf.set(DFSConfigKeys.DFS_NAMENODE_REPLICATION_MIN_KEY, "1");
      conf.set("dfs.support.append", "true");
      conf.set("dfs.datanode.synconclose", "true");
      conf.set("dfs.datanode.data.dir.perm", MiniDFSUtil.computeDatanodeDirectoryPermission());
      config.getHadoopConfOverrides().forEach((k, v) -> conf.set(k, v));
      String oldTestBuildData =
          System.setProperty("test.build.data", dfs.toAbsolutePath().toString());
      miniDFS.set(new MiniDFSCluster.Builder(conf).numDataNodes(config.getNumDataNodes()).build());
      if (oldTestBuildData == null) {
        System.clearProperty("test.build.data");
      } else {
        System.setProperty("test.build.data", oldTestBuildData);
      }
      miniDFS.get().waitClusterUp();
      InetSocketAddress dfsAddress = miniDFS.get().getNameNode().getNameNodeAddress();
      dfsUri = "hdfs://" + dfsAddress.getHostName() + ":" + dfsAddress.getPort();
      java.nio.file.Path coreFile = confDir.resolve("core-site.xml");
      writeConfig(coreFile, Collections.singletonMap("fs.default.name", dfsUri).entrySet());
      java.nio.file.Path hdfsFile = confDir.resolve("hdfs-site.xml");
      writeConfig(hdfsFile, conf);

      Map<String,String> siteConfig = config.getSiteConfig();
      siteConfig.put(Property.INSTANCE_VOLUMES.getKey(), dfsUri + "/accumulo");
      config.setSiteConfig(siteConfig);
    } else if (config.useExistingInstance()) {
      dfsUri = config.getHadoopConfiguration().get(CommonConfigurationKeys.FS_DEFAULT_NAME_KEY);
    } else {
      dfsUri = "file:///";
    }

    // Perform any modifications to the site config that need to happen
    // after the instance volumes are set, and before the config is
    // written out and MAC started.
    config.preStartConfigUpdate();

    Map<String,String> clientProps = config.getClientProps();
    clientProps.put(ClientProperty.INSTANCE_ZOOKEEPERS.getKey(), config.getZooKeepers());
    clientProps.put(ClientProperty.INSTANCE_NAME.getKey(), config.getInstanceName());
    if (!clientProps.containsKey(ClientProperty.AUTH_TYPE.getKey())) {
      clientProps.put(ClientProperty.AUTH_TYPE.getKey(), "password");
      clientProps.put(ClientProperty.AUTH_PRINCIPAL.getKey(), config.getRootUserName());
      clientProps.put(ClientProperty.AUTH_TOKEN.getKey(), config.getRootPassword());
    }

    java.nio.file.Path clientPropsFile = config.getClientPropsFile().toPath();
    writeConfigProperties(clientPropsFile, clientProps);

    java.nio.file.Path siteFile = confDir.resolve("accumulo.properties");
    writeConfigProperties(siteFile, config.getSiteConfig());
    this.siteConfig = SiteConfiguration.fromFile(siteFile.toFile()).build();
    this.context = Suppliers.memoize(() -> new ServerContext(siteConfig) {

      @Override
      public ServiceLock getServiceLock() {
        // Override getServiceLock because any call to setServiceLock
        // will set the SingletonManager.MODE to SERVER and we may not
        // want that side-effect.
        return miniLock;
      }

    });

    if (!config.useExistingInstance() && !config.useExistingZooKeepers()) {
      zooCfgFile = confDir.resolve("zoo.cfg");
      BufferedWriter fileWriter = Files.newBufferedWriter(zooCfgFile);

      // zookeeper uses Properties to read its config, so use that to write in order to properly
      // escape things like Windows paths
      Properties zooCfg = new Properties();
      zooCfg.setProperty("tickTime", "2000");
      zooCfg.setProperty("initLimit", "10");
      zooCfg.setProperty("syncLimit", "5");
      zooCfg.setProperty("clientPortAddress", MiniAccumuloConfigImpl.DEFAULT_ZOOKEEPER_HOST);
      zooCfg.setProperty("clientPort", config.getZooKeeperPort() + "");
      zooCfg.setProperty("maxClientCnxns", "1000");
      zooCfg.setProperty("dataDir", config.getZooKeeperDir().getAbsolutePath());
      zooCfg.setProperty("4lw.commands.whitelist", "ruok,wchs");
      zooCfg.setProperty("admin.enableServer", "false");
      zooCfg.store(fileWriter, null);

      fileWriter.close();
    } else {
      zooCfgFile = null;
    }
    clusterControl = new MiniAccumuloClusterControl(this);
  }

  File getZooCfgFile() {
    return zooCfgFile.toFile();
  }

  public ProcessInfo exec(Class<?> clazz, String... args) throws IOException {
    return exec(clazz, null, args);
  }

  public ProcessInfo exec(Class<?> clazz, List<String> jvmArgs, String... args) throws IOException {
    ArrayList<String> jvmArgs2 = new ArrayList<>(1 + (jvmArgs == null ? 0 : jvmArgs.size()));
    jvmArgs2.add("-Xmx" + config.getDefaultMemory());
    if (jvmArgs != null) {
      jvmArgs2.addAll(jvmArgs);
    }
    return _exec(clazz, jvmArgs2, args);
  }

  private String getClasspath() {
    StringBuilder classpathBuilder = new StringBuilder();
    classpathBuilder.append(config.getConfDir().getAbsolutePath());

    if (config.getHadoopConfDir() != null) {
      classpathBuilder.append(File.pathSeparator)
          .append(config.getHadoopConfDir().getAbsolutePath());
    }

    if (config.getClasspathItems() == null) {
      String javaClassPath = System.getProperty("java.class.path");
      if (javaClassPath == null) {
        throw new IllegalStateException("java.class.path is not set");
      }
      classpathBuilder.append(File.pathSeparator).append(javaClassPath);
    } else {
      for (String s : config.getClasspathItems()) {
        classpathBuilder.append(File.pathSeparator).append(s);
      }
    }

    return classpathBuilder.toString();
  }

  public static class ProcessInfo {

    private final Process process;
    private final File stdOut;

    public ProcessInfo(Process process, File stdOut) {
      this.process = process;
      this.stdOut = stdOut;
    }

    public Process getProcess() {
      return process;
    }

    public String readStdOut() {
      try (InputStream in = Files.newInputStream(stdOut.toPath())) {
        return IOUtils.toString(in, UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  @SuppressFBWarnings(value = {"COMMAND_INJECTION", "PATH_TRAVERSAL_IN"},
      justification = "mini runs in the same security context as user providing the args")
  private ProcessInfo _exec(Class<?> clazz, List<String> extraJvmOpts, String... args)
      throws IOException {
    String javaHome = System.getProperty("java.home");
    String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

    var basicArgs = Stream.of(javaBin, "-Dproc=" + clazz.getSimpleName());
    var jvmArgs = extraJvmOpts.stream();
    var propsArgs = config.getSystemProperties().entrySet().stream()
        .map(e -> String.format("-D%s=%s", e.getKey(), e.getValue()));

    // @formatter:off
    var hardcodedArgs = Stream.of(
        "-Dapple.awt.UIElement=true",
        "-Djava.net.preferIPv4Stack=true",
        "-XX:+PerfDisableSharedMem",
        "-XX:+AlwaysPreTouch",
        Main.class.getName(), clazz.getName());
    // @formatter:on

    // concatenate all the args sources into a single list of args
    var argList = Stream.of(basicArgs, jvmArgs, propsArgs, hardcodedArgs, Stream.of(args))
        .flatMap(Function.identity()).collect(toList());
    ProcessBuilder builder = new ProcessBuilder(argList);

    final String classpath = getClasspath();
    builder.environment().put("CLASSPATH", classpath);
    builder.environment().put("ACCUMULO_HOME", config.getDir().getAbsolutePath());
    builder.environment().put("ACCUMULO_LOG_DIR", config.getLogDir().getAbsolutePath());
    String ldLibraryPath = Joiner.on(File.pathSeparator).join(config.getNativeLibPaths());
    builder.environment().put("LD_LIBRARY_PATH", ldLibraryPath);
    builder.environment().put("DYLD_LIBRARY_PATH", ldLibraryPath);

    // if we're running under accumulo.start, we forward these env vars
    String env = System.getenv("HADOOP_HOME");
    if (env != null) {
      builder.environment().put("HADOOP_HOME", env);
    }
    env = System.getenv("ZOOKEEPER_HOME");
    if (env != null) {
      builder.environment().put("ZOOKEEPER_HOME", env);
    }
    builder.environment().put("ACCUMULO_CONF_DIR", config.getConfDir().getAbsolutePath());
    if (config.getHadoopConfDir() != null) {
      builder.environment().put("HADOOP_CONF_DIR", config.getHadoopConfDir().getAbsolutePath());
    }

    log.debug("Starting MiniAccumuloCluster process with class: " + clazz.getSimpleName()
        + "\n, args: " + argList + "\n, environment: " + builder.environment());

    int hashcode = builder.hashCode();

    java.nio.file.Path logDir = config.getLogDir().toPath();
    File stdOut = logDir.resolve(clazz.getSimpleName() + "_" + hashcode + ".out").toFile();
    File stdErr = logDir.resolve(clazz.getSimpleName() + "_" + hashcode + ".err").toFile();

    Process process = builder.redirectError(stdErr).redirectOutput(stdOut).start();

    cleanup.add(process);

    return new ProcessInfo(process, stdOut);
  }

  public ProcessInfo _exec(KeywordExecutable server, ServerType serverType,
      Map<String,String> configOverrides, String... args) throws IOException {
    String[] modifiedArgs;
    if (args == null || args.length == 0) {
      modifiedArgs = new String[] {server.keyword()};
    } else {
      modifiedArgs =
          Stream.concat(Stream.of(server.keyword()), Stream.of(args)).toArray(String[]::new);
    }
    return _exec(Main.class, serverType, configOverrides, modifiedArgs);
  }

  public ProcessInfo _exec(Class<?> clazz, ServerType serverType,
      Map<String,String> configOverrides, String... args) throws IOException {
    List<String> jvmOpts = new ArrayList<>();
    if (serverType == ServerType.ZOOKEEPER) {
      // disable zookeeper's log4j 1.2 jmx support, which requires old versions of log4j 1.2
      // and won't work with reload4j or log4j2
      jvmOpts.add("-Dzookeeper.jmx.log4j.disable=true");
    }
    jvmOpts.add("-Xmx" + config.getMemory(serverType));
    if (configOverrides != null && !configOverrides.isEmpty()) {
      File siteFile =
          Files.createTempFile(config.getConfDir().toPath(), "accumulo", ".properties").toFile();
      Map<String,String> confMap = new HashMap<>(config.getSiteConfig());
      confMap.putAll(configOverrides);
      writeConfigProperties(siteFile.toPath(), confMap);
      jvmOpts.add("-Daccumulo.properties=" + siteFile.getName());
    }

    if (config.isJDWPEnabled()) {
      int port = PortUtils.getRandomFreePort();
      jvmOpts.addAll(buildRemoteDebugParams(port));
      debugPorts.add(new Pair<>(serverType, port));
    }
    return _exec(clazz, jvmOpts, args);
  }

  private static void mkdirs(java.nio.file.Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      log.warn("Unable to create {}", dir);
    }
    if (!Files.isDirectory(dir)) {
      log.warn("Unable to create {}", dir);
    }
  }

  private void writeConfig(java.nio.file.Path file, Iterable<Map.Entry<String,String>> settings)
      throws IOException {
    BufferedWriter fileWriter = Files.newBufferedWriter(file);
    fileWriter.append("<configuration>\n");

    for (Entry<String,String> entry : settings) {
      String value =
          entry.getValue().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
      fileWriter.append(
          "<property><name>" + entry.getKey() + "</name><value>" + value + "</value></property>\n");
    }
    fileWriter.append("</configuration>\n");
    fileWriter.close();
  }

  private void writeConfigProperties(java.nio.file.Path file, Map<String,String> settings)
      throws IOException {
    BufferedWriter fileWriter = Files.newBufferedWriter(file);

    for (Entry<String,String> entry : settings.entrySet()) {
      fileWriter.append(entry.getKey() + "=" + entry.getValue() + "\n");
    }
    fileWriter.close();
  }

  /**
   * Starts Accumulo and Zookeeper processes. Can only be called once.
   */
  @SuppressFBWarnings(value = "UNENCRYPTED_SOCKET",
      justification = "insecure socket used for reservation")
  @Override
  public synchronized void start() throws IOException, InterruptedException {
    Preconditions.checkState(clusterState != State.TERMINATED,
        "Cannot start a cluster that is terminated.");

    if (config.getUseMiniDFS() && miniDFS.get() == null) {
      throw new IllegalStateException("Cannot restart mini when using miniDFS");
    }

    MiniAccumuloClusterControl control = getClusterControl();

    if (config.useExistingInstance()) {
      String instanceName = getServerContext().getInstanceName();
      if (instanceName == null || instanceName.isBlank()) {
        throw new IllegalStateException("Unable to read instance name from zookeeper.");
      }

      config.setInstanceName(instanceName);
      if (!AccumuloStatus.isAccumuloOffline(getServerContext())) {
        throw new IllegalStateException(
            "The Accumulo instance being used is already running. Aborting.");
      }
    } else {
      if (!initialized) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          try {
            if (clusterState != State.TERMINATED) {
              MiniAccumuloClusterImpl.this.stop();
              MiniAccumuloClusterImpl.this.terminate();
            }
          } catch (InterruptedException e) {
            log.error("The stopping of MiniAccumuloCluster was interrupted.", e);
          } catch (Exception e) {
            log.error("Exception while attempting to stop the MiniAccumuloCluster.", e);
          }
        }));
      }

      if (!config.useExistingZooKeepers()) {
        log.warn("Starting ZooKeeper");
        control.start(ServerType.ZOOKEEPER);
      }

      if (!initialized) {
        if (!config.useExistingZooKeepers()) {
          // sleep a little bit to let zookeeper come up before calling init, seems to work better
          long startTime = System.currentTimeMillis();
          while (true) {
            try (Socket s = new Socket(MiniAccumuloConfigImpl.DEFAULT_ZOOKEEPER_HOST,
                config.getZooKeeperPort())) {
              s.setReuseAddress(true);
              s.getOutputStream().write("ruok\n".getBytes(UTF_8));
              s.getOutputStream().flush();
              byte[] buffer = new byte[100];
              int n = s.getInputStream().read(buffer);
              if (n >= 4 && new String(buffer, 0, 4, UTF_8).equals("imok")) {
                break;
              }
            } catch (IOException | RuntimeException e) {
              if (System.currentTimeMillis() - startTime >= config.getZooKeeperStartupTime()) {
                throw new ZooKeeperBindException("Zookeeper did not start within "
                    + (config.getZooKeeperStartupTime() / 1000) + " seconds. Check the logs in "
                    + config.getLogDir() + " for errors.  Last exception: " + e);
              }
              // Don't spin absurdly fast
              sleepUninterruptibly(250, TimeUnit.MILLISECONDS);
            }
          }
        }

        LinkedList<String> args = new LinkedList<>();
        args.add("--instance-name");
        args.add(config.getInstanceName());
        args.add("--user");
        args.add(config.getRootUserName());
        args.add("--clear-instance-name");

        // If we aren't using SASL, add in the root password
        final String saslEnabled =
            config.getSiteConfig().get(Property.INSTANCE_RPC_SASL_ENABLED.getKey());
        if (saslEnabled == null || !Boolean.parseBoolean(saslEnabled)) {
          args.add("--password");
          args.add(config.getRootPassword());
        }

        log.warn("Initializing ZooKeeper");
        Process initProcess = exec(Initialize.class, args.toArray(new String[0])).getProcess();
        int ret = initProcess.waitFor();
        if (ret != 0) {
          throw new IllegalStateException("Initialize process returned " + ret
              + ". Check the logs in " + config.getLogDir() + " for errors.");
        }
        initialized = true;
      } else {
        log.warn("Not initializing ZooKeeper, already initialized");
      }
    }

    log.info("Starting MAC against instance {} and zookeeper(s) {}.", config.getInstanceName(),
        config.getZooKeepers());

    control.start(ServerType.TABLET_SERVER);
    control.start(ServerType.SCAN_SERVER);

    int ret = 0;
    for (int i = 0; i < 5; i++) {
      ret = exec(Main.class, SetGoalState.class.getName(), ManagerGoalState.NORMAL.toString())
          .getProcess().waitFor();
      if (ret == 0) {
        break;
      }
      sleepUninterruptibly(1, TimeUnit.SECONDS);
    }
    if (ret != 0) {
      throw new IllegalStateException("Could not set manager goal state, process returned " + ret
          + ". Check the logs in " + config.getLogDir() + " for errors.");
    }

    control.start(ServerType.MANAGER);
    control.start(ServerType.GARBAGE_COLLECTOR);

    if (executor == null) {
      executor = ThreadPools.getServerThreadPools().getPoolBuilder(getClass().getSimpleName())
          .numCoreThreads(1).numMaxThreads(16).withTimeOut(1, TimeUnit.SECONDS)
          .enableThreadPoolMetrics(false).build();
    }

    Set<String> groups;
    try {
      groups = getCompactionGroupNames();
      if (groups.isEmpty()) {
        throw new IllegalStateException("No Compactor groups configured.");
      }
      for (String name : groups) {
        // Allow user override
        if (!config.getClusterServerConfiguration().getCompactorConfiguration().containsKey(name)) {
          config.getClusterServerConfiguration().addCompactorResourceGroup(name, 1);
        }
      }
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Unable to find declared CompactionPlanner class", e);
    }
    control.start(ServerType.COMPACTOR);

    final AtomicBoolean lockAcquired = new AtomicBoolean(false);
    final CountDownLatch lockWatcherInvoked = new CountDownLatch(1);
    AccumuloLockWatcher miniLockWatcher = new AccumuloLockWatcher() {

      @Override
      public void lostLock(LockLossReason reason) {
        log.warn("Lost lock: " + reason.toString());
        miniLock = null;
      }

      @Override
      public void unableToMonitorLockNode(Exception e) {
        log.warn("Unable to monitor lock: " + e.getMessage());
        miniLock = null;
      }

      @Override
      public void acquiredLock() {
        log.debug("Acquired ZK lock for MiniAccumuloClusterImpl");
        lockAcquired.set(true);
        lockWatcherInvoked.countDown();
      }

      @Override
      public void failedToAcquireLock(Exception e) {
        log.warn("Failed to acquire ZK lock for MiniAccumuloClusterImpl, msg: " + e.getMessage());
        lockWatcherInvoked.countDown();
        miniLock = null;
      }
    };

    InstanceId iid = null;
    // It's possible start was called twice...
    if (client == null) {
      client = Accumulo.newClient().from(getClientProperties()).build();
    }
    iid = client.instanceOperations().getInstanceId();
    // The code below does not use `getServerContext()` as that will
    // set the SingletonManager.mode to SERVER which will cause some
    // tests to fail
    final Map<String,String> properties = config.getSiteConfig();
    final int timeout = (int) ConfigurationTypeHelper.getTimeInMillis(properties.getOrDefault(
        Property.INSTANCE_ZK_TIMEOUT.getKey(), Property.INSTANCE_ZK_TIMEOUT.getDefaultValue()));
    final String secret = properties.get(Property.INSTANCE_SECRET.getKey());
    miniLockZk = new ZooSession(MiniAccumuloClusterImpl.class.getSimpleName() + ".lock",
        config.getZooKeepers() + ZooUtil.getRoot(iid), timeout, secret);

    // It's possible start was called twice...
    if (miniLock == null) {
      UUID miniUUID = UUID.randomUUID();
      // Don't call getServerContext here as it will set the SingletonManager.mode to SERVER
      // We don't want that.
      ServiceLockPath slp =
          ((ClientContext) client).getServerPaths().createMiniPath(miniUUID.toString());
      String miniZInstancePath = slp.toString();
      String miniZDirPath =
          miniZInstancePath.substring(0, miniZInstancePath.indexOf("/" + miniUUID.toString()));
      try {
        var zrw = miniLockZk.asReaderWriter();
        zrw.putPersistentData(miniZDirPath, new byte[0], NodeExistsPolicy.SKIP);
        zrw.putPersistentData(miniZInstancePath, new byte[0], NodeExistsPolicy.SKIP);
      } catch (KeeperException | InterruptedException e) {
        throw new IllegalStateException("Error creating path in ZooKeeper", e);
      }
      ServiceLockData sld =
          new ServiceLockData(miniUUID, "localhost", ThriftService.NONE, ResourceGroupId.DEFAULT);
      miniLock = new ServiceLock(miniLockZk, slp, miniUUID);
      miniLock.lock(miniLockWatcher, sld);

      lockWatcherInvoked.await();

      if (!lockAcquired.get()) {
        throw new IllegalStateException("Error creating MAC entry in ZooKeeper");
      }
    }

    verifyUp((ClientContext) client, iid);

    printProcessSummary();
    clusterState = State.STARTED;

  }

  private void printProcessSummary() {
    log.info("Process Summary:");
    getProcesses().forEach((k, v) -> log.info("{}: {}", k,
        v.stream().map((pr) -> pr.getProcess().pid()).collect(Collectors.toList())));
  }

  private Set<String> getCompactionGroupNames() throws ClassNotFoundException {

    Set<String> groupNames = new HashSet<>();
    AccumuloConfiguration aconf = new ConfigurationCopy(config.getSiteConfig());
    CompactionServicesConfig csc = new CompactionServicesConfig(aconf);

    ServiceEnvironment senv = new ServiceEnvironment() {

      @Override
      public String getTableName(TableId tableId) throws TableNotFoundException {
        return null;
      }

      @Override
      public <T> T instantiate(String className, Class<T> base)
          throws ReflectiveOperationException {
        return ConfigurationTypeHelper.getClassInstance(null, className, base);
      }

      @Override
      public <T> T instantiate(TableId tableId, String className, Class<T> base)
          throws ReflectiveOperationException {
        return null;
      }

      @Override
      public Configuration getConfiguration() {
        return new ConfigurationImpl(aconf);
      }

      @Override
      public Configuration getConfiguration(TableId tableId) {
        return null;
      }

    };

    for (var entry : csc.getPlanners().entrySet()) {
      String serviceId = entry.getKey();
      String plannerClass = entry.getValue();

      try {
        CompactionPlanner cp = senv.instantiate(plannerClass, CompactionPlanner.class);
        var initParams = new CompactionPlannerInitParams(CompactionServiceId.of(serviceId),
            csc.getPlannerPrefix(serviceId), csc.getOptions().get(serviceId), senv);
        cp.init(initParams);
        initParams.getRequestedGroups().forEach(gid -> groupNames.add(gid.canonical()));
      } catch (Exception e) {
        log.error("For compaction service {}, failed to get compactor groups from planner {}.",
            serviceId, plannerClass, e);
      }
    }
    return groupNames;
  }

  // wait up to 10 seconds for the process to start
  private static void waitForProcessStart(Process p, String name) throws InterruptedException {
    long start = System.nanoTime();
    while (p.info().startInstant().isEmpty()) {
      if (NANOSECONDS.toSeconds(System.nanoTime() - start) > 10) {
        throw new IllegalStateException(
            "Error starting " + name + " - instance not started within 10 seconds");
      }
      Thread.sleep(50);
    }
  }

  private void verifyUp(ClientContext context, InstanceId instanceId)
      throws InterruptedException, IOException {

    requireNonNull(getClusterControl().managerProcess, "Error starting Manager - no process");
    waitForProcessStart(getClusterControl().managerProcess, "Manager");

    requireNonNull(getClusterControl().gcProcess, "Error starting GC - no process");
    waitForProcessStart(getClusterControl().gcProcess, "GC");

    int tsExpectedCount = 0;
    for (List<Process> tabletServerProcesses : getClusterControl().tabletServerProcesses.values()) {
      for (Process tsp : tabletServerProcesses) {
        tsExpectedCount++;
        requireNonNull(tsp, "Error starting TabletServer " + tsExpectedCount + " - no process");
        waitForProcessStart(tsp, "TabletServer" + tsExpectedCount);
      }
    }

    int ssExpectedCount = 0;
    for (List<Process> scanServerProcesses : getClusterControl().scanServerProcesses.values()) {
      for (Process tsp : scanServerProcesses) {
        ssExpectedCount++;
        requireNonNull(tsp, "Error starting ScanServer " + ssExpectedCount + " - no process");
        waitForProcessStart(tsp, "ScanServer" + ssExpectedCount);
      }
    }

    int ecExpectedCount = 0;
    for (List<Process> compactorProcesses : getClusterControl().compactorProcesses.values()) {
      for (Process ecp : compactorProcesses) {
        ecExpectedCount++;
        requireNonNull(ecp, "Error starting compactor " + ecExpectedCount + " - no process");
        waitForProcessStart(ecp, "Compactor" + ecExpectedCount);
      }
    }

    int tsActualCount = 0;
    while (tsActualCount < tsExpectedCount) {
      Set<ServiceLockPath> tservers =
          context.getServerPaths().getTabletServer(rg -> true, AddressSelector.all(), true);
      tsActualCount = tservers.size();
      log.info(tsActualCount + " of " + tsExpectedCount + " tablet servers present in ZooKeeper");
      Thread.sleep(500);
    }

    int ssActualCount = 0;
    while (ssActualCount < ssExpectedCount) {
      Set<ServiceLockPath> tservers =
          context.getServerPaths().getScanServer(rg -> true, AddressSelector.all(), true);
      ssActualCount = tservers.size();
      log.info(ssActualCount + " of " + ssExpectedCount + " scan servers present in ZooKeeper");
      Thread.sleep(500);
    }

    int ecActualCount = 0;
    while (ecActualCount < ecExpectedCount) {
      Set<ServiceLockPath> compactors =
          context.getServerPaths().getCompactor(rg -> true, AddressSelector.all(), true);
      ecActualCount = compactors.size();
      log.info(ecActualCount + " of " + ecExpectedCount + " compactors present in ZooKeeper");
      Thread.sleep(500);
    }

    while (context.getServerPaths().getManager(true) == null) {
      log.info("Manager not yet present in ZooKeeper");
      Thread.sleep(500);
    }

    while (context.getServerPaths().getGarbageCollector(true) == null) {
      log.info("GC not yet present in ZooKeeper");
      Thread.sleep(500);
    }

  }

  private List<String> buildRemoteDebugParams(int port) {
    return Collections.singletonList(
        String.format("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=%d", port));
  }

  /**
   * @return generated remote debug ports if in debug mode.
   * @since 1.6.0
   */
  public Set<Pair<ServerType,Integer>> getDebugPorts() {
    return debugPorts;
  }

  List<ProcessReference> references(Process... procs) {
    return Stream.of(procs).map(ProcessReference::new).collect(toList());
  }

  public Map<ServerType,Collection<ProcessReference>> getProcesses() {
    Map<ServerType,Collection<ProcessReference>> result = new HashMap<>();
    MiniAccumuloClusterControl control = getClusterControl();
    result.put(ServerType.MANAGER, references(control.managerProcess));
    result.put(ServerType.TABLET_SERVER, references(control.tabletServerProcesses.values().stream()
        .flatMap(List::stream).collect(Collectors.toList()).toArray(new Process[0])));
    result.put(ServerType.COMPACTOR, references(control.compactorProcesses.values().stream()
        .flatMap(List::stream).collect(Collectors.toList()).toArray(new Process[0])));
    if (control.scanServerProcesses != null) {
      result.put(ServerType.SCAN_SERVER, references(control.scanServerProcesses.values().stream()
          .flatMap(List::stream).collect(Collectors.toList()).toArray(new Process[0])));
    }
    if (control.zooKeeperProcess != null) {
      result.put(ServerType.ZOOKEEPER, references(control.zooKeeperProcess));
    }
    if (control.gcProcess != null) {
      result.put(ServerType.GARBAGE_COLLECTOR, references(control.gcProcess));
    }
    if (control.monitor != null) {
      result.put(ServerType.MONITOR, references(control.monitor));
    }
    return result;
  }

  public void killProcess(ServerType type, ProcessReference proc)
      throws ProcessNotFoundException, InterruptedException {
    getClusterControl().killProcess(type, proc);
  }

  @Override
  public String getInstanceName() {
    return config.getInstanceName();
  }

  @Override
  public String getZooKeepers() {
    return config.getZooKeepers();
  }

  @Override
  public ServerContext getServerContext() {
    serverContextCreated.set(true);
    return context.get();
  }

  /**
   * Stops Accumulo and Zookeeper processes. If stop is not called, there is a shutdown hook that is
   * setup to kill the processes. However it's probably best to call stop in a finally block as soon
   * as possible.
   */
  @Override
  public synchronized void stop() throws IOException, InterruptedException {
    Preconditions.checkState(clusterState != State.TERMINATED,
        "Cannot stop a cluster that is terminated.");

    if (executor == null) {
      // keep repeated calls to stop() from failing
      return;
    }

    if (miniLock != null) {
      try {
        miniLock.unlock();
      } catch (InterruptedException | KeeperException e) {
        log.error("Error unlocking ServiceLock for MiniAccumuloClusterImpl", e);
      }
      miniLock = null;
      this.getServerContext().clearServiceLock();
    }
    if (miniLockZk != null) {
      miniLockZk.close();
      miniLockZk = null;
    }
    if (client != null) {
      client.close();
      client = null;
    }

    MiniAccumuloClusterControl control = getClusterControl();

    control.stop(ServerType.GARBAGE_COLLECTOR, null);
    control.stop(ServerType.MANAGER, null);
    control.stop(ServerType.TABLET_SERVER, null);
    control.stop(ServerType.COMPACTOR, null);
    control.stop(ServerType.SCAN_SERVER, null);

    // Clean up the locks in ZooKeeper so that if the cluster
    // is restarted, then the processes will start right away
    // and not wait for the old locks to be cleaned up.
    try {
      new ZooZap().zap(getServerContext(), "-manager", "-tservers", "-compactors", "-sservers",
          "--gc");
    } catch (RuntimeException e) {
      if (!e.getMessage().startsWith("Accumulo not initialized")) {
        log.error("Error zapping zookeeper locks", e);
      }
    }

    // Clear the location of the servers in ZooCache.
    boolean macStarted = false;
    try {
      ZooUtil.getRoot(getServerContext().getInstanceID());
      macStarted = true;
    } catch (IllegalStateException e) {
      if (!e.getMessage().startsWith("Accumulo not initialized")) {
        throw e;
      }
    }
    if (macStarted) {
      getServerContext().getZooCache().clear(path -> path.startsWith("/"));
    }
    control.stop(ServerType.ZOOKEEPER, null);

    // ACCUMULO-2985 stop the ExecutorService after we finished using it to stop accumulo procs
    if (executor != null) {
      List<Runnable> tasksRemaining = executor.shutdownNow();

      // the single thread executor shouldn't have any pending tasks, but check anyways
      if (!tasksRemaining.isEmpty()) {
        log.warn(
            "Unexpectedly had {} task(s) remaining in threadpool for execution when being stopped",
            tasksRemaining.size());
      }

      executor = null;
    }

    var miniDFSActual = miniDFS.get();
    if (config.getUseMiniDFS() && miniDFSActual != null) {
      miniDFSActual.shutdown();
    }
    for (Process p : cleanup) {
      p.destroy();
      p.waitFor();
    }
    miniDFS.set(null);
    clusterState = State.STOPPED;
  }

  @Override
  public synchronized void terminate() throws Exception {
    Preconditions.checkState(clusterState != State.TERMINATED,
        "Cannot stop a cluster that is terminated.");

    if (clusterState != State.STOPPED) {
      stop();
    }

    if (serverContextCreated.get()) {
      getServerContext().close();
    }
    clusterState = State.TERMINATED;
  }

  /**
   * @since 1.6.0
   */
  public MiniAccumuloConfigImpl getConfig() {
    return config;
  }

  @Override
  public AccumuloClient createAccumuloClient(String user, AuthenticationToken token) {
    return Accumulo.newClient().from(clientProperties.get()).as(user, token).build();
  }

  @Override
  public Properties getClientProperties() {
    // return a copy, without re-reading the file
    var copy = new Properties();
    copy.putAll(clientProperties.get());
    return copy;
  }

  @Override
  public FileSystem getFileSystem() {
    try {
      return FileSystem.get(new URI(dfsUri), new Configuration());
    } catch (IOException | URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  @VisibleForTesting
  protected void setShutdownExecutor(ExecutorService svc) {
    this.executor = svc;
  }

  @VisibleForTesting
  protected ExecutorService getShutdownExecutor() {
    return executor;
  }

  public void stopProcessesWithTimeout(final ServerType type, final List<Process> procs,
      final long timeout, final TimeUnit unit) {

    final List<Future<Integer>> futures = new ArrayList<>();
    for (Process proc : procs) {
      futures.add(executor.submit(() -> {
        proc.destroy();
        proc.waitFor(timeout, unit);
        return proc.exitValue();
      }));
    }

    while (!futures.isEmpty()) {
      futures.removeIf(f -> {
        if (f.isDone()) {
          try {
            f.get();
          } catch (ExecutionException | InterruptedException e) {
            log.warn("{} did not fully stop after {} seconds", type, unit.toSeconds(timeout), e);
          }
          return true;
        }
        return false;
      });
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        log.warn("Interrupted while trying to stop " + type + " processes.");
        Thread.currentThread().interrupt();
      }
    }
  }

  public int stopProcessWithTimeout(final Process proc, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    FutureTask<Integer> future = new FutureTask<>(() -> {
      proc.destroy();
      return proc.waitFor();
    });

    executor.execute(future);

    return future.get(timeout, unit);
  }

  /**
   * Get programmatic interface to information available in a normal monitor. XXX the returned
   * structure won't contain information about the metadata table until there is data in it. e.g. if
   * you want to see the metadata table you should create a table.
   *
   * @since 1.6.1
   */
  public ManagerMonitorInfo getManagerMonitorInfo()
      throws AccumuloException, AccumuloSecurityException {
    try (AccumuloClient c = Accumulo.newClient().from(clientProperties.get()).build()) {
      ClientContext context = (ClientContext) c;
      return ThriftClientTypes.MANAGER.execute(context,
          client -> client.getManagerStats(TraceUtil.traceInfo(), context.rpcCreds()));
    }
  }

  public MiniDFSCluster getMiniDfs() {
    return this.miniDFS.get();
  }

  @Override
  public MiniAccumuloClusterControl getClusterControl() {
    return clusterControl;
  }

  @Override
  public Path getTemporaryPath() {
    String p;
    if (config.getUseMiniDFS()) {
      p = "/tmp/";
    } else {
      java.nio.file.Path tmp = config.getDir().toPath().resolve("tmp");
      mkdirs(tmp);
      p = tmp.toString();
    }
    return getFileSystem().makeQualified(new Path(p));
  }

  @Override
  public AccumuloConfiguration getSiteConfiguration() {
    return new ConfigurationCopy(Stream.concat(DefaultConfiguration.getInstance().stream(),
        config.getSiteConfig().entrySet().stream()));
  }

  @Override
  public String getAccumuloPropertiesPath() {
    return config.getConfDir().toPath().resolve("accumulo.properties").toString();
  }

  @Override
  public String getClientPropsPath() {
    return config.getClientPropsFile().getAbsolutePath();
  }

}

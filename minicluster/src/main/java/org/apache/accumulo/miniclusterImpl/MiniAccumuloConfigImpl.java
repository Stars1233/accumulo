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

import static org.apache.accumulo.minicluster.ServerType.COMPACTOR;
import static org.apache.accumulo.minicluster.ServerType.GARBAGE_COLLECTOR;
import static org.apache.accumulo.minicluster.ServerType.MANAGER;
import static org.apache.accumulo.minicluster.ServerType.MONITOR;
import static org.apache.accumulo.minicluster.ServerType.SCAN_SERVER;
import static org.apache.accumulo.minicluster.ServerType.TABLET_SERVER;
import static org.apache.accumulo.minicluster.ServerType.ZOOKEEPER;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.accumulo.compactor.Compactor;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.ClientProperty;
import org.apache.accumulo.core.conf.HadoopCredentialProvider;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.conf.SiteConfiguration;
import org.apache.accumulo.gc.SimpleGarbageCollector;
import org.apache.accumulo.manager.Manager;
import org.apache.accumulo.minicluster.MemoryUnit;
import org.apache.accumulo.minicluster.MiniAccumuloCluster;
import org.apache.accumulo.minicluster.ServerType;
import org.apache.accumulo.monitor.Monitor;
import org.apache.accumulo.server.util.PortUtils;
import org.apache.accumulo.tserver.ScanServer;
import org.apache.accumulo.tserver.TabletServer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

/**
 * Holds configuration for {@link MiniAccumuloClusterImpl}. Required configurations must be passed
 * to constructor(s) and all other configurations are optional.
 *
 * @since 1.6.0
 */
public class MiniAccumuloConfigImpl {

  private static final Logger log = LoggerFactory.getLogger(MiniAccumuloConfigImpl.class);
  private static final String DEFAULT_INSTANCE_SECRET = "DONTTELL";
  static final String DEFAULT_ZOOKEEPER_HOST = "127.0.0.1";
  private static final EnumMap<ServerType,Function<String,Class<?>>> DEFAULT_SERVER_CLASSES =
      new EnumMap<>(Map.of(MANAGER, rg -> Manager.class, GARBAGE_COLLECTOR,
          rg -> SimpleGarbageCollector.class, MONITOR, rg -> Monitor.class, ZOOKEEPER,
          rg -> ZooKeeperServerMain.class, TABLET_SERVER, rg -> TabletServer.class, SCAN_SERVER,
          rg -> ScanServer.class, COMPACTOR, rg -> Compactor.class));

  private Path dir = null;
  private String rootPassword = null;
  private Map<String,String> hadoopConfOverrides = new HashMap<>();
  private Map<String,String> siteConfig = new HashMap<>();
  private Map<String,String> configuredSiteConfig = new HashMap<>();
  private Map<String,String> clientProps = new HashMap<>();
  private Map<ServerType,Long> memoryConfig = new HashMap<>();
  private final Map<ServerType,Function<String,Class<?>>> rgServerClassOverrides = new HashMap<>();
  private boolean jdwpEnabled = false;
  private Map<String,String> systemProperties = new HashMap<>();

  private String instanceName = "miniInstance";
  private String rootUserName = "root";

  private Path libDir;
  private Path libExtDir;
  private Path confDir;
  private File hadoopConfDir = null;
  private Path zooKeeperDir;
  private Path accumuloDir;
  private Path logDir;

  private int zooKeeperPort = 0;
  private int configuredZooKeeperPort = 0;
  private long zooKeeperStartupTime = 20_000;
  private String existingZooKeepers;

  private long defaultMemorySize = 256 * 1024 * 1024;

  private boolean initialized = false;

  // TODO Nuke existingInstance and push it over to StandaloneAccumuloCluster
  private Boolean existingInstance = null;

  private boolean useMiniDFS = false;
  private int numMiniDFSDataNodes = 1;

  private boolean useCredentialProvider = false;

  private String[] classpathItems = null;

  private String[] nativePathItems = null;

  // These are only used on top of existing instances
  private Configuration hadoopConf;
  private SiteConfiguration accumuloConf;

  private Consumer<MiniAccumuloConfigImpl> preStartConfigProcessor;

  private final ClusterServerConfiguration serverConfiguration;

  /**
   * @param dir An empty or nonexistent directory that Accumulo and Zookeeper can store data in.
   *        Creating the directory is left to the user. Java 7, Guava, and Junit provide methods for
   *        creating temporary directories.
   * @param rootPassword The initial password for the Accumulo root user
   */
  public MiniAccumuloConfigImpl(File dir, String rootPassword) {
    this.dir = dir.toPath();
    this.rootPassword = rootPassword;
    this.serverConfiguration = new ClusterServerConfiguration();
  }

  /**
   * Set directories and fully populate site config
   *
   * @return this
   */
  MiniAccumuloConfigImpl initialize() {

    // Sanity checks
    if (this.getDir().exists() && !this.getDir().isDirectory()) {
      throw new IllegalArgumentException("Must pass in directory, " + this.getDir() + " is a file");
    }

    if (this.getDir().exists()) {
      String[] children = this.getDir().list();
      if (children != null && children.length != 0) {
        throw new IllegalArgumentException("Directory " + this.getDir() + " is not empty");
      }
    }

    if (!initialized) {
      libDir = dir.resolve("lib");
      libExtDir = libDir.resolve("ext");
      confDir = dir.resolve("conf");
      accumuloDir = dir.resolve("accumulo");
      zooKeeperDir = dir.resolve("zookeeper");
      logDir = dir.resolve("logs");

      // Never want to override these if an existing instance, which may be using the defaults
      if (existingInstance == null || !existingInstance) {
        existingInstance = false;
        mergeProp(Property.INSTANCE_VOLUMES.getKey(), "file://" + accumuloDir.toAbsolutePath());
        mergeProp(Property.INSTANCE_SECRET.getKey(), DEFAULT_INSTANCE_SECRET);
      }

      // enable metrics reporting - by default will appear in standard log files.
      mergeProp(Property.GENERAL_MICROMETER_ENABLED.getKey(), "true");

      mergeProp(Property.TSERV_PORTSEARCH.getKey(), "true");
      mergeProp(Property.TSERV_DATACACHE_SIZE.getKey(), "10M");
      mergeProp(Property.TSERV_INDEXCACHE_SIZE.getKey(), "10M");
      mergeProp(Property.TSERV_SUMMARYCACHE_SIZE.getKey(), "10M");
      mergeProp(Property.TSERV_MAXMEM.getKey(), "40M");
      mergeProp(Property.TSERV_WAL_MAX_SIZE.getKey(), "100M");
      mergeProp(Property.TSERV_NATIVEMAP_ENABLED.getKey(), "false");
      mergeProp(Property.GC_CYCLE_DELAY.getKey(), "4s");
      mergeProp(Property.GC_CYCLE_START.getKey(), "0s");
      mergePropWithRandomPort(Property.MANAGER_CLIENTPORT.getKey());
      mergePropWithRandomPort(Property.TSERV_CLIENTPORT.getKey());
      mergePropWithRandomPort(Property.MONITOR_PORT.getKey());
      mergePropWithRandomPort(Property.GC_PORT.getKey());

      mergeProp(Property.COMPACTOR_PORTSEARCH.getKey(), "true");

      mergeProp(Property.MANAGER_COMPACTION_SERVICE_PRIORITY_QUEUE_SIZE.getKey(),
          Property.MANAGER_COMPACTION_SERVICE_PRIORITY_QUEUE_SIZE.getDefaultValue());
      mergeProp(Property.COMPACTION_SERVICE_DEFAULT_PLANNER.getKey(),
          Property.COMPACTION_SERVICE_DEFAULT_PLANNER.getDefaultValue());

      mergeProp(Property.COMPACTION_SERVICE_DEFAULT_GROUPS.getKey(),
          Property.COMPACTION_SERVICE_DEFAULT_GROUPS.getDefaultValue());

      if (isUseCredentialProvider()) {
        updateConfigForCredentialProvider();
      }

      if (existingInstance == null || !existingInstance) {
        existingInstance = false;
        String zkHost;
        if (useExistingZooKeepers()) {
          zkHost = existingZooKeepers;
        } else {
          // zookeeper port should be set explicitly in this class, not just on the site config
          if (zooKeeperPort == 0) {
            zooKeeperPort = PortUtils.getRandomFreePort();
          }

          zkHost = DEFAULT_ZOOKEEPER_HOST + ":" + zooKeeperPort;
        }
        siteConfig.put(Property.INSTANCE_ZK_HOST.getKey(), zkHost);
      }
      initialized = true;
    }
    return this;
  }

  private void updateConfigForCredentialProvider() {
    String cpPaths = siteConfig.get(Property.GENERAL_SECURITY_CREDENTIAL_PROVIDER_PATHS.getKey());
    if (cpPaths != null
        && !Property.GENERAL_SECURITY_CREDENTIAL_PROVIDER_PATHS.getDefaultValue().equals(cpPaths)) {
      // Already configured
      return;
    }

    Path keystoreFile = confDir.resolve("credential-provider.jks");
    String keystoreUri = "jceks://file" + keystoreFile.toAbsolutePath();
    Configuration conf = getHadoopConfiguration();
    HadoopCredentialProvider.setPath(conf, keystoreUri);

    // Set the URI on the siteCfg
    siteConfig.put(Property.GENERAL_SECURITY_CREDENTIAL_PROVIDER_PATHS.getKey(), keystoreUri);

    Iterator<Entry<String,String>> entries = siteConfig.entrySet().iterator();
    while (entries.hasNext()) {
      Entry<String,String> entry = entries.next();

      // Not a @Sensitive Property, ignore it
      if (!Property.isSensitive(entry.getKey())) {
        continue;
      }

      // Add the @Sensitive Property to the CredentialProvider
      try {
        HadoopCredentialProvider.createEntry(conf, entry.getKey(), entry.getValue().toCharArray());
      } catch (IOException e) {
        log.warn("Attempted to add " + entry.getKey() + " to CredentialProvider but failed", e);
        continue;
      }

      // Only remove it from the siteCfg if we succeeded in adding it to the CredentialProvider
      entries.remove();
    }
  }

  /**
   * Set a given key/value on the site config if it doesn't already exist
   */
  private void mergeProp(String key, String value) {
    if (!siteConfig.containsKey(key)) {
      siteConfig.put(key, value);
    }
  }

  /**
   * Sets a given key with a random port for the value on the site config if it doesn't already
   * exist.
   */
  private void mergePropWithRandomPort(String key) {
    if (!siteConfig.containsKey(key)) {
      siteConfig.put(key, "0");
    }
  }

  /**
   * Calling this method is optional. If not set, defaults to 'miniInstance'
   *
   * @since 1.6.0
   */
  public MiniAccumuloConfigImpl setInstanceName(String instanceName) {
    this.instanceName = instanceName;
    return this;
  }

  /**
   * Calling this method is optional. If not set, it defaults to an empty map.
   *
   * @param siteConfig key/values that you normally put in accumulo.properties can be put here.
   */
  public MiniAccumuloConfigImpl setSiteConfig(Map<String,String> siteConfig) {
    if (existingInstance != null && existingInstance) {
      throw new UnsupportedOperationException(
          "Cannot set set config info when using an existing instance.");
    }

    this.existingInstance = Boolean.FALSE;

    return _setSiteConfig(siteConfig);
  }

  public MiniAccumuloConfigImpl setClientProps(Map<String,String> clientProps) {
    if (existingInstance != null && existingInstance) {
      throw new UnsupportedOperationException(
          "Cannot set zookeeper info when using an existing instance.");
    }
    this.existingInstance = Boolean.FALSE;
    this.clientProps = clientProps;
    return this;
  }

  private MiniAccumuloConfigImpl _setSiteConfig(Map<String,String> siteConfig) {
    this.siteConfig = new HashMap<>(siteConfig);
    this.configuredSiteConfig = new HashMap<>(siteConfig);
    return this;
  }

  /**
   * Calling this method is optional. A random port is generated by default
   *
   * @param zooKeeperPort A valid (and unused) port to use for the zookeeper
   *
   * @since 1.6.0
   */
  public MiniAccumuloConfigImpl setZooKeeperPort(int zooKeeperPort) {
    if (existingInstance != null && existingInstance) {
      throw new UnsupportedOperationException(
          "Cannot set zookeeper info when using an existing instance.");
    }

    this.existingInstance = Boolean.FALSE;

    this.configuredZooKeeperPort = zooKeeperPort;
    this.zooKeeperPort = zooKeeperPort;
    return this;
  }

  /**
   * Configure the time to wait for ZooKeeper to startup. Calling this method is optional. The
   * default is 20000 milliseconds
   *
   * @param zooKeeperStartupTime Time to wait for ZooKeeper to startup, in milliseconds
   *
   * @since 1.6.1
   */
  public MiniAccumuloConfigImpl setZooKeeperStartupTime(long zooKeeperStartupTime) {
    if (existingInstance != null && existingInstance) {
      throw new UnsupportedOperationException(
          "Cannot set zookeeper info when using an existing instance.");
    }

    this.existingInstance = Boolean.FALSE;

    this.zooKeeperStartupTime = zooKeeperStartupTime;
    return this;
  }

  /**
   * Configure an existing ZooKeeper instance to use. Calling this method is optional. If not set, a
   * new ZooKeeper instance is created.
   *
   * @param existingZooKeepers Connection string for a already-running ZooKeeper instance. A null
   *        value will turn off this feature.
   *
   * @since 1.8.0
   */
  public MiniAccumuloConfigImpl setExistingZooKeepers(String existingZooKeepers) {
    this.existingZooKeepers = existingZooKeepers;
    return this;
  }

  /**
   * Sets the amount of memory to use in the specified process. Calling this method is optional.
   * Default memory is 256M
   *
   * @param serverType the type of server to apply the memory settings
   * @param memory amount of memory to set
   *
   * @param memoryUnit the units for which to apply with the memory size
   *
   * @since 1.6.0
   */
  public MiniAccumuloConfigImpl setMemory(ServerType serverType, long memory,
      MemoryUnit memoryUnit) {
    this.memoryConfig.put(serverType, memoryUnit.toBytes(memory));
    return this;
  }

  /**
   * Sets the default memory size to use. This value is also used when a ServerType has not been
   * configured explicitly. Calling this method is optional. Default memory is 256M
   *
   * @param memory amount of memory to set
   *
   * @param memoryUnit the units for which to apply with the memory size
   *
   * @since 1.6.0
   */
  public MiniAccumuloConfigImpl setDefaultMemory(long memory, MemoryUnit memoryUnit) {
    this.defaultMemorySize = memoryUnit.toBytes(memory);
    return this;
  }

  /**
   * Sets a function that returns the class that will be used to instantiate this server type given
   * a resource group.
   */
  public MiniAccumuloConfigImpl setServerClass(ServerType type, Function<String,Class<?>> func) {
    rgServerClassOverrides.put(type, func);
    return this;
  }

  /**
   * @return the class to use to instantiate this server type.
   */
  public Class<?> getServerClass(ServerType type, String rg) {
    Class<?> clazz = rgServerClassOverrides.getOrDefault(type, r -> null).apply(rg);
    if (clazz != null) {
      return clazz;
    }
    return DEFAULT_SERVER_CLASSES.get(type).apply(rg);
  }

  /**
   * @return a copy of the site config
   */
  public Map<String,String> getSiteConfig() {
    return new HashMap<>(siteConfig);
  }

  /**
   * @return a copy of client props
   */
  public Map<String,String> getClientProps() {
    return new HashMap<>(clientProps);
  }

  public Map<String,String> getConfiguredSiteConfig() {
    return new HashMap<>(configuredSiteConfig);
  }

  /**
   * @return name of configured instance
   *
   * @since 1.6.0
   */
  public String getInstanceName() {
    return instanceName;
  }

  /**
   * @return The configured zookeeper port
   *
   * @since 1.6.0
   */
  public int getZooKeeperPort() {
    return zooKeeperPort;
  }

  public int getConfiguredZooKeeperPort() {
    return configuredZooKeeperPort;
  }

  public long getZooKeeperStartupTime() {
    return zooKeeperStartupTime;
  }

  public String getExistingZooKeepers() {
    return existingZooKeepers;
  }

  public boolean useExistingZooKeepers() {
    return existingZooKeepers != null && !existingZooKeepers.isEmpty();
  }

  File getLibDir() {
    return libDir.toFile();
  }

  File getLibExtDir() {
    return libExtDir.toFile();
  }

  public File getConfDir() {
    return confDir.toFile();
  }

  File getZooKeeperDir() {
    return zooKeeperDir.toFile();
  }

  public File getAccumuloDir() {
    return accumuloDir.toFile();
  }

  public File getLogDir() {
    return logDir.toFile();
  }

  /**
   * @param serverType get configuration for this server type
   *
   * @return memory configured in bytes, returns default if this server type is not configured
   *
   * @since 1.6.0
   */
  public long getMemory(ServerType serverType) {
    return memoryConfig.containsKey(serverType) ? memoryConfig.get(serverType) : defaultMemorySize;
  }

  /**
   * @return memory configured in bytes
   *
   * @since 1.6.0
   */
  public long getDefaultMemory() {
    return defaultMemorySize;
  }

  /**
   * @return zookeeper connection string
   *
   * @since 1.6.0
   */
  public String getZooKeepers() {
    return siteConfig.get(Property.INSTANCE_ZK_HOST.getKey());
  }

  /**
   * @return the base directory of the cluster configuration
   */
  public File getDir() {
    return dir.toFile();
  }

  /**
   * @return the root password of this cluster configuration
   */
  public String getRootPassword() {
    return rootPassword;
  }

  /**
   * @return ClusterServerConfiguration
   */
  public ClusterServerConfiguration getClusterServerConfiguration() {
    return serverConfiguration;
  }

  /**
   * @return is the current configuration in jdwpEnabled mode?
   *
   * @since 1.6.0
   */
  public boolean isJDWPEnabled() {
    return jdwpEnabled;
  }

  /**
   * @param jdwpEnabled should the processes run remote jdwpEnabled servers?
   * @return the current instance
   *
   * @since 1.6.0
   */
  public MiniAccumuloConfigImpl setJDWPEnabled(boolean jdwpEnabled) {
    this.jdwpEnabled = jdwpEnabled;
    return this;
  }

  public boolean getUseMiniDFS() {
    return useMiniDFS;
  }

  /**
   * Configures this cluster to use miniDFS instead of the local {@link FileSystem}. Using this
   * feature will not allow you to re-start {@link MiniAccumuloCluster} by calling
   * {@link MiniAccumuloCluster#start()} after {@link MiniAccumuloCluster#stop()}, because the
   * underlying miniDFS cannot be restarted.
   */
  public void useMiniDFS(boolean useMiniDFS) {
    useMiniDFS(useMiniDFS, 1);
  }

  public void useMiniDFS(boolean useMiniDFS, int numDataNodes) {
    Preconditions.checkArgument(numDataNodes > 0);
    this.useMiniDFS = useMiniDFS;
    this.numMiniDFSDataNodes = numDataNodes;
  }

  public int getNumDataNodes() {
    return numMiniDFSDataNodes;
  }

  public File getAccumuloPropsFile() {
    return getConfDir().toPath().resolve("accumulo.properties").toFile();
  }

  /**
   * @return location of accumulo-client.properties file for connecting to this mini cluster
   */
  public File getClientPropsFile() {
    return confDir.resolve("accumulo-client.properties").toFile();
  }

  /**
   * sets system properties set for service processes
   *
   * @since 1.6.0
   */
  public void setSystemProperties(Map<String,String> systemProperties) {
    this.systemProperties = new HashMap<>(systemProperties);
  }

  /**
   * @return a copy of the system properties for service processes
   *
   * @since 1.6.0
   */
  public Map<String,String> getSystemProperties() {
    return new HashMap<>(systemProperties);
  }

  /**
   * Gets the classpath elements to use when spawning processes.
   *
   * @return the classpathItems, if set
   *
   * @since 1.6.0
   */
  public String[] getClasspathItems() {
    return classpathItems;
  }

  /**
   * Sets the classpath elements to use when spawning processes.
   *
   * @param classpathItems the classpathItems to set
   * @since 1.6.0
   */
  public void setClasspathItems(String... classpathItems) {
    this.classpathItems = classpathItems;
  }

  /**
   * @return the paths to use for loading native libraries
   *
   * @since 1.6.0
   */
  public String[] getNativeLibPaths() {
    return this.nativePathItems == null ? new String[0] : this.nativePathItems;
  }

  /**
   * Sets the path for processes to use for loading native libraries
   *
   * @param nativePathItems the nativePathItems to set
   * @since 1.6.0
   */
  public MiniAccumuloConfigImpl setNativeLibPaths(String... nativePathItems) {
    this.nativePathItems = nativePathItems;
    return this;
  }

  /**
   * Sets arbitrary configuration properties.
   *
   * @since 1.6.0
   */
  public void setProperty(Property p, String value) {
    this.siteConfig.put(p.getKey(), value);
  }

  public void setClientProperty(ClientProperty property, String value) {
    setClientProperty(property.getKey(), value);
  }

  public void setClientProperty(String key, String value) {
    this.clientProps.put(key, value);
  }

  /**
   * Sets arbitrary configuration properties.
   *
   * @since 2.0.0
   */
  public void setProperty(String p, String value) {
    this.siteConfig.put(p, value);
  }

  /**
   * @return the useCredentialProvider
   */
  public boolean isUseCredentialProvider() {
    return useCredentialProvider;
  }

  /**
   * @param useCredentialProvider the useCredentialProvider to set
   */
  public void setUseCredentialProvider(boolean useCredentialProvider) {
    this.useCredentialProvider = useCredentialProvider;
  }

  /**
   * Informs MAC that it's running against an existing accumulo instance. It is assumed that it's
   * already initialized and hdfs/zookeeper are already running.
   *
   * @param accumuloProps a File representation of the accumulo.properties file for the instance
   *        being run
   * @param hadoopConfDir a File representation of the hadoop configuration directory containing
   *        core-site.xml and hdfs-site.xml
   *
   * @return MiniAccumuloConfigImpl which uses an existing accumulo configuration
   *
   * @since 1.6.2
   *
   * @throws IOException when there are issues converting the provided Files to URLs
   */
  public MiniAccumuloConfigImpl useExistingInstance(File accumuloProps, File hadoopConfDir)
      throws IOException {
    if (existingInstance != null && !existingInstance) {
      throw new UnsupportedOperationException(
          "Cannot set to useExistingInstance after specifying config/zookeeper");
    }

    this.existingInstance = Boolean.TRUE;

    System.setProperty("accumulo.properties", "accumulo.properties");
    this.hadoopConfDir = hadoopConfDir;
    hadoopConf = new Configuration(false);
    accumuloConf = SiteConfiguration.fromFile(accumuloProps).build();
    Path coreSite = this.hadoopConfDir.toPath().resolve("core-site.xml");
    Path hdfsSite = this.hadoopConfDir.toPath().resolve("hdfs-site.xml");

    try {
      hadoopConf.addResource(coreSite.toUri().toURL());
      hadoopConf.addResource(hdfsSite.toUri().toURL());
    } catch (MalformedURLException e1) {
      throw e1;
    }

    Map<String,String> siteConfigMap = new HashMap<>();
    for (Entry<String,String> e : accumuloConf) {
      siteConfigMap.put(e.getKey(), e.getValue());
    }
    _setSiteConfig(siteConfigMap);

    return this;
  }

  /**
   * @return MAC should run assuming it's configured for an initialized accumulo instance
   *
   * @since 1.6.2
   */
  public boolean useExistingInstance() {
    return existingInstance != null && existingInstance;
  }

  /**
   * @return hadoop configuration directory being used
   *
   * @since 1.6.2
   */
  public File getHadoopConfDir() {
    return hadoopConfDir;
  }

  /**
   * @return accumulo Configuration being used
   *
   * @since 1.6.2
   */
  public AccumuloConfiguration getAccumuloConfiguration() {
    return accumuloConf;
  }

  /**
   * @return hadoop Configuration being used
   *
   * @since 1.6.2
   */
  public Configuration getHadoopConfiguration() {
    return hadoopConf;
  }

  /**
   * @return the default Accumulo "superuser"
   * @since 1.7.0
   */
  public String getRootUserName() {
    return rootUserName;
  }

  /**
   * Sets the default Accumulo "superuser".
   *
   * @param rootUserName The name of the user to create with administrative permissions during
   *        initialization
   * @since 1.7.0
   */
  public void setRootUserName(String rootUserName) {
    this.rootUserName = rootUserName;
  }

  /**
   * Set the object that will be used to modify the site configuration right before it's written out
   * a file. This would be useful in the case where the configuration needs to be updated based on a
   * property that is set in MiniAccumuloClusterImpl like instance.volumes
   *
   */
  public void setPreStartConfigProcessor(Consumer<MiniAccumuloConfigImpl> processor) {
    this.preStartConfigProcessor = processor;
  }

  /**
   * Called by MiniAccumuloClusterImpl after all modifications are done to the configuration and
   * right before it's written out to a file.
   */
  public void preStartConfigUpdate() {
    if (this.preStartConfigProcessor != null) {
      this.preStartConfigProcessor.accept(this);
    }
  }

  /**
   * Add server-side Hadoop configuration properties
   *
   * @param overrides properties
   * @since 2.1.1
   */
  public void setHadoopConfOverrides(Map<String,String> overrides) {
    hadoopConfOverrides.putAll(overrides);
  }

  /**
   * Get server-side Hadoop configuration properties
   *
   * @return map of properties set in prior call to {@link #setHadoopConfOverrides(Map)}
   * @since 2.1.1
   */
  public Map<String,String> getHadoopConfOverrides() {
    return new HashMap<>(hadoopConfOverrides);
  }
}

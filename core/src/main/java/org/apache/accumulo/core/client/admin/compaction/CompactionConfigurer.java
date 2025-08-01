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
package org.apache.accumulo.core.client.admin.compaction;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.apache.accumulo.core.client.PluginEnvironment;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.TabletId;

/**
 * Enables dynamically overriding of per table properties used to create the output file for a
 * compaction. For example it could override the per table property for compression.
 *
 * @since 2.1.0
 */
public interface CompactionConfigurer {
  /**
   * @since 2.1.0
   */
  interface InitParameters {
    TableId getTableId();

    Map<String,String> getOptions();

    PluginEnvironment getEnvironment();
  }

  void init(InitParameters iparams);

  /**
   * @since 2.1.0
   */
  interface InputParameters {
    TableId getTableId();

    Collection<CompactableFile> getInputFiles();

    /**
     * Returns the tablet that is compacting.
     *
     * @since 2.1.4
     */
    TabletId getTabletId();

    /**
     * Returns the path that the compaction will write to, one use of this is to know the output
     * volume.
     *
     * @since 2.1.4
     */
    URI getOutputFile();

    /**
     * For user and selector compactions:
     * <ul>
     * <li>Returns the selected set of files to be compacted.</li>
     * <li>When getInputFiles() (inputFiles) and getSelectedFiles() (selectedFiles) are equal, then
     * this is the final compaction.</li>
     * <li>When they are not equal, this is an intermediate compaction.</li>
     * <li>Intermediate compactions are compactions whose resultant RFile will be consumed by
     * another compaction.</li>
     * <li>inputFiles and selectedFiles can be compared using: <code>
     * selectedFiles.equals(inputFiles instanceof Set ? inputFiles : Set.copyOf(inputFiles))
     * </code></li>
     * </ul>
     * For system compactions:
     * <ul>
     * <li>There is no selected set of files so the empty set is returned.</li>
     * </ul>
     *
     * @since 4.0.0
     */
    public Set<CompactableFile> getSelectedFiles();

    PluginEnvironment getEnvironment();
  }

  /**
   * Specifies how the output file should be created for a compaction.
   *
   * @since 2.1.0
   */
  class Overrides {
    private final Map<String,String> tablePropertyOverrides;

    public Overrides(Map<String,String> tablePropertyOverrides) {
      this.tablePropertyOverrides = Map.copyOf(tablePropertyOverrides);
    }

    /**
     * @return Table properties to override.
     */
    public Map<String,String> getOverrides() {
      return tablePropertyOverrides;
    }
  }

  Overrides override(InputParameters params);
}

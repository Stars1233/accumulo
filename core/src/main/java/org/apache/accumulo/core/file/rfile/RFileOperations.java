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
package org.apache.accumulo.core.file.rfile;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;

import org.apache.accumulo.core.client.sample.Sampler;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.file.FileOperations;
import org.apache.accumulo.core.file.FileSKVIterator;
import org.apache.accumulo.core.file.FileSKVWriter;
import org.apache.accumulo.core.file.blockfile.impl.CachableBlockFile.CachableBuilder;
import org.apache.accumulo.core.file.rfile.RFile.RFileSKVIterator;
import org.apache.accumulo.core.file.rfile.bcfile.BCFile;
import org.apache.accumulo.core.metadata.TabletFile;
import org.apache.accumulo.core.sample.impl.SamplerConfigurationImpl;
import org.apache.accumulo.core.sample.impl.SamplerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

public class RFileOperations extends FileOperations {

  private static final Logger LOG = LoggerFactory.getLogger(RFileOperations.class);

  private static final Collection<ByteSequence> EMPTY_CF_SET = Collections.emptySet();

  private static RFileSKVIterator getReader(FileOptions options) throws IOException {
    CachableBuilder cb = new CachableBuilder()
        .fsPath(options.getFileSystem(), options.getFile().getPath(), options.dropCacheBehind)
        .conf(options.getConfiguration()).fileLen(options.getFileLenCache())
        .cacheProvider(options.cacheProvider).cryptoService(options.getCryptoService());
    return RFile.getReader(cb, options.getFile());
  }

  @Override
  protected long getFileSize(FileOptions options) throws IOException {
    return options.getFileSystem().getFileStatus(options.getFile().getPath()).getLen();
  }

  @Override
  protected FileSKVIterator openIndex(FileOptions options) throws IOException {
    return getReader(options).getIndex();
  }

  @Override
  protected FileSKVIterator openReader(FileOptions options) throws IOException {
    FileSKVIterator reader = getReader(options);

    if (options.isSeekToBeginning()) {
      reader.seek(new Range((Key) null, null), EMPTY_CF_SET, false);
    }

    return reader;
  }

  @Override
  protected FileSKVIterator openScanReader(FileOptions options) throws IOException {
    FileSKVIterator reader = getReader(options);
    reader.seek(options.getRange(), options.getColumnFamilies(), options.isRangeInclusive());
    return reader;
  }

  @Override
  protected FileSKVWriter openWriter(FileOptions options) throws IOException {

    AccumuloConfiguration acuconf = options.getTableConfiguration();

    long blockSize = acuconf.getAsBytes(Property.TABLE_FILE_COMPRESSED_BLOCK_SIZE);
    Preconditions.checkArgument((blockSize < Integer.MAX_VALUE && blockSize > 0),
        "table.file.compress.blocksize must be greater than 0 and less than " + Integer.MAX_VALUE);
    long indexBlockSize = acuconf.getAsBytes(Property.TABLE_FILE_COMPRESSED_BLOCK_SIZE_INDEX);
    Preconditions.checkArgument((indexBlockSize < Integer.MAX_VALUE && indexBlockSize > 0),
        "table.file.compress.blocksize.index must be greater than 0 and less than "
            + Integer.MAX_VALUE);

    SamplerConfigurationImpl samplerConfig = SamplerConfigurationImpl.newSamplerConfig(acuconf);
    Sampler sampler = null;

    if (samplerConfig != null) {
      sampler = SamplerFactory.newSampler(samplerConfig, acuconf, options.isAccumuloStartEnabled());
    }

    String compression = options.getCompression();
    compression = compression == null
        ? options.getTableConfiguration().get(Property.TABLE_FILE_COMPRESSION_TYPE) : compression;

    FSDataOutputStream outputStream = options.getOutputStream();

    Configuration conf = options.getConfiguration();

    if (outputStream == null) {
      int hrep = conf.getInt("dfs.replication", 3);
      int trep = acuconf.getCount(Property.TABLE_FILE_REPLICATION);
      int rep = hrep;
      if (trep > 0 && trep != hrep) {
        rep = trep;
      }
      long hblock = conf.getLong("dfs.block.size", 1 << 26);
      long tblock = acuconf.getAsBytes(Property.TABLE_FILE_BLOCK_SIZE);
      long block = hblock;
      if (tblock > 0) {
        block = tblock;
      }
      int bufferSize = conf.getInt("io.file.buffer.size", 4096);

      TabletFile file = options.getFile();
      FileSystem fs = options.getFileSystem();

      var ecEnable = EcEnabled.valueOf(
          options.getTableConfiguration().get(Property.TABLE_ENABLE_ERASURE_CODES).toUpperCase());

      if (fs instanceof DistributedFileSystem) {
        var builder = ((DistributedFileSystem) fs).createFile(file.getPath()).bufferSize(bufferSize)
            .blockSize(block).overwrite(false);

        if (options.dropCacheBehind) {
          builder = builder.syncBlock();
        }

        switch (ecEnable) {
          case ENABLE:
            String ecPolicyName =
                options.getTableConfiguration().get(Property.TABLE_ERASURE_CODE_POLICY);
            // The default value of this property is empty string. If empty string is given to this
            // builder it will disable erasure coding. So adding an explicit check for that.
            Preconditions.checkArgument(!ecPolicyName.isBlank(), "Blank or empty value set for %s",
                Property.TABLE_ERASURE_CODE_POLICY.getKey());
            builder = builder.ecPolicyName(ecPolicyName);
            break;
          case DISABLE:
            // force replication
            builder = builder.replication((short) rep).replicate();
            break;
          case INHERIT:
            // use the directory settings for replication or EC
            builder = builder.replication((short) rep);
            break;
          default:
            throw new IllegalStateException(ecEnable.name());
        }

        outputStream = builder.build();
      } else if (options.dropCacheBehind) {
        EnumSet<CreateFlag> set = EnumSet.of(CreateFlag.SYNC_BLOCK, CreateFlag.CREATE);
        outputStream = fs.create(file.getPath(), FsPermission.getDefault(), set, bufferSize,
            (short) rep, block, null);
      } else {
        outputStream = fs.create(file.getPath(), false, bufferSize, (short) rep, block);
      }

      if (options.dropCacheBehind) {
        try {
          // Tell the DataNode that the file does not need to be cached in the OS page cache
          outputStream.setDropBehind(Boolean.TRUE);
          LOG.trace("Called setDropBehind(TRUE) for stream writing file {}", options.file);
        } catch (UnsupportedOperationException e) {
          LOG.debug("setDropBehind not enabled for file: {}", options.file);
        } catch (IOException e) {
          LOG.debug("IOException setting drop behind for file: {}, msg: {}", options.file,
              e.getMessage());
        }
      }
    }

    BCFile.Writer _cbw = new BCFile.Writer(outputStream, compression, conf, options.cryptoService);

    return new RFile.Writer(_cbw, (int) blockSize, (int) indexBlockSize, samplerConfig, sampler);
  }
}

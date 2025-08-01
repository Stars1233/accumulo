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
package org.apache.accumulo.core.conf;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.accumulo.core.fate.Fate;
import org.apache.accumulo.core.file.rfile.RFile;
import org.apache.accumulo.core.file.rfile.bcfile.Compression;
import org.apache.accumulo.core.file.rfile.bcfile.CompressionAlgorithm;
import org.apache.commons.lang3.Range;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.Compressor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.gson.JsonParser;

/**
 * Types of {@link Property} values. Each type has a short name, a description, and a regex which
 * valid values match. All of these fields are optional.
 */
public enum PropertyType {
  PREFIX(null, x -> false, null),

  TIMEDURATION("duration", boundedUnits(0, Long.MAX_VALUE, true, "", "ms", "s", "m", "h", "d"),
      "A non-negative integer optionally followed by a unit of time (whitespace"
          + " disallowed), as in 30s.\n"
          + "If no unit of time is specified, seconds are assumed. Valid units"
          + " are 'ms', 's', 'm', 'h' for milliseconds, seconds, minutes, and hours.\n"
          + "Examples of valid durations are '600', '30s', '45m', '30000ms', '3d', and '1h'.\n"
          + "Examples of invalid durations are '1w', '1h30m', '1s 200ms', 'ms', '',"
          + " and 'a'.\nUnless otherwise stated, the max value for the duration"
          + " represented in milliseconds is " + Long.MAX_VALUE),

  BYTES("bytes", boundedUnits(0, Long.MAX_VALUE, false, "", "B", "K", "M", "G"),
      "A positive integer optionally followed by a unit of memory (whitespace disallowed).\n"
          + "If no unit is specified, bytes are assumed. Valid units are 'B',"
          + " 'K', 'M' or 'G' for bytes, kilobytes, megabytes, gigabytes.\n"
          + "Examples of valid memories are '1024', '20B', '100K', '1500M', '2G', '20%'.\n"
          + "Examples of invalid memories are '1M500K', '1M 2K', '1MB', '1.5G',"
          + " '1,024K', '', and 'a'.\n"
          + "Unless otherwise stated, the max value for the memory represented in bytes is "
          + Long.MAX_VALUE),

  MEMORY("memory", boundedUnits(0, Long.MAX_VALUE, false, "", "B", "K", "M", "G", "%"),
      "A positive integer optionally followed by a unit of memory or a"
          + " percentage (whitespace disallowed).\n"
          + "If a percentage is specified, memory will be a percentage of the"
          + " max memory allocated to a Java process (set by the JVM option -Xmx).\n"
          + "If no unit is specified, bytes are assumed. Valid units are 'B',"
          + " 'K', 'M', 'G', '%' for bytes, kilobytes, megabytes, gigabytes, and percentage.\n"
          + "Examples of valid memories are '1024', '20B', '100K', '1500M', '2G', '20%'.\n"
          + "Examples of invalid memories are '1M500K', '1M 2K', '1MB', '1.5G',"
          + " '1,024K', '', and 'a'.\n"
          + "Unless otherwise stated, the max value for the memory represented in bytes is "
          + Long.MAX_VALUE),

  HOSTLIST("host list",
      new Matches(
          "[\\w-]+(?:\\.[\\w-]+)*(?:\\:\\d{1,5})?(?:,[\\w-]+(?:\\.[\\w-]+)*(?:\\:\\d{1,5})?)*"),
      "A comma-separated list of hostnames or ip addresses, with optional port numbers.\n"
          + "Examples of valid host lists are"
          + " 'localhost:2000,www.example.com,10.10.1.1:500' and 'localhost'.\n"
          + "Examples of invalid host lists are '', ':1000', and 'localhost:80000'"),

  PORT("port",
      x -> Stream.of(new Bounds(1024, 65535), in(true, "0"), new PortRange("\\d{4,5}-\\d{4,5}"))
          .anyMatch(y -> y.test(x)),
      "An positive integer in the range 1024-65535 (not already in use or"
          + " specified elsewhere in the configuration),\n"
          + "zero to indicate any open ephemeral port, or a range of positive"
          + " integers specified as M-N"),

  COUNT("count", new Bounds(0, Integer.MAX_VALUE),
      "A non-negative integer in the range of 0-" + Integer.MAX_VALUE),

  FRACTION("fraction/percentage", new FractionPredicate(),
      "A floating point number that represents either a fraction or, if"
          + " suffixed with the '%' character, a percentage.\n"
          + "Examples of valid fractions/percentages are '10', '1000%', '0.05',"
          + " '5%', '0.2%', '0.0005'.\n"
          + "Examples of invalid fractions/percentages are '', '10 percent', 'Hulk Hogan'"),

  PATH("path", x -> true,
      "A string that represents a filesystem path, which can be either relative"
          + " or absolute to some directory. The filesystem depends on the property. "
          + "Substitutions of the ACCUMULO_HOME environment variable can be done in the system "
          + "config file using '${env:ACCUMULO_HOME}' or similar."),

  DROP_CACHE_SELECTION("drop cache option", in(false, "NONE", "ALL", "NON-IMPORT"),
      "One of 'ALL', 'NONE', or 'NON-IMPORT'"),

  ABSOLUTEPATH("absolute path",
      x -> x == null || x.trim().isEmpty() || new Path(x.trim()).isAbsolute(),
      "An absolute filesystem path. The filesystem depends on the property."
          + " This is the same as path, but enforces that its root is explicitly specified."),

  CLASSNAME("java class", new Matches("[\\w$.]*"),
      "A fully qualified java class name representing a class on the classpath.\n"
          + "An example is 'java.lang.String', rather than 'String'"),

  CLASSNAMELIST("java class list", new Matches("[\\w$.,]*"),
      "A list of fully qualified java class names representing classes on the classpath.\n"
          + "An example is 'java.lang.String', rather than 'String'"),

  COMPRESSION_TYPE("compression type name", new ValidCompressionType(),
      "One of the configured compression types."),

  DURABILITY("durability", in(false, null, "default", "none", "log", "flush", "sync"),
      "One of 'none', 'log', 'flush' or 'sync'."),

  GC_POST_ACTION("gc_post_action", in(true, null, "none", "flush", "compact"),
      "One of 'none', 'flush', or 'compact'."),

  STRING("string", x -> true,
      "An arbitrary string of characters whose format is unspecified and"
          + " interpreted based on the context of the property to which it applies."),

  JSON("json", new ValidJson(),
      "An arbitrary string that represents a valid, parsable generic json object. The validity "
          + "of the json object in the context of the property usage is not checked by this type."),
  BOOLEAN("boolean", in(false, null, "true", "false"),
      "Has a value of either 'true' or 'false' (case-insensitive)"),

  URI("uri", x -> true, "A valid URI"),

  FILENAME_EXT("file name extension", in(true, RFile.EXTENSION),
      "One of the currently supported filename extensions for storing table data files. "
          + "Currently, only " + RFile.EXTENSION + " is supported."),
  VOLUMES("volumes", new ValidVolumes(), "See instance.volumes documentation"),
  FATE_USER_CONFIG(ValidUserFateConfig.NAME, new ValidUserFateConfig(),
      "An arbitrary string that: 1. Represents a valid, parsable generic json object. "
          + "2. the keys of the json are strings which contain a comma-separated list of fate operations. "
          + "3. the values of the json are integers which represent the number of threads assigned to the fate operations. "
          + "4. all possible user fate operations are present in the json. "
          + "5. no fate operations are repeated."),

  FATE_META_CONFIG(ValidMetaFateConfig.NAME, new ValidMetaFateConfig(),
      "An arbitrary string that: 1. Represents a valid, parsable generic json object. "
          + "2. the keys of the json are strings which contain a comma-separated list of fate operations. "
          + "3. the values of the json are integers which represent the number of threads assigned to the fate operations. "
          + "4. all possible meta fate operations are present in the json. "
          + "5. no fate operations are repeated."),
  FATE_THREADPOOL_SIZE("(deprecated) Manager FATE thread pool size", new FateThreadPoolSize(),
      "No format check. Allows any value to be set but will warn the user that the"
          + " property is no longer used."),
  EC("erasurecode", in(false, "enable", "disable", "inherit"),
      "One of 'enable','disable','inherit'.");

  private final String shortname;
  private final String format;
  // Field is transient because enums are Serializable, but Predicates aren't necessarily,
  // and our lambdas certainly aren't; This shouldn't matter because enum serialization doesn't
  // store fields, so this is a false positive in our spotbugs version
  // see https://github.com/spotbugs/spotbugs/issues/740
  private transient final Predicate<String> predicate;

  PropertyType(String shortname, Predicate<String> predicate, String formatDescription) {
    this.shortname = shortname;
    this.predicate = Objects.requireNonNull(predicate);
    this.format = formatDescription;
  }

  @Override
  public String toString() {
    return shortname;
  }

  /**
   * Gets the description of this type.
   *
   * @return description
   */
  String getFormatDescription() {
    return format;
  }

  /**
   * Checks if the given value is valid for this type.
   *
   * @return true if value is valid or null, or if this type has no regex
   */
  public boolean isValidFormat(String value) {
    // this can't happen because enum fields aren't serialized, so it doesn't matter if the
    // predicate was transient or not, but it's probably not hurting anything to check and provide
    // the helpful error message for troubleshooting, just in case
    Preconditions.checkState(predicate != null,
        "Predicate was null, maybe this enum was serialized????");
    return predicate.test(value);
  }

  /**
   * Validate that the provided string can be parsed into a json object. This implementation uses
   * jackson databind because it is less permissive that GSON for what is considered valid. This
   * implementation cannot guarantee that the json is valid for the target usage. That would require
   * something like a json schema or a check specific to the use-case. This is only trying to
   * provide a generic, minimal check that at least the json is valid.
   */
  private static class ValidJson implements Predicate<String> {
    private static final Logger log = LoggerFactory.getLogger(ValidJson.class);

    // ObjectMapper is thread-safe, but uses synchronization. If this causes contention, ThreadLocal
    // may be an option.
    private final ObjectMapper jsonMapper =
        new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    // set a limit of 1 million characters on the string as rough guard on invalid input
    private static final int ONE_MILLION = 1024 * 1024;

    @Override
    public boolean test(String value) {
      try {
        if (value.length() > ONE_MILLION) {
          log.error("provided json string length {} is greater than limit of {} for parsing",
              value.length(), ONE_MILLION);
          return false;
        }
        jsonMapper.readTree(value);
        return true;
      } catch (IOException e) {
        log.error("provided json string resulted in an error", e);
        return false;
      }
    }
  }

  private static class ValidVolumes implements Predicate<String> {
    private static final Logger log = LoggerFactory.getLogger(ValidVolumes.class);

    @Override
    public boolean test(String volumes) {
      if (volumes == null) {
        return false;
      }
      try {
        ConfigurationTypeHelper.getVolumeUris(volumes);
        return true;
      } catch (IllegalArgumentException e) {
        log.error("provided volume string is not valid", e);
        return false;
      }
    }
  }

  private static Predicate<String> in(final boolean caseSensitive, final String... allowedSet) {
    if (caseSensitive) {
      return x -> Arrays.stream(allowedSet)
          .anyMatch(y -> (x == null && y == null) || (x != null && x.equals(y)));
    } else {
      Function<String,String> toLower = x -> x == null ? null : x.toLowerCase();
      return x -> Arrays.stream(allowedSet).map(toLower)
          .anyMatch(y -> (x == null && y == null) || (x != null && toLower.apply(x).equals(y)));
    }
  }

  private static Predicate<String> boundedUnits(final long lowerBound, final long upperBound,
      final boolean caseSensitive, final String... suffixes) {
    Predicate<String> suffixCheck = new HasSuffix(caseSensitive, suffixes);
    return x -> x == null
        || (suffixCheck.test(x) && new Bounds(lowerBound, upperBound).test(stripUnits.apply(x)));
  }

  private static class ValidCompressionType implements Predicate<String> {

    private static final Logger LOG = LoggerFactory.getLogger(ValidCompressionType.class);

    @Override
    public boolean test(String type) {
      if (!Compression.getSupportedAlgorithms().contains(type)) {
        return false;
      }
      CompressionAlgorithm ca = Compression.getCompressionAlgorithmByName(type);
      Compressor c = null;
      try {
        c = ca.getCompressor();
        return true;
      } catch (RuntimeException e) {
        LOG.error("Error creating compressor for type {}", type, e);
        return false;
      } finally {
        if (c != null) {
          ca.returnCompressor(c);
        }
      }
    }

  }

  private static final Pattern SUFFIX_REGEX = Pattern.compile("\\D*$"); // match non-digits at end
  private static final Function<String,String> stripUnits =
      x -> x == null ? null : SUFFIX_REGEX.matcher(x.trim()).replaceAll("");

  private static class HasSuffix implements Predicate<String> {

    private final Predicate<String> p;

    public HasSuffix(final boolean caseSensitive, final String... suffixes) {
      p = in(caseSensitive, suffixes);
    }

    @Override
    public boolean test(final String input) {
      requireNonNull(input);
      Matcher m = SUFFIX_REGEX.matcher(input);
      if (m.find()) {
        if (m.groupCount() != 0) {
          throw new AssertionError(m.groupCount());
        }
        return p.test(m.group());
      } else {
        return true;
      }
    }
  }

  private static class FractionPredicate implements Predicate<String> {
    @Override
    public boolean test(final String input) {
      if (input == null) {
        return true;
      }
      try {
        double d;
        if (!input.isEmpty() && input.charAt(input.length() - 1) == '%') {
          d = Double.parseDouble(input.substring(0, input.length() - 1));
        } else {
          d = Double.parseDouble(input);
        }
        return d >= 0;
      } catch (NumberFormatException e) {
        return false;
      }
    }
  }

  private static class Bounds implements Predicate<String> {

    private final long lowerBound;
    private final long upperBound;
    private final boolean lowerInclusive;
    private final boolean upperInclusive;

    public Bounds(final long lowerBound, final long upperBound) {
      this(lowerBound, true, upperBound, true);
    }

    public Bounds(final long lowerBound, final boolean lowerInclusive, final long upperBound,
        final boolean upperInclusive) {
      this.lowerBound = lowerBound;
      this.lowerInclusive = lowerInclusive;
      this.upperBound = upperBound;
      this.upperInclusive = upperInclusive;
    }

    @Override
    public boolean test(final String input) {
      if (input == null) {
        return true;
      }
      long number;
      try {
        number = Long.parseLong(input);
      } catch (NumberFormatException e) {
        return false;
      }
      if (number < lowerBound || (!lowerInclusive && number == lowerBound)) {
        return false;
      }
      return number <= upperBound && (upperInclusive || number != upperBound);
    }

  }

  private static class Matches implements Predicate<String> {

    protected final Pattern pattern;

    public Matches(final String pattern) {
      this(pattern, Pattern.DOTALL);
    }

    public Matches(final String pattern, int flags) {
      this(Pattern.compile(requireNonNull(pattern), flags));
    }

    public Matches(final Pattern pattern) {
      requireNonNull(pattern);
      this.pattern = pattern;
    }

    @Override
    public boolean test(final String input) {
      // TODO when the input is null, it just means that the property wasn't set
      // we can add checks for not null for required properties with
      // Predicates.and(Predicates.notNull(), ...),
      // or we can stop assuming that null is always okay for a Matches predicate, and do that
      // explicitly with Predicates.or(Predicates.isNull(), ...)
      return input == null || pattern.matcher(input).matches();
    }

  }

  public static class PortRange extends Matches {

    public static final Range<Integer> VALID_RANGE = Range.of(1024, 65535);

    public PortRange(final String pattern) {
      super(pattern);
    }

    @Override
    public boolean test(final String input) {
      if (super.test(input)) {
        try {
          PortRange.parse(input);
          return true;
        } catch (IllegalArgumentException e) {
          return false;
        }
      } else {
        return false;
      }
    }

    public static IntStream parse(String portRange) {
      int idx = portRange.indexOf('-');
      if (idx != -1) {
        int low = Integer.parseInt(portRange.substring(0, idx));
        int high = Integer.parseInt(portRange.substring(idx + 1));
        if (!VALID_RANGE.contains(low) || !VALID_RANGE.contains(high) || low > high) {
          throw new IllegalArgumentException(
              "Invalid port range specified, only 1024 to 65535 supported.");
        }
        return IntStream.rangeClosed(low, high);
      }
      throw new IllegalArgumentException(
          "Invalid port range specification, must use M-N notation.");
    }

  }

  private static class ValidFateConfig implements Predicate<String> {
    private static final Logger log = LoggerFactory.getLogger(ValidFateConfig.class);
    private final Set<Fate.FateOperation> allFateOps;
    private final String name;

    private ValidFateConfig(Set<Fate.FateOperation> allFateOps, String name) {
      this.allFateOps = allFateOps;
      this.name = name;
    }

    @Override
    public boolean test(String s) {
      final Set<Fate.FateOperation> seenFateOps;

      try {
        final var json = JsonParser.parseString(s).getAsJsonObject();
        seenFateOps = new HashSet<>();

        for (var entry : json.entrySet()) {
          var key = entry.getKey();
          var val = entry.getValue().getAsInt();
          if (val <= 0) {
            log.warn(
                "Invalid entry {} in {}. Must be a valid thread pool size. Property was unchanged.",
                entry, name);
            return false;
          }
          var fateOpsStrArr = key.split(",");
          for (String fateOpStr : fateOpsStrArr) {
            Fate.FateOperation fateOp = Fate.FateOperation.valueOf(fateOpStr);
            if (seenFateOps.contains(fateOp)) {
              log.warn("Duplicate fate operation {} seen in {}. Property was unchanged.", fateOp,
                  name);
              return false;
            }
            seenFateOps.add(fateOp);
          }
        }
      } catch (Exception e) {
        log.warn("Exception from attempting to set {}. Property was unchanged.", name, e);
        return false;
      }

      var allFateOpsSeen = allFateOps.equals(seenFateOps);
      if (!allFateOpsSeen) {
        log.warn(
            "Not all fate operations found in {}. Expected to see {} but saw {}. Property was unchanged.",
            name, allFateOps, seenFateOps);
      }
      return allFateOpsSeen;
    }
  }

  private static class ValidUserFateConfig extends ValidFateConfig {
    private static final String NAME = "fate user config";

    private ValidUserFateConfig() {
      super(Fate.FateOperation.getAllUserFateOps(), NAME);
    }
  }

  private static class ValidMetaFateConfig extends ValidFateConfig {
    private static final String NAME = "fate meta config";

    private ValidMetaFateConfig() {
      super(Fate.FateOperation.getAllMetaFateOps(), NAME);
    }
  }

  private static class FateThreadPoolSize implements Predicate<String> {
    private static final Logger log = LoggerFactory.getLogger(FateThreadPoolSize.class);

    @Override
    public boolean test(String s) {
      log.warn(
          "The manager fate thread pool size property is no longer used. See the {} and {} for "
              + "the replacements to this property.",
          ValidUserFateConfig.NAME, ValidMetaFateConfig.NAME);
      return true;
    }
  }

}

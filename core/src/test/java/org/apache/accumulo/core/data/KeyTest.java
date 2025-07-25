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
package org.apache.accumulo.core.data;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.accumulo.core.dataImpl.thrift.TKey;
import org.apache.accumulo.core.dataImpl.thrift.TKeyValue;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;

public class KeyTest {

  @Test
  public void testDeletedCompare() {
    final byte[] row = "r1".getBytes(UTF_8);
    final byte[] cf = "cf".getBytes(UTF_8);
    final byte[] cq = "cq".getBytes(UTF_8);
    Key k1 = new Key(row, cf, cq, new byte[0], 0, false);
    Key k2 = new Key(row, cf, cq, new byte[0], 0, false);
    Key k3 = new Key(row, cf, cq, new byte[0], 0, true);
    Key k4 = new Key(row, cf, cq, new byte[0], 0, true);

    assertEquals(k1, k2);
    assertEquals(k3, k4);
    assertTrue(k1.compareTo(k3) > 0);
    assertTrue(k3.compareTo(k1) < 0);
  }

  @Test
  public void testCopyData() {
    byte[] row = "r".getBytes(UTF_8);
    byte[] cf = "cf".getBytes(UTF_8);
    byte[] cq = "cq".getBytes(UTF_8);
    byte[] cv = "cv".getBytes(UTF_8);

    Key k1 = new Key(row, cf, cq, cv, 5L, false, false);
    Key k2 = new Key(row, cf, cq, cv, 5L, false, true);

    assertSame(row, k1.getRowBytes());
    assertSame(cf, k1.getColFamily());
    assertSame(cq, k1.getColQualifier());
    assertSame(cv, k1.getColVisibility());

    assertSame(row, k1.getRowData().getBackingArray());
    assertSame(cf, k1.getColumnFamilyData().getBackingArray());
    assertSame(cq, k1.getColumnQualifierData().getBackingArray());
    assertSame(cv, k1.getColumnVisibilityData().getBackingArray());

    assertNotSame(row, k2.getRowBytes());
    assertNotSame(cf, k2.getColFamily());
    assertNotSame(cq, k2.getColQualifier());
    assertNotSame(cv, k2.getColVisibility());

    assertNotSame(row, k2.getRowData().getBackingArray());
    assertNotSame(cf, k2.getColumnFamilyData().getBackingArray());
    assertNotSame(cq, k2.getColumnQualifierData().getBackingArray());
    assertNotSame(cv, k2.getColumnVisibilityData().getBackingArray());

    assertEquals(k1, k2);

  }

  @Test
  public void testCopyDataWithByteArrayConstructors() {
    byte[] row = "r".getBytes(UTF_8);
    byte[] cf = "cf".getBytes(UTF_8);
    byte[] cq = "cq".getBytes(UTF_8);
    byte[] cv = "cv".getBytes(UTF_8);
    byte[] empty = "".getBytes(UTF_8);

    Key kRow = new Key(row);
    Key kRowcolFam = new Key(row, cf);
    Key kRowcolFamColQual = new Key(row, cf, cq);
    Key kRowcolFamColQualColVis = new Key(row, cf, cq, cv);
    Key kRowcolFamColQualColVisTimeStamp = new Key(row, cf, cq, cv, 5L);

    // test row constructor
    assertNotSameByteArray(kRow, row, empty, empty, empty);

    // test row, column family constructor
    assertNotSameByteArray(kRowcolFam, row, cf, empty, empty);

    // test row, column family, column qualifier constructor
    assertNotSameByteArray(kRowcolFamColQual, row, cf, cq, empty);

    // test row, column family, column qualifier, column visibility constructor
    assertNotSameByteArray(kRowcolFamColQualColVis, row, cf, cq, cv);

    // test row, column family, column qualifier, column visibility, timestamp constructor
    assertNotSameByteArray(kRowcolFamColQualColVisTimeStamp, row, cf, cq, cv);
  }

  private void assertNotSameByteArray(Key key, byte[] row, byte[] cf, byte[] cq, byte[] cv) {
    if (key.getRowBytes().length != 0) {
      assertNotSame(row, key.getRowBytes());
      assertNotSame(row, key.getRowData().getBackingArray());
      assertArrayEquals(row, key.getRowBytes());

    }
    if (key.getColFamily().length != 0) {
      assertNotSame(cf, key.getColFamily());
      assertNotSame(cf, key.getColumnFamilyData().getBackingArray());
      assertArrayEquals(cf, key.getColFamily());

    }
    if (key.getColQualifier().length != 0) {
      assertNotSame(cq, key.getColQualifier());
      assertNotSame(cq, key.getColumnQualifierData().getBackingArray());
      assertArrayEquals(cq, key.getColQualifier());

    }
    if (key.getColVisibility().length != 0) {
      assertNotSame(cv, key.getColVisibility());
      assertNotSame(cv, key.getColumnVisibilityData().getBackingArray());
      assertArrayEquals(cv, key.getColVisibility());
    }
  }

  @Test
  public void testTextConstructorByteArrayConversion() {
    Text rowText = new Text("r");
    Text cfText = new Text("cf");
    Text cqText = new Text("cq");
    Text cvText = new Text("cv");

    // make Keys from Text parameters
    Key kRow = new Key(rowText);
    Key kRowColFam = new Key(rowText, cfText);
    Key kRowColFamColQual = new Key(rowText, cfText, cqText);
    Key kRowColFamColQualColVis = new Key(rowText, cfText, cqText, cvText);
    Key kRowColFamColQualColVisTimeStamp = new Key(rowText, cfText, cqText, cvText, 5L);

    // test row constructor
    assertTextValueConversionToByteArray(kRow);

    // test row, column family constructor
    assertTextValueConversionToByteArray(kRowColFam);

    // test row, column family, column qualifier constructor
    assertTextValueConversionToByteArray(kRowColFamColQual);

    // test row, column family, column qualifier, column visibility constructor
    assertTextValueConversionToByteArray(kRowColFamColQualColVis);

    // test row, column family, column qualifier, column visibility, timestamp constructor
    assertTextValueConversionToByteArray(kRowColFamColQualColVisTimeStamp);
  }

  private void assertTextValueConversionToByteArray(Key key) {
    byte[] row = "r".getBytes(UTF_8);
    byte[] cf = "cf".getBytes(UTF_8);
    byte[] cq = "cq".getBytes(UTF_8);
    byte[] cv = "cv".getBytes(UTF_8);
    // show Text values submitted in constructor
    // are converted to byte array containing
    // the same value
    if (key.getRowBytes().length != 0) {
      assertArrayEquals(row, key.getRowBytes());
    }
    if (key.getColFamily().length != 0) {
      assertArrayEquals(cf, key.getColFamily());
    }
    if (key.getColQualifier().length != 0) {
      assertArrayEquals(cq, key.getColQualifier());
    }
    if (key.getColVisibility().length != 0) {
      assertArrayEquals(cv, key.getColVisibility());
    }
  }

  @Test
  public void testString() {
    Key k1 = new Key("r1");
    Key k2 = new Key(new Text("r1"));
    assertEquals(k2, k1);

    k1 = new Key("r1", "cf1");
    k2 = new Key(new Text("r1"), new Text("cf1"));
    assertEquals(k2, k1);

    k1 = new Key("r1", "cf2", "cq2");
    k2 = new Key(new Text("r1"), new Text("cf2"), new Text("cq2"));
    assertEquals(k2, k1);

    k1 = new Key("r1", "cf2", "cq2", "cv");
    k2 = new Key(new Text("r1"), new Text("cf2"), new Text("cq2"), new Text("cv"));
    assertEquals(k2, k1);

    k1 = new Key("r1", "cf2", "cq2", "cv", 89);
    k2 = new Key(new Text("r1"), new Text("cf2"), new Text("cq2"), new Text("cv"), 89);
    assertEquals(k2, k1);

    k1 = new Key("r1", "cf2", "cq2", 89);
    k2 = new Key(new Text("r1"), new Text("cf2"), new Text("cq2"), 89);
    assertEquals(k2, k1);

  }

  @Test
  public void testVisibilityFollowingKey() {
    Key k = new Key("r", "f", "q", "v");
    assertEquals(k.followingKey(PartialKey.ROW_COLFAM_COLQUAL_COLVIS).toString(),
        "r f:q [v%00;] " + Long.MAX_VALUE + " false");
  }

  @Test
  public void testVisibilityGetters() {
    Key k = new Key("r", "f", "q", "v1|(v2&v3)");

    Text expression = k.getColumnVisibility();
    ColumnVisibility parsed = k.getColumnVisibilityParsed();

    assertEquals(expression, new Text(parsed.getExpression()));
  }

  @Test
  public void testThrift() {
    Key k = new Key("r1", "cf2", "cq2", "cv");
    TKey tk = k.toThrift();
    Key k2 = new Key(tk);
    assertEquals(k, k2);
  }

  @Test
  public void testThrift_Invalid() {
    Key k = new Key("r1", "cf2", "cq2", "cv");
    TKey tk = k.toThrift();
    tk.setRow((byte[]) null);
    assertThrows(IllegalArgumentException.class, () -> new Key(tk));
  }

  @Test
  public void testCompressDecompress() {
    List<KeyValue> kvs = new ArrayList<>();
    kvs.add(new KeyValue(new Key(), new byte[] {}));
    kvs.add(new KeyValue(new Key("r"), new byte[] {}));
    kvs.add(new KeyValue(new Key("r", "cf"), new byte[] {}));
    kvs.add(new KeyValue(new Key("r2", "cf"), new byte[] {}));
    kvs.add(new KeyValue(new Key("r", "cf", "cq"), new byte[] {}));
    kvs.add(new KeyValue(new Key("r2", "cf2", "cq"), new byte[] {}));
    kvs.add(new KeyValue(new Key("r", "cf", "cq", "cv"), new byte[] {}));
    kvs.add(new KeyValue(new Key("r2", "cf2", "cq2", "cv"), new byte[] {}));
    kvs.add(new KeyValue(new Key("r2", "cf2", "cq2", "cv"), new byte[] {}));
    kvs.add(new KeyValue(new Key(), new byte[] {}));

    List<TKeyValue> tkvs = Key.compress(kvs);
    Key.decompress(tkvs);

    assertEquals(kvs.size(), tkvs.size());
    Iterator<KeyValue> kvi = kvs.iterator();
    Iterator<TKeyValue> tkvi = tkvs.iterator();

    while (kvi.hasNext()) {
      KeyValue kv = kvi.next();
      TKeyValue tkv = tkvi.next();
      assertEquals(kv.getKey(), new Key(tkv.getKey()));
    }
  }

  @Test
  public void testBytesText() {
    byte[] row = {1};
    Key bytesRowKey = new Key(row);
    Key textRowKey = new Key(new Text(row));
    assertEquals(bytesRowKey, textRowKey);

    byte[] colFamily = {0, 1};
    Key bytesColFamilyKey = new Key(row, colFamily);
    Key textColFamilyKey = new Key(new Text(row), new Text(colFamily));
    assertEquals(bytesColFamilyKey, textColFamilyKey);

    byte[] colQualifier = {0, 0, 1};
    Key bytesColQualifierKey = new Key(row, colFamily, colQualifier);
    Key textColQualifierKey = new Key(new Text(row), new Text(colFamily), new Text(colQualifier));
    assertEquals(bytesColQualifierKey, textColQualifierKey);

    byte[] colVisibility = {0, 0, 0, 1};
    Key bytesColVisibilityKey = new Key(row, colFamily, colQualifier, colVisibility);
    Key textColVisibilityKey = new Key(new Text(row), new Text(colFamily), new Text(colQualifier),
        new Text(colVisibility));
    assertEquals(bytesColVisibilityKey, textColVisibilityKey);

    long ts = 0L;
    Key bytesTSKey = new Key(row, colFamily, colQualifier, colVisibility, ts);
    Key textTSKey = new Key(new Text(row), new Text(colFamily), new Text(colQualifier),
        new Text(colVisibility), ts);
    assertEquals(bytesTSKey, textTSKey);

    Key bytesTSKey2 = new Key(row, ts);
    Key textTSKey2 = new Key(new Text(row), ts);
    assertEquals(bytesTSKey2, textTSKey2);

    Key bytesTSKey3 = new Key(row, colFamily, colQualifier, ts);
    Key testTSKey3 = new Key(new Text(row), new Text(colFamily), new Text(colQualifier), ts);
    assertEquals(bytesTSKey3, testTSKey3);

    ColumnVisibility colVisibility2 = new ColumnVisibility("v1");
    Key bytesColVisibilityKey2 = new Key(row, colFamily, colQualifier, colVisibility2, ts);
    Key textColVisibilityKey2 =
        new Key(new Text(row), new Text(colFamily), new Text(colQualifier), colVisibility2, ts);
    assertEquals(bytesColVisibilityKey2, textColVisibilityKey2);
  }

  private static class TestByteSequence extends ArrayByteSequence {

    private static final long serialVersionUID = 1234L;

    public TestByteSequence(String s) {
      super(s);
    }

    @Override
    public boolean isBackedByArray() {
      return false;
    }
  }

  @Test
  public void testByteSequenceConstructor() {
    var row1 = new ArrayByteSequence("Row");
    var row2 = new ArrayByteSequence("TheRowData").subSequence(3, 6);
    var row3 = new TestByteSequence("Row");

    var fam1 = new ArrayByteSequence("Family");
    var fam2 = new ArrayByteSequence("SomeFamilyData").subSequence(4, 10);
    var fam3 = new TestByteSequence("Family");

    var qual1 = new ArrayByteSequence("Qual");
    var qual2 = new ArrayByteSequence("TheQualData").subSequence(3, 7);
    var qual3 = new TestByteSequence("Qual");

    var vis1 = new ArrayByteSequence("Vis");
    var vis2 = new ArrayByteSequence("AVisData").subSequence(1, 4);
    var vis3 = new TestByteSequence("Vis");

    var expectedKey = new Key("Row", "Family", "Qual", "Vis", 4);

    for (var r : List.of(row1, row2, row3)) {
      for (var f : List.of(fam1, fam2, fam3)) {
        for (var q : List.of(qual1, qual2, qual3)) {
          for (var v : List.of(vis1, vis2, vis3)) {
            var actualKey = new Key(r, f, q, v, 4);
            assertEquals(expectedKey, actualKey);
            var actualKey2 =
                Key.builder().row(r).family(f).qualifier(q).visibility(v).timestamp(4).build();
            assertEquals(expectedKey, actualKey2);
          }
        }
      }
    }

  }

  @Test
  public void testHashCode() {
    // Test consistency
    Key key = new Key("r1".getBytes(UTF_8), "cf".getBytes(UTF_8), "cq".getBytes(UTF_8),
        "cv".getBytes(UTF_8), 0, false);
    int hashCode1 = key.hashCode();
    int hashCode2 = key.hashCode();
    assertEquals(hashCode1, hashCode2);

    // Testing equality
    Key k1 = new Key("r1".getBytes(UTF_8), "cf".getBytes(UTF_8), "cq".getBytes(UTF_8),
        "cv".getBytes(UTF_8), 0, false);
    Key k2 = new Key("r1".getBytes(UTF_8), "cf".getBytes(UTF_8), "cq".getBytes(UTF_8),
        "cv".getBytes(UTF_8), 0, false);
    assertEquals(k1.hashCode(), k2.hashCode());

    // Testing even distribution
    List<Key> keys = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      keys.add(new Key(("r1" + i).getBytes(UTF_8), ("cf" + i).getBytes(UTF_8),
          ("cq" + i).getBytes(UTF_8), ("cv" + i).getBytes(UTF_8), 0, false));
    }
    Set<Integer> hashCodes = new HashSet<>();
    for (Key k : keys) {
      hashCodes.add(k.hashCode());
    }
    assertEquals(keys.size(), hashCodes.size(), 10);
  }
}

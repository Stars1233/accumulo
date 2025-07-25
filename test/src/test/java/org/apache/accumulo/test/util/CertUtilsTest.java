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
package org.apache.accumulo.test.util;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.Certificate;

import org.apache.accumulo.harness.WithTestNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class CertUtilsTest extends WithTestNames {
  private static final String KEYSTORE_TYPE = "JKS";
  private static final String PASSWORD = "CertUtilsTestPassword";
  private static final char[] PASSWORD_CHARS = PASSWORD.toCharArray();
  private static final String RDN_STRING = "o=Apache Accumulo,cn=CertUtilsTest";

  @TempDir
  private static Path tempDir;

  private CertUtils getUtils() {
    return new CertUtils(KEYSTORE_TYPE, RDN_STRING, "RSA", 4096, "SHA512WITHRSA");
  }

  @SuppressFBWarnings(value = "HARD_CODE_PASSWORD", justification = "test password is okay")
  @Test
  public void createSelfSigned() throws Exception {
    CertUtils certUtils = getUtils();
    Path tempSubDir = tempDir.resolve(testName());
    if (!Files.isDirectory(tempSubDir)) {
      Files.createDirectories(tempSubDir);
    }
    Path keyStoreFile = tempSubDir.resolve("selfsigned.jks");
    certUtils.createSelfSignedCert(keyStoreFile.toFile(), "test", PASSWORD);

    KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
    try (InputStream fis = Files.newInputStream(keyStoreFile)) {
      keyStore.load(fis, PASSWORD_CHARS);
    }
    Certificate cert = CertUtils.findCert(keyStore);

    cert.verify(cert.getPublicKey()); // throws exception if it can't be verified
  }

  @Test
  public void createPublicSelfSigned() throws Exception {
    CertUtils certUtils = getUtils();
    Path tempSubDir = tempDir.resolve(testName());
    if (!Files.isDirectory(tempSubDir)) {
      Files.createDirectories(tempSubDir);
    }
    Path rootKeyStoreFile = tempSubDir.resolve("root.jks");
    certUtils.createSelfSignedCert(rootKeyStoreFile.toFile(), "test", PASSWORD);
    Path publicKeyStoreFile = tempSubDir.resolve("public.jks");
    certUtils.createPublicCert(publicKeyStoreFile.toFile(), "test", rootKeyStoreFile.toString(),
        PASSWORD, "");

    KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
    try (InputStream fis = Files.newInputStream(publicKeyStoreFile)) {
      keyStore.load(fis, new char[0]);
    }
    var e = assertThrows(KeyStoreException.class,
        () -> CertUtils.findPrivateKey(keyStore, PASSWORD_CHARS),
        "expected not to find private key in keystore");
    assertTrue(e.getMessage().contains("private key"));
    Certificate cert = CertUtils.findCert(keyStore);
    cert.verify(cert.getPublicKey()); // throws exception if it can't be verified
  }

  @SuppressFBWarnings(value = "HARD_CODE_PASSWORD", justification = "test password is okay")
  @Test
  public void createSigned() throws Exception {
    CertUtils certUtils = getUtils();
    Path tempSubDir = tempDir.resolve(testName());
    if (!Files.isDirectory(tempSubDir)) {
      Files.createDirectories(tempSubDir);
    }
    Path rootKeyStoreFile = tempSubDir.resolve("root.jks");
    certUtils.createSelfSignedCert(rootKeyStoreFile.toFile(), "test", PASSWORD);
    Path signedKeyStoreFile = tempSubDir.resolve("signed.jks");
    certUtils.createSignedCert(signedKeyStoreFile.toFile(), "test", PASSWORD,
        rootKeyStoreFile.toString(), PASSWORD);

    KeyStore rootKeyStore = KeyStore.getInstance(KEYSTORE_TYPE);
    try (InputStream fis = Files.newInputStream(rootKeyStoreFile)) {
      rootKeyStore.load(fis, PASSWORD_CHARS);
    }
    Certificate rootCert = CertUtils.findCert(rootKeyStore);

    KeyStore signedKeyStore = KeyStore.getInstance(KEYSTORE_TYPE);
    try (InputStream fis = Files.newInputStream(signedKeyStoreFile)) {
      signedKeyStore.load(fis, PASSWORD_CHARS);
    }

    Certificate signedCert = CertUtils.findCert(signedKeyStore);
    PublicKey pubKey = signedCert.getPublicKey();
    assertThrows(SignatureException.class, () -> signedCert.verify(pubKey),
        "signed cert should not be able to verify itself");

    signedCert.verify(rootCert.getPublicKey()); // throws exception if it can't be verified
  }

  @Test
  public void publicOnlyVerfication() throws Exception {
    // this approximates the real life scenario. the client will only have the public key of each
    // cert (the root made by us as below, but the signed cert extracted by the SSL transport)
    CertUtils certUtils = getUtils();
    Path tempSubDir = tempDir.resolve(testName());
    if (!Files.isDirectory(tempSubDir)) {
      Files.createDirectories(tempSubDir);
    }
    Path rootKeyStoreFile = tempSubDir.resolve("root.jks");
    certUtils.createSelfSignedCert(rootKeyStoreFile.toFile(), "test", PASSWORD);
    Path publicRootKeyStoreFile = tempSubDir.resolve("publicroot.jks");
    certUtils.createPublicCert(publicRootKeyStoreFile.toFile(), "test", rootKeyStoreFile.toString(),
        PASSWORD, "");
    Path signedKeyStoreFile = tempSubDir.resolve("signed.jks");
    certUtils.createSignedCert(signedKeyStoreFile.toFile(), "test", PASSWORD,
        rootKeyStoreFile.toString(), PASSWORD);
    Path publicSignedKeyStoreFile = tempSubDir.resolve("publicsigned.jks");
    certUtils.createPublicCert(publicSignedKeyStoreFile.toFile(), "test",
        signedKeyStoreFile.toString(), PASSWORD, "");

    KeyStore rootKeyStore = KeyStore.getInstance(KEYSTORE_TYPE);
    try (InputStream fis = Files.newInputStream(publicRootKeyStoreFile)) {
      rootKeyStore.load(fis, new char[0]);
    }
    KeyStore signedKeyStore = KeyStore.getInstance(KEYSTORE_TYPE);
    try (InputStream fis = Files.newInputStream(publicSignedKeyStoreFile)) {
      signedKeyStore.load(fis, new char[0]);
    }
    Certificate rootCert = CertUtils.findCert(rootKeyStore);
    Certificate signedCert = CertUtils.findCert(signedKeyStore);
    PublicKey pubKey = signedCert.getPublicKey();

    assertThrows(SignatureException.class, () -> signedCert.verify(pubKey),
        "signed cert should not be able to verify itself");

    signedCert.verify(rootCert.getPublicKey()); // throws exception if it can't be verified
  }

  @SuppressFBWarnings(value = "HARD_CODE_PASSWORD", justification = "test password is okay")
  @Test
  public void signingChain() throws Exception {
    // no reason the keypair we generate for the tservers need to be able to sign anything,
    // but this is a way to make sure the private and public keys created actually correspond.
    CertUtils certUtils = getUtils();
    Path tempSubDir = tempDir.resolve(testName());
    if (!Files.isDirectory(tempSubDir)) {
      Files.createDirectories(tempSubDir);
    }
    Path rootKeyStoreFile = tempSubDir.resolve("root.jks");
    certUtils.createSelfSignedCert(rootKeyStoreFile.toFile(), "test", PASSWORD);
    Path signedCaKeyStoreFile = tempSubDir.resolve("signedca.jks");
    certUtils.createSignedCert(signedCaKeyStoreFile.toFile(), "test", PASSWORD,
        rootKeyStoreFile.toString(), PASSWORD);
    Path signedLeafKeyStoreFile = tempSubDir.resolve("signedleaf.jks");
    certUtils.createSignedCert(signedLeafKeyStoreFile.toFile(), "test", PASSWORD,
        signedCaKeyStoreFile.toString(), PASSWORD);

    KeyStore caKeyStore = KeyStore.getInstance(KEYSTORE_TYPE);
    try (InputStream fis = Files.newInputStream(signedCaKeyStoreFile)) {
      caKeyStore.load(fis, PASSWORD_CHARS);
    }
    Certificate caCert = CertUtils.findCert(caKeyStore);

    KeyStore leafKeyStore = KeyStore.getInstance(KEYSTORE_TYPE);
    try (InputStream fis = Files.newInputStream(signedLeafKeyStoreFile)) {
      leafKeyStore.load(fis, PASSWORD_CHARS);
    }
    Certificate leafCert = CertUtils.findCert(leafKeyStore);

    leafCert.verify(caCert.getPublicKey()); // throws exception if it can't be verified
  }
}

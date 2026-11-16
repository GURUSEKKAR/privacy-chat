package com.secretchat.client.mls;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.bouncycastle.mls.crypto.Secret;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** MLS working group known-answer vectors, independent of this application's round trips. */
class OfficialMlsVectorsTest {
  @Test
  void officialWelcomeAndCommitEpochAuthenticators() throws Exception {
    int tested = 0;
    for (String file :
        new String[] {"passive-client-welcome.json", "passive-client-handling-commit.json"}) {
      try (var input = getClass().getResourceAsStream("/mls/" + file)) {
        var vectors = JsonMapper.builder().build().readTree(input);
        int index = 0;
        for (var v : vectors) {
          int current = index++;
          if (v.get("cipher_suite").asInt() != MlsIdentity.SUITE_ID) continue;
          var suite = MlsIdentity.suite();
          synchronized (suite) {
            var kp =
                ((org.bouncycastle.mls.codec.MLSMessage)
                        org.bouncycastle.mls.codec.MLSInputStream.decode(
                            bytes(v, "key_package"), org.bouncycastle.mls.codec.MLSMessage.class))
                    .keyPackage;
            var welcome =
                ((org.bouncycastle.mls.codec.MLSMessage)
                        org.bouncycastle.mls.codec.MLSInputStream.decode(
                            bytes(v, "welcome"), org.bouncycastle.mls.codec.MLSMessage.class))
                    .welcome;
            org.bouncycastle.mls.TreeKEM.TreeKEMPublicKey tree = null;
            if (!v.get("ratchet_tree").isNull()) {
              tree =
                  (org.bouncycastle.mls.TreeKEM.TreeKEMPublicKey)
                      org.bouncycastle.mls.codec.MLSInputStream.decode(
                          bytes(v, "ratchet_tree"),
                          org.bouncycastle.mls.TreeKEM.TreeKEMPublicKey.class);
              tree.setSuite(suite);
            }
            var psks = new java.util.HashMap<Secret, byte[]>();
            for (var p : v.get("external_psks"))
              psks.put(new Secret(bytes(p, "psk_id")), bytes(p, "psk"));
            var leaf =
                suite
                    .getHPKE()
                    .deserializePrivateKey(
                        bytes(v, "encryption_priv"), kp.getLeafNode().getEncryptionKey());
            var group =
                new org.bouncycastle.mls.protocol.Group(
                    bytes(v, "init_priv"),
                    leaf,
                    bytes(v, "signature_priv"),
                    kp,
                    welcome,
                    tree,
                    psks,
                    new java.util.HashMap<>());
            assertThat(group.getEpochAuthenticator())
                .as(file + " vector " + current + " initial")
                .isEqualTo(bytes(v, "initial_epoch_authenticator"));
            for (var epoch : v.get("epochs")) {
              for (var proposal : epoch.get("proposals")) {
                group.handle(HexFormat.of().parseHex(proposal.asString()), null);
              }
              group = group.handle(bytes(epoch, "commit"), null);
              assertThat(group).isNotNull();
              assertThat(group.getEpochAuthenticator())
                  .as(file + " vector " + current + " epoch " + group.getEpoch())
                  .isEqualTo(bytes(epoch, "epoch_authenticator"));
            }
          }
          tested++;
        }
      }
    }
    assertThat(tested).isGreaterThanOrEqualTo(20);
    System.out.println("Official suite 1 passive-client Welcome/commit cases verified: " + tested);
  }

  private byte[] bytes(JsonNode node, String key) {
    return HexFormat.of().parseHex(node.get(key).asString());
  }

  @Test
  void supportedSuiteMatchesOfficialCryptoBasics() throws Exception {
    try (var input = getClass().getResourceAsStream("/mls/crypto-basics.json")) {
      var vectors = JsonMapper.builder().build().readTree(input);
      int tested = 0;
      for (var v : vectors) {
        if (v.get("cipher_suite").asInt() != MlsIdentity.SUITE_ID) continue;
        var suite = MlsIdentity.suite();
        synchronized (suite) {
          var r = v.get("ref_hash");
          assertThat(suite.refHash(bytes(r, "value"), r.get("label").asString()))
              .isEqualTo(bytes(r, "out"));
          var e = v.get("expand_with_label");
          assertThat(
                  new Secret(bytes(e, "secret"))
                      .expandWithLabel(
                          suite,
                          e.get("label").asString(),
                          bytes(e, "context"),
                          e.get("length").asInt())
                      .value())
              .isEqualTo(bytes(e, "out"));
          var d = v.get("derive_secret");
          assertThat(
                  new Secret(bytes(d, "secret"))
                      .deriveSecret(suite, d.get("label").asString())
                      .value())
              .isEqualTo(bytes(d, "out"));
          var t = v.get("derive_tree_secret");
          assertThat(
                  new Secret(bytes(t, "secret"))
                      .deriveTreeSecret(
                          suite,
                          t.get("label").asString(),
                          (int) t.get("generation").asLong(),
                          t.get("length").asInt())
                      .value())
              .isEqualTo(bytes(t, "out"));
          var s = v.get("sign_with_label");
          assertThat(
                  suite.signWithLabel(
                      bytes(s, "priv"), s.get("label").asString(), bytes(s, "content")))
              .isEqualTo(bytes(s, "signature"));
          assertThat(
                  suite.verifyWithLabel(
                      bytes(s, "pub"),
                      s.get("label").asString(),
                      bytes(s, "content"),
                      bytes(s, "signature")))
              .isTrue();
          var h = v.get("encrypt_with_label");
          assertThat(
                  suite.decryptWithLabel(
                      bytes(h, "priv"),
                      h.get("label").asString(),
                      bytes(h, "context"),
                      bytes(h, "kem_output"),
                      bytes(h, "ciphertext")))
              .isEqualTo(bytes(h, "plaintext"));
        }
        tested++;
      }
      assertThat(tested).isPositive();
    }
  }
}

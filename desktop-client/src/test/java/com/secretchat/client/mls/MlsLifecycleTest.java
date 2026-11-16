package com.secretchat.client.mls;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MlsLifecycleTest {
  @Test
  void rosterAndDeviceIdentityDisagreementAreRejected() {
    var a = device();
    var group = a.createGroup(UUID.randomUUID());
    var expected =
        new com.secretchat.protocol.RoomDtos.MemberView(
            a.userId(),
            "alice",
            "OWNER",
            "ACTIVE",
            java.time.Instant.now(),
            0,
            a.identity().fingerprint());
    GroupRosterVerifier.verify(group, List.of(expected));
    assertThatThrownBy(() -> GroupRosterVerifier.verify(group, List.of()))
        .isInstanceOf(MlsException.class);
    assertThatThrownBy(() -> GroupRosterVerifier.verify(group, List.of(expected, expected)))
        .isInstanceOf(MlsException.class);
    var substituted =
        new com.secretchat.protocol.RoomDtos.MemberView(
            a.userId(),
            "alice",
            "OWNER",
            "ACTIVE",
            java.time.Instant.now(),
            0,
            MlsIdentity.generate().fingerprint());
    assertThatThrownBy(() -> GroupRosterVerifier.verify(group, List.of(substituted)))
        .isInstanceOf(MlsException.class);
  }

  @Test
  void forgettingDropsGroupStateAndWipesDevicePrivateKeyBytes() {
    var identity = MlsIdentity.generate();
    var engine = new MlsEngine(identity, UUID.randomUUID());
    var room = UUID.randomUUID();
    engine.createGroup(room);
    engine.forget(room);
    assertThat(engine.group(room)).isNull();
    identity.destroy();
    assertThat(identity.exportPrivateForVault()).containsOnly((byte) 0);
  }

  @Test
  void newKeyPackagePublicationPreservesAnOutstandingWelcome() {
    var a = device();
    var b = device();
    var ga = a.createGroup(UUID.randomUUID());
    var first =
        MlsEngine.decodeKeyPackage(Base64.getDecoder().decode(b.freshKeyPackages(1).getFirst()));
    var prepared = ga.prepareAdd(List.of(first));
    ga.applyOwnCommit(prepared);
    b.freshKeyPackages(1);
    var gb = b.join(ga.roomId(), prepared.welcome());
    assertThat(gb.decrypt(ga.encrypt(new byte[0], new byte[] {42})).plaintext())
        .containsExactly(42);
  }

  private MlsEngine device() {
    return new MlsEngine(MlsIdentity.generate(), UUID.randomUUID());
  }

  private RoomGroup add(RoomGroup owner, MlsEngine member) {
    var kp =
        MlsEngine.decodeKeyPackage(
            Base64.getDecoder().decode(member.freshKeyPackages(1).getFirst()));
    var commit = owner.prepareAdd(List.of(kp));
    owner.applyOwnCommit(commit);
    return member.join(owner.roomId(), commit.welcome());
  }

  @Test
  void welcomePrivateMessagesLateJoinAndRemoval() {
    var a = device();
    var b = device();
    var c = device();
    var ga = a.createGroup(UUID.randomUUID());
    var gb = add(ga, b);
    assertThat(gb.epoch()).isEqualTo(ga.epoch());
    byte[] old = ga.encrypt(new byte[0], "before C joined".getBytes(StandardCharsets.UTF_8));
    var decoded = gb.decrypt(old);
    assertThat(decoded.senderUserId()).isEqualTo(a.userId());
    assertThat(new String(decoded.plaintext(), StandardCharsets.UTF_8))
        .isEqualTo("before C joined");
    assertThatThrownBy(() -> gb.decrypt(old)).isInstanceOf(MlsException.class);

    var kp =
        MlsEngine.decodeKeyPackage(Base64.getDecoder().decode(c.freshKeyPackages(1).getFirst()));
    var addC = ga.prepareAdd(List.of(kp));
    ga.applyOwnCommit(addC);
    gb.handleCommit(addC.commit(), ga.epoch());
    var gc = c.join(ga.roomId(), addC.welcome());
    assertThatThrownBy(() -> gc.decrypt(old)).isInstanceOf(MlsException.class);
    byte[] current = ga.encrypt(new byte[0], new byte[] {1, 2, 3});
    assertThat(gb.decrypt(current).plaintext()).containsExactly(1, 2, 3);
    assertThat(gc.decrypt(current).plaintext()).containsExactly(1, 2, 3);

    var remove = ga.prepareRemove(List.of(b.userId()));
    ga.applyOwnCommit(remove);
    gc.handleCommit(remove.commit(), ga.epoch());
    byte[] after = ga.encrypt(new byte[0], new byte[] {4, 5, 6});
    assertThat(gc.decrypt(after).plaintext()).containsExactly(4, 5, 6);
    assertThatThrownBy(() -> gb.decrypt(after)).isInstanceOf(MlsException.class);
    assertThat(ga.roster().values()).containsExactlyInAnyOrder(a.userId(), c.userId());
  }

  @Test
  void tamperedMessageAndWrongWelcomeAreRejected() {
    var a = device();
    var b = device();
    var ga = a.createGroup(UUID.randomUUID());
    var gb = add(ga, b);
    byte[] message = ga.encrypt(new byte[0], new byte[] {42});
    message[message.length - 1] ^= 1;
    assertThatThrownBy(() -> gb.decrypt(message)).isInstanceOf(MlsException.class);
    var stranger = device();
    stranger.freshKeyPackages(1);
    var commit = ga.prepareUpdate();
    assertThatThrownBy(() -> gb.handleCommit(commit.commit(), ga.epoch() + 2))
        .isInstanceOf(MlsException.class);
  }
}

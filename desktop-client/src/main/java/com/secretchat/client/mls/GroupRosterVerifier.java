package com.secretchat.client.mls;

import com.secretchat.protocol.Fingerprints;
import com.secretchat.protocol.RoomDtos.MemberView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Fail closed if active membership and the authenticated MLS tree disagree at the same epoch. */
public final class GroupRosterVerifier {
  private GroupRosterVerifier() {}

  public static void verify(RoomGroup group, List<MemberView> expected) {
    var roster = group.roster();
    Set<UUID> actual = new HashSet<>(roster.values());
    Set<UUID> listed = new HashSet<>();
    expected.forEach(member -> listed.add(member.userId()));
    if (!actual.equals(listed)
        || roster.size() != actual.size()
        || expected.size() != listed.size())
      throw new MlsException(
          "MLS roster differs from active membership; reset and verify identities before continuing");
    for (var member : expected) {
      if (!Fingerprints.matches(group.fingerprintOf(member.userId()), member.deviceFingerprint()))
        throw new MlsException("MLS identity differs from the enrolled device fingerprint");
    }
  }
}

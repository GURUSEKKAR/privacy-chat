package com.secretchat.crypto_relay;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Opaque, ordered MLS control event (Commit and optional Welcome). {@code epoch} is the epoch the
 * group is in after applying the event. The server cannot open the Welcome.
 */
@Entity
@Table(name = "mls_events")
public class MlsEvent {
  @Id private UUID id;

  @Column(name = "room_id", nullable = false)
  private UUID roomId;

  @Column(nullable = false)
  private int generation;

  @Column(name = "sequence_no", nullable = false)
  private long sequenceNo;

  @Column(nullable = false)
  private long epoch;

  @Column(name = "event_type", nullable = false, length = 16)
  private String eventType;

  @Column(name = "opaque_commit")
  private byte[] opaqueCommit;

  @Column(name = "welcome")
  private byte[] welcome;

  @Column(name = "created_by_device_id", nullable = false)
  private UUID createdByDeviceId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected MlsEvent() {}

  public MlsEvent(
      UUID roomId,
      int generation,
      long sequenceNo,
      long epoch,
      String eventType,
      byte[] commit,
      byte[] welcome,
      UUID byDevice,
      Instant now) {
    this.id = UUID.randomUUID();
    this.roomId = roomId;
    this.generation = generation;
    this.sequenceNo = sequenceNo;
    this.epoch = epoch;
    this.eventType = eventType;
    this.opaqueCommit = commit;
    this.welcome = welcome;
    this.createdByDeviceId = byDevice;
    this.createdAt = now;
  }

  public long getSequenceNo() {
    return sequenceNo;
  }

  public int getGeneration() {
    return generation;
  }

  public long getEpoch() {
    return epoch;
  }

  public String getEventType() {
    return eventType;
  }

  public byte[] getOpaqueCommit() {
    return opaqueCommit;
  }

  public byte[] getWelcome() {
    return welcome;
  }
}

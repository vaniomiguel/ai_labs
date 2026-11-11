package pt.conta.jobs;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** A durable leased task with bounded retries and an idempotency key. */
@Entity
@Table(name = "job")
public class Job extends BaseEntity {
  public String kind;
  public UUID targetId;
  public long targetVersion;
  public String dedupeKey;
  public String state = "QUEUED";
  public int attempts;
  public Instant availableAt = Instant.now();
  public Instant leasedUntil;
  public UUID leaseToken;

  @Column(columnDefinition = "text")
  public String payload = "{}";

  @Column(columnDefinition = "text")
  public String error;
}

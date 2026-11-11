package pt.conta.shared;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Common identity, optimistic version and immutable creation timestamp for persisted records. */
@MappedSuperclass
public abstract class BaseEntity {
  @Id public UUID id = UUID.randomUUID();
  @Version public long version;
  public Instant createdAt = Instant.now();
  public Instant updatedAt = Instant.now();

  /** Records the time at which a persisted revision changed. */
  @PreUpdate
  protected void touch() {
    updatedAt = Instant.now();
  }
}

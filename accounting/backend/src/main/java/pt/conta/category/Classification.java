package pt.conta.category;

import jakarta.persistence.*;
import java.util.UUID;
import pt.conta.shared.BaseEntity;

/** Category decisions have their own optimistic revision, independent of financial facts. */
@Entity
@Table(name = "record_classification")
public class Classification extends BaseEntity {
  public UUID invoiceId;
  public UUID movementId;
  public long generation;
  public String status = "PENDING";
  public String contentHash;
  public String inheritanceHash;
  public String appliedFingerprint;
  @Column(columnDefinition = "text") public String hints = "[]";
  @Column(columnDefinition = "text") public String reason;
}

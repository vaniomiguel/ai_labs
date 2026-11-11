package pt.conta.review;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** An actionable review issue; saving edits does not implicitly resolve it. */
@Entity
@Table(name = "alert")
public class Alert extends BaseEntity {
  public UUID documentId;
  public String entityType;
  public UUID entityId;
  public String code;

  @Column(columnDefinition = "text")
  public String message;

  public String status = "OPEN";

  @Column(columnDefinition = "text")
  public String proposal;

  public String resolvedBy;
  public Instant resolvedAt;
}

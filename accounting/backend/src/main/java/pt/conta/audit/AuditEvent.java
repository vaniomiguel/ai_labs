package pt.conta.audit;

import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** An append-only record of the actor, action and before/after values. */
@Entity
@Table(name = "audit_event")
public class AuditEvent extends BaseEntity {
  public String actor;
  public String action;
  public String entityType;
  public UUID entityId;

  @Column(columnDefinition = "text")
  public String beforeValue;

  @Column(columnDefinition = "text")
  public String afterValue;
}

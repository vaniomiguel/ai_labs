package pt.conta.reconciliation;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** An auditable monetary allocation, optionally reversing an earlier allocation. */
@Entity
@Table(name = "allocation")
public class Allocation extends BaseEntity {
  public UUID invoiceId;
  public UUID movementId;
  public UUID reversesId;

  @Column(precision = 19, scale = 2)
  public BigDecimal amount;

  public String origin;
  public String author;

  @Column(columnDefinition = "text")
  public String evidence;

  public boolean active = true;
}

package pt.conta.ledger;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** An append-only effect or reversal with a business date separate from audit time. */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry extends BaseEntity {
  public String sourceType;
  public UUID sourceId;
  public long sourceVersion;
  public LocalDate effectiveOn;

  @Column(precision = 19, scale = 2)
  public BigDecimal amount;

  public UUID reversesId;

  @Column(columnDefinition = "text")
  public String explanation;
}

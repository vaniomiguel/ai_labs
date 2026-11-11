package pt.conta.bank;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** A source statement whose global integrity gates its movements. */
@Entity
@Table(name = "bank_statement")
public class BankStatement extends BaseEntity {
  public UUID documentId;
  public UUID accountId;
  public LocalDate periodStart;
  public LocalDate periodEnd;

  @Column(precision = 19, scale = 2)
  /** Legacy storage only; excluded from extraction, review and financial validation. */
  public BigDecimal openingBalance;

  @Column(precision = 19, scale = 2)
  /** Legacy storage only; excluded from extraction, review and financial validation. */
  public BigDecimal closingBalance;

  public boolean validated;
  public boolean deleted;
}

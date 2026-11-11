package pt.conta.history;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** A source Excel row used for comparison, never a fabricated bank payment. */
@Entity
@Table(name = "legacy_row")
public class LegacyRow extends BaseEntity {
  public UUID documentId;
  public String sheet;
  public int rowNumber;
  public int year;
  public int month;
  public LocalDate date;

  @Column(columnDefinition = "text")
  public String description;

  @Column(precision = 19, scale = 2)
  public BigDecimal received;

  @Column(precision = 19, scale = 2)
  public BigDecimal payment;

  @Column(precision = 19, scale = 2)
  public BigDecimal balance;

  @Column(columnDefinition = "text")
  public String cells;

  public UUID invoiceId;
  public String status = "PROCESSING";
}

package pt.conta.invoice;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** An individually editable article, retaining unknown values as null. */
@Entity
@Table(name = "invoice_line")
public class InvoiceLine extends BaseEntity {
  public UUID invoiceId;
  public int position;

  @Column(columnDefinition = "text")
  public String description;

  @Column(precision = 19, scale = 6)
  public BigDecimal quantity;

  @Column(precision = 19, scale = 6)
  public BigDecimal unitPrice;

  @Column(precision = 19, scale = 2)
  public BigDecimal net;

  @Column(precision = 19, scale = 2)
  public BigDecimal tax;

  @Column(precision = 19, scale = 2)
  public BigDecimal discount;

  @Column(precision = 19, scale = 2)
  public BigDecimal total;
}

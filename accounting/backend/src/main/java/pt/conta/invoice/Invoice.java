package pt.conta.invoice;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** A business invoice; validation and payment status remain independent. */
@Entity
@Table(name = "invoice")
public class Invoice extends BaseEntity {
  public LocalDate issuedOn;
  public LocalDate dueOn;
  public String number;
  public String supplier;
  public String supplierTaxId;

  @Column(columnDefinition = "text")
  public String description;

  public String currency = "EUR";

  @Column(precision = 19, scale = 2)
  public BigDecimal total;

  @Column(precision = 19, scale = 2)
  public BigDecimal net;

  @Column(precision = 19, scale = 2)
  public BigDecimal tax;

  @Column(precision = 19, scale = 2)
  public BigDecimal discount;

  @Enumerated(EnumType.STRING)
  public InvoiceType type = InvoiceType.INVOICE;

  public UUID relatedInvoiceId;
  public UUID documentId;

  @Enumerated(EnumType.STRING)
  public ReviewStatus reviewStatus = ReviewStatus.OPEN;

  public String origin = "MANUAL";
  public String author;
  public boolean manuallyConfirmed;
  public boolean deleted;
  public boolean excluded;

  @ElementCollection
  @CollectionTable(name = "invoice_category", joinColumns = @JoinColumn(name = "invoice_id"))
  @Column(name = "category_id")
  public Set<UUID> categoryIds = new LinkedHashSet<>();
}

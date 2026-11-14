package pt.conta.bank;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import pt.conta.shared.BaseEntity;
import pt.conta.shared.Types.*;

/** An original bank movement, independent of its accounting classification. */
@Entity
@Table(name = "bank_movement")
public class BankMovement extends BaseEntity {
  @Column(columnDefinition = "text")
  public String fieldProvenance = "{}";

  public UUID documentId;
  public UUID statementId;
  public UUID accountId;
  public String bankReference;
  public LocalDate bookedOn;
  public LocalDate valueOn;
  public LocalTime occurredAt;

  @Column(columnDefinition = "text")
  public String description;

  public String currency = "EUR";

  @Column(precision = 19, scale = 2)
  public BigDecimal amount;

  @Enumerated(EnumType.STRING)
  public Direction direction = Direction.DEBIT;

  @Enumerated(EnumType.STRING)
  public MovementKind kind = MovementKind.UNCLASSIFIED;

  @Enumerated(EnumType.STRING)
  public ReviewStatus reviewStatus = ReviewStatus.OPEN;

  public String origin = "MANUAL";
  public String author;
  public boolean manuallyConfirmed;
  public boolean deleted;
  public boolean excluded;
}

package pt.conta.shared;

/** Closed business vocabularies persisted as names rather than fragile numeric ordinals. */
public final class Types {
  private Types() {}

  public enum ReviewStatus {
    OPEN,
    AUTOMATIC,
    CORRECTED,
    EDITED,
    MANUAL
  }

  public enum InvoiceType {
    INVOICE,
    CREDIT_NOTE
  }

  public enum Direction {
    DEBIT,
    CREDIT
  }

  public enum MovementKind {
    UNCLASSIFIED,
    INVOICE_PAYMENT,
    REFUND,
    REIMBURSEMENT,
    ADVANCE,
    RETURN,
    OTHER,
    INTERNAL_TRANSFER
  }
}

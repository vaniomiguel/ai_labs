package pt.conta.history;

import static pt.conta.shared.Inputs.*;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.document.SourceDocument;
import pt.conta.invoice.Invoice;
import pt.conta.ledger.BookkeepingService;
import pt.conta.reconciliation.*;
import pt.conta.review.ReviewService;
import pt.conta.shared.*;

/** Treats legacy spreadsheets as comparison evidence instead of manufacturing financial records. */
@Service
public class HistoryService {
  private final Store store;
  private final ReviewService review;
  private final BookkeepingService book;

  public HistoryService(Store store, ReviewService review, BookkeepingService book) {
    this.store = store;
    this.review = review;
    this.book = book;
  }

  /** Upserts original Excel rows idempotently, retaining any already linked business identity. */
  @Transactional
  public void importRows(SourceDocument document, Map<String, Object> draft) {
    for (var input : rows(draft, "rows")) {
      String sheet = text(input, "sheet");
      int number = Integer.parseInt(input.get("rowNumber").toString());
      var row =
          store.all(LegacyRow.class).stream()
              .filter(
                  r ->
                      r.documentId.equals(document.id)
                          && r.sheet.equals(sheet)
                          && r.rowNumber == number)
              .findFirst()
              .orElseGet(
                  () -> {
                    var r = new LegacyRow();
                    r.documentId = document.id;
                    r.sheet = sheet;
                    r.rowNumber = number;
                    return store.add(r);
                  });
      row.date = date(input, "date");
      row.year =
          row.date != null
              ? row.date.getYear()
              : input.get("year") == null ? 0 : Integer.parseInt(input.get("year").toString());
      row.month =
          row.date != null
              ? row.date.getMonthValue()
              : input.get("month") == null ? 0 : Integer.parseInt(input.get("month").toString());
      row.description = text(input, "description");
      row.received = Money.optional(input.get("received"));
      row.payment = Money.optional(input.get("payment"));
      row.balance = Money.optional(input.get("balance"));
      row.cells = Json.write(input.get("cells"));
      if (input.get("issues") instanceof List<?> issues && !issues.isEmpty())
        review.open(
            document.id,
            "legacy",
            row.id,
            "LEGACY_VALUES",
            String.join("; ", issues.stream().map(Object::toString).toList()));
    }
    refresh();
  }

  /** Checks edited historical fields and month controls before resolving an Excel extraction. */
  public void validateDraft(Map<String, Object> draft) {
    var errors = new ArrayList<String>();
    var rows = rows(draft, "rows");
    if (rows.isEmpty()) errors.add("O Excel não contém linhas reconhecidas");
    for (var row : rows) {
      if (date(row, "date") == null || text(row, "description") == null)
        errors.add("Existe uma linha sem data ou descrição");
      Money.optional(row.get("received"));
      Money.optional(row.get("payment"));
      Money.optional(row.get("balance"));
    }
    for (var sheet : rows(draft, "sheets")) {
      var controls =
          sheet.get("controls") instanceof Map<?, ?>
              ? Json.object(Json.write(sheet.get("controls")))
              : new HashMap<String, Object>();
      var relevant =
          rows.stream()
              .filter(r -> Objects.equals(r.get("sheet"), sheet.get("name")))
              .sorted(Comparator.comparingInt(r -> Integer.parseInt(r.get("rowNumber").toString())))
              .toList();
      var received = Money.ZERO;
      var paid = Money.ZERO;
      var running = Money.orZero(Money.optional(controls.get("opening")));
      for (var row : relevant) {
        received = received.add(Money.orZero(Money.optional(row.get("received"))));
        paid = paid.add(Money.orZero(Money.optional(row.get("payment"))));
        running =
            running
                .add(Money.orZero(Money.optional(row.get("received"))))
                .subtract(Money.orZero(Money.optional(row.get("payment"))));
        if (row.get("balance") != null
            && Money.optional(row.get("balance")).compareTo(running) != 0)
          errors.add(
              sheet.get("name") + ": saldo acumulado incorreto na linha " + row.get("rowNumber"));
      }
      for (var check :
          List.of(
              map("field", "received", "actual", received),
              map("field", "payment", "actual", paid),
              map("field", "closing", "actual", running))) {
        String field = check.get("field").toString();
        if (controls.get(field) != null
            && Money.optional(controls.get(field)).compareTo((BigDecimal) check.get("actual")) != 0)
          errors.add(sheet.get("name") + ": controlo mensal incorreto (" + field + ")");
      }
    }
    if (!errors.isEmpty()) throw DomainException.invalid(String.join("; ", errors));
    rows.forEach(r -> r.put("issues", List.of()));
    draft.put("issues", List.of());
  }

  /** Associates only unique matching invoice evidence and checks historical payment references. */
  @Transactional
  public void refresh() {
    store.lockFinancialWrites();
    var invoices = store.all(Invoice.class);
    for (var row : store.all(LegacyRow.class)) {
      if (row.invoiceId == null) {
        var candidates =
            invoices.stream()
                .filter(BookkeepingService::eligible)
                .filter(
                    i ->
                        row.date != null
                            && row.date.equals(i.issuedOn)
                            && row.received != null
                            && row.received.compareTo(signedTotal(i)) == 0)
                .filter(
                    i ->
                        MatchingRules.reference(i.number, row.description)
                            || MatchingRules.supplier(i.supplier, row.description))
                .toList();
        if (candidates.size() == 1) row.invoiceId = candidates.getFirst().id;
      }
      if (row.invoiceId == null) {
        row.status = "PROCESSING";
        review.open(
            row.documentId,
            "legacy",
            row.id,
            "LEGACY_MISSING_DOCUMENT",
            "Linha do Excel ainda sem fatura confirmada");
        continue;
      }
      var invoice = store.get(Invoice.class, row.invoiceId);
      if (!BookkeepingService.eligible(invoice)
          || row.received == null
          || row.received.compareTo(signedTotal(invoice)) != 0
          || !Objects.equals(row.date, invoice.issuedOn)) {
        row.status = "OPEN";
        review.open(
            row.documentId,
            "legacy",
            row.id,
            "LEGACY_MISMATCH",
            "O Excel e a fatura têm valores ou datas diferentes");
        continue;
      }
      BigDecimal paid =
          invoice
              .total
              .subtract(book.remaining(invoice))
              .multiply(
                  invoice.type == pt.conta.shared.Types.InvoiceType.CREDIT_NOTE
                      ? BigDecimal.ONE.negate()
                      : BigDecimal.ONE);
      if (row.payment != null && paid.compareTo(row.payment) != 0) {
        row.status = "OPEN";
        review.open(
            row.documentId,
            "legacy",
            row.id,
            "LEGACY_PAYMENT",
            "O pagamento indicado no Excel não coincide com os movimentos reconciliados");
      } else {
        row.status = "AUTOMATIC";
        review.resolveFor("legacy", row.id);
      }
    }
  }

  /**
   * Explicitly links an owner-selected invoice; comparison issues remain visible until consistent.
   */
  @Transactional
  public void link(UUID rowId, UUID invoiceId) {
    var row = store.get(LegacyRow.class, rowId);
    store.get(Invoice.class, invoiceId);
    row.invoiceId = invoiceId;
    refresh();
  }

  /** Applies the document type's sign while keeping the original invoice total positive. */
  private BigDecimal signedTotal(Invoice invoice) {
    return invoice.type == pt.conta.shared.Types.InvoiceType.CREDIT_NOTE
        ? invoice.total.negate()
        : invoice.total;
  }
}

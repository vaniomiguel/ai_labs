package pt.conta.document;

import static pt.conta.shared.Inputs.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.audit.AuditService;
import pt.conta.bank.*;
import pt.conta.history.HistoryService;
import pt.conta.invoice.Invoice;
import pt.conta.ledger.BookkeepingService;
import pt.conta.review.ReviewService;
import pt.conta.shared.*;
import pt.conta.shared.Types.*;

/** Publishes extraction revisions atomically and gates complete statements on global integrity. */
@Service
public class DocumentPublisher {
  private final Store store;
  private final BookkeepingService book;
  private final ReviewService review;
  private final HistoryService history;
  private final AuditService audit;
  private final pt.conta.jobs.JobQueue jobs;

  public DocumentPublisher(
      Store store,
      BookkeepingService book,
      ReviewService review,
      HistoryService history,
      AuditService audit,
      pt.conta.jobs.JobQueue jobs) {
    this.store = store;
    this.book = book;
    this.review = review;
    this.history = history;
    this.audit = audit;
    this.jobs = jobs;
  }

  /** Saves extraction evidence and publishes safe records, ignoring stale background results. */
  @Transactional
  public boolean extracted(
      UUID id, long version, DocumentConverter.Converted converted, Map<String, Object> draft) {
    store.lockFinancialWrites();
    var doc = store.get(SourceDocument.class, id);
    if (doc.version != version || doc.deleted) return false;
    draft = DraftSanitizer.sanitize(doc.kind, draft);
    doc.text = converted.text();
    doc.pages = converted.pages();
    doc.layout = Json.write(converted.layout());
    doc.draft = Json.write(draft);
    var attempt = new ExtractionAttempt();
    attempt.documentId = id;
    attempt.engine = converted.engine();
    attempt.outcome = "COMPLETE";
    attempt.result = doc.draft;
    attempt.evidence = doc.layout;
    store.add(attempt);
    publish(doc, draft, converted.engine(), false);
    return true;
  }

  /** Saves raw draft edits even when invalid, excluding prior business records until resolution. */
  @Transactional
  public Map<String, Object> edit(UUID id, Map<String, Object> body, DocumentService documents) {
    store.lockFinancialWrites();
    var doc = store.get(SourceDocument.class, id);
    store.checkVersion(doc, version(body));
    String before = doc.draft;
    Object raw = body.get("draft");
    var draft = Json.object(Json.write(raw));
    doc.draft = Json.write(draft);
    if (flag(body, "resolve")) {
      draft.put("validate", true);
      publish(doc, draft, "MANUAL", true);
      review.resolveFor("document", id);
    } else {
      suspend(doc);
      doc.status = "OPEN";
      review.open(
          id, "document", id, "DRAFT_REVIEW", "Alterações guardadas; falta validar e resolver");
    }
    store.flush();
    audit.record("EDIT_EXTRACTION", "document", id, Json.read(before), Json.read(doc.draft));
    jobs.enqueue("REVIEW_DOCUMENT", id, doc.version, "{}");
    return documents.view(doc);
  }

  /**
   * Applies a proposed AI extraction only when it targets the same still-current draft revision.
   */
  @Transactional
  public void aiExtracted(UUID id, long version, Map<String, Object> draft, String evidence) {
    store.lockFinancialWrites();
    var doc = store.get(SourceDocument.class, id);
    if (doc.version != version || doc.deleted) return;
    draft = DraftSanitizer.sanitize(doc.kind, draft);
    doc.draft = Json.write(draft);
    var attempt = new ExtractionAttempt();
    attempt.documentId = id;
    attempt.engine = "AI";
    attempt.outcome = "COMPLETE";
    attempt.result = doc.draft;
    attempt.evidence = evidence;
    store.add(attempt);
    publish(doc, draft, "AI", false);
    store.flush();
    jobs.enqueue("REVIEW_DOCUMENT", doc.id, doc.version, "{}");
  }

  /** Flags grounded AI concerns on automatic data, without overriding owner-confirmed decisions. */
  @Transactional
  public void suspend(SourceDocument doc) {
    store.all(Invoice.class).stream()
        .filter(i -> Objects.equals(i.documentId, doc.id) && !i.deleted)
        .forEach(i -> i.reviewStatus = ReviewStatus.OPEN);
    store.all(BankMovement.class).stream()
        .filter(m -> Objects.equals(m.documentId, doc.id) && !m.deleted)
        .forEach(m -> m.reviewStatus = ReviewStatus.OPEN);
    book.synchronize();
  }

  private void publish(
      SourceDocument doc, Map<String, Object> input, String origin, boolean human) {
    var draft = new LinkedHashMap<>(input);
    draft.put("documentId", doc.id.toString());
    boolean issues = draft.get("issues") instanceof List<?> list && !list.isEmpty();
    if (!human && issues) draft.put("validate", false);
    if (doc.kind.equals("HISTORY")) {
      if (human) history.validateDraft(draft);
      history.importRows(doc, draft);
      doc.status = human || !issues ? "DONE" : "OPEN";
    } else if (doc.kind.equals("INVOICE")) {
      var existing =
          store.all(Invoice.class).stream()
              .filter(i -> Objects.equals(i.documentId, doc.id) && !i.deleted)
              .findFirst();
      existing.ifPresent(i -> draft.put("version", i.version));
      var result = book.saveInvoice(existing.map(i -> i.id).orElse(null), draft, origin, human);
      doc.status = result.get("reviewStatus") == ReviewStatus.OPEN ? "OPEN" : "DONE";
    } else if (doc.kind.equals("STATEMENT")) {
      publishStatement(doc, draft, origin, human);
    }
    if (issues && !human)
      review.open(
          doc.id,
          "document",
          doc.id,
          "EXTRACTION_REVIEW",
          String.join(
              "; ", ((List<?>) draft.get("issues")).stream().map(Object::toString).toList()));
    if (doc.status.equals("DONE")) {
      review.resolveFor("document", doc.id);
      if (human) {
        draft.put("issues", List.of());
        for (String key : List.of("lines", "movements", "rows"))
          rows(draft, key).forEach(row -> row.remove("issues"));
        doc.draft = Json.write(draft);
      }
    }
    history.refresh();
  }

  private void publishStatement(
      SourceDocument doc, Map<String, Object> draft, String origin, boolean human) {
    var statement =
        store.all(BankStatement.class).stream()
            .filter(s -> doc.id.equals(s.documentId))
            .findFirst()
            .orElseGet(
                () -> {
                  var s = new BankStatement();
                  s.documentId = doc.id;
                  return store.add(s);
                });
    statement.accountId = doc.accountId;
    statement.openingBalance = Money.optional(draft.get("openingBalance"));
    statement.closingBalance = Money.optional(draft.get("closingBalance"));
    var rows = rows(draft, "movements");
    var dates =
        rows.stream().map(r -> date(r, "bookedOn")).filter(Objects::nonNull).sorted().toList();
    statement.periodStart = date(draft, "periodStart");
    statement.periodEnd = date(draft, "periodEnd");
    if (statement.periodStart == null && !dates.isEmpty()) statement.periodStart = dates.getFirst();
    if (statement.periodEnd == null && !dates.isEmpty()) statement.periodEnd = dates.getLast();
    var errors = new ArrayList<String>();
    if (rows.isEmpty()) errors.add("Não existem movimentos para confirmar");
    if (statement.accountId == null) errors.add("Falta a conta bancária");
    BigDecimal signed = Money.ZERO;
    for (var row : rows) {
      BigDecimal amount = Money.optional(row.get("amount"));
      String direction = text(row, "direction");
      if (amount == null
          || amount.signum() <= 0
          || direction == null
          || date(row, "bookedOn") == null
          || text(row, "description") == null) errors.add("Existe um movimento incompleto");
      else signed = signed.add(direction.equals("DEBIT") ? amount.negate() : amount);
      if (direction != null && !Set.of("DEBIT", "CREDIT").contains(direction))
        errors.add("Direção de movimento inválida");
      if (row.get("currency") != null && !row.get("currency").equals("EUR"))
        errors.add("Moeda diferente de EUR");
    }
    if (statement.openingBalance != null
        && statement.closingBalance != null
        && statement.openingBalance.add(signed).compareTo(statement.closingBalance) != 0)
      errors.add("Os movimentos não explicam os saldos inicial/final");
    if (!human && (statement.openingBalance == null || statement.closingBalance == null))
      errors.add("Confirmar a integridade do extrato sem saldos de controlo");
    if (human && flag(draft, "validate") && !errors.isEmpty())
      throw DomainException.invalid(String.join("; ", errors));
    statement.validated = flag(draft, "validate") && errors.isEmpty();
    book.batch(
        () -> {
          var old =
              store.all(BankMovement.class).stream()
                  .filter(m -> statement.id.equals(m.statementId) && !m.deleted)
                  .toList();
          var seen = new HashSet<UUID>();
          for (int n = 0; n < rows.size(); n++) {
            var row = new LinkedHashMap<>(rows.get(n));
            row.put("documentId", doc.id.toString());
            row.put("statementId", statement.id.toString());
            row.put(
                "accountId", statement.accountId == null ? null : statement.accountId.toString());
            row.put("validate", statement.validated);
            BankMovement existing = n < old.size() ? old.get(n) : null;
            if (existing != null) {
              row.put("version", existing.version);
              seen.add(existing.id);
            }
            // Identical rows within a statement remain legitimate separate transactions. Across
            // sources they require review.
            boolean duplicate =
                store.all(BankMovement.class).stream()
                    .anyMatch(
                        m ->
                            !m.deleted
                                && !Objects.equals(m.documentId, doc.id)
                                && Objects.equals(m.accountId, statement.accountId)
                                && Objects.equals(m.bookedOn, date(row, "bookedOn"))
                                && Objects.equals(m.description, text(row, "description"))
                                && m.amount != null
                                && row.get("amount") != null
                                && m.amount.compareTo(Money.optional(row.get("amount"))) == 0);
            if (duplicate && !human) {
              row.put("validate", false);
              review.open(
                  doc.id,
                  "document",
                  doc.id,
                  "POSSIBLE_DUPLICATE",
                  "Existe um movimento semelhante noutro extrato; confirma a sobreposição");
            }
            book.saveMovement(existing == null ? null : existing.id, row, origin, human);
          }
          for (var movement : old)
            if (!seen.contains(movement.id)) {
              movement.deleted = true;
            }
          if (!errors.isEmpty())
            review.open(
                doc.id, "document", doc.id, "STATEMENT_INTEGRITY", String.join("; ", errors));
          doc.status = statement.validated && errors.isEmpty() ? "DONE" : "OPEN";
        });
  }
}

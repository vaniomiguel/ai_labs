package pt.conta.ai;

import static pt.conta.shared.Inputs.*;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.bank.BankMovement;
import pt.conta.document.FieldProtection;
import pt.conta.document.SourceDocument;
import pt.conta.invoice.Invoice;
import pt.conta.invoice.InvoiceLine;
import pt.conta.jobs.JobQueue;
import pt.conta.reconciliation.Allocation;
import pt.conta.reconciliation.MatchingRules;
import pt.conta.shared.*;
import pt.conta.shared.Types.ReviewStatus;

/** Captures the saved facts, ownership and revisions that an automatic review can inspect. */
@Service
public class AutomaticReviewContext {
  enum Kind {
    INVOICE(PromptCatalog.AUTOMATIC_INVOICE_REVIEW_CHECKLIST),
    MOVEMENT(PromptCatalog.AUTOMATIC_MOVEMENT_REVIEW_CHECKLIST),
    DOCUMENT(PromptCatalog.AUTOMATIC_DOCUMENT_REVIEW_CHECKLIST),
    ALLOCATION(PromptCatalog.AUTOMATIC_ALLOCATION_REVIEW_CHECKLIST);

    final String checklist;

    Kind(String checklist) {
      this.checklist = checklist;
    }

    static Optional<Kind> forJob(String job) {
      return switch (job) {
        case "REVIEW_INVOICE" -> Optional.of(INVOICE);
        case "REVIEW_MOVEMENT" -> Optional.of(MOVEMENT);
        case "REVIEW_DOCUMENT" -> Optional.of(DOCUMENT);
        case "REVIEW_ALLOCATION" -> Optional.of(ALLOCATION);
        default -> Optional.empty();
      };
    }
  }

  record Revision(Kind kind, UUID id, long version) {}
  record Page(int number, int start, int end) {}
  record Source(
      String id, UUID documentId, String kind, String text, boolean complete, long invoiceCount, List<Page> pages) {
    boolean available() {
      return !text.isBlank();
    }
  }
  record SavedRecord(
      Revision revision, UUID documentId, Map<String, Object> identity, Map<String, Object> ownership,
      boolean manuallyConfirmed, boolean excluded, boolean automatic) {}
  record Field(
      String path, String name, String value, String sourceId, String origin, boolean protectedValue,
      SavedRecord record) {
    Map<String, Object> input() {
      return map("path", path, "recordedValue", value, "sourceId", sourceId, "origin", origin,
          "protected", protectedValue, "manuallyConfirmed", record != null && record.manuallyConfirmed(),
          "excluded", record != null && record.excluded());
    }
  }
  record Snapshot(
      Kind kind, Revision target, UUID alertDocument, List<Revision> revisions,
      List<Source> sources, List<Field> fields, Map<String, Object> data) {}

  private static final List<String> INVOICE_FIELDS =
      List.of("number", "supplier", "supplierTaxId", "issuedOn", "total", "net", "tax", "discount", "currency", "type");
  private static final List<String> MOVEMENT_FIELDS = List.of("bookedOn", "description", "amount", "direction");
  private static final List<String> ARTICLE_FIELDS = List.of("description", "quantity", "unitPrice", "net", "tax", "discount", "total");

  private final Store store;
  private final FieldProtection protection;
  private final Set<String> historySheets;

  public AutomaticReviewContext(
      Store store, FieldProtection protection,
      @Value("${accounting.ai-history-months:}") String historyMonths) {
    this.store = store;
    this.protection = protection;
    this.historySheets = historyMonths.isBlank() ? Set.of() : Arrays.stream(historyMonths.split(","))
        .map(String::trim).map(Integer::parseInt).map(Month::of)
        .map(month -> month.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-PT")))
        .map(MatchingRules::normalize).collect(Collectors.toUnmodifiableSet());
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Snapshot capture(JobQueue.Claim job) {
    return switch (Kind.forJob(job.kind()).orElseThrow()) {
      case INVOICE -> invoiceReview(job);
      case MOVEMENT -> movementReview(job);
      case DOCUMENT -> documentReview(job);
      case ALLOCATION -> allocationReview(job);
    };
  }

  private Snapshot invoiceReview(JobQueue.Claim job) {
    var invoice = store.get(Invoice.class, job.target());
    if (invoice.deleted || invoice.version != job.revision()) return null;
    var record = invoiceRecord(invoice);
    var sources = new ArrayList<Revision>();
    var source = source("invoice", invoice.documentId, sources);
    var fields = new ArrayList<Field>();
    addInvoice(fields, "", invoiceFacts(invoice, record), record, source.id());
    return new Snapshot(Kind.INVOICE, record.revision(), invoice.documentId,
        List.copyOf(sources), List.of(source), List.copyOf(fields), record.identity());
  }

  private Snapshot movementReview(JobQueue.Claim job) {
    var movement = store.get(BankMovement.class, job.target());
    if (movement.deleted || movement.version != job.revision()) return null;
    var record = movementRecord(movement);
    var sources = new ArrayList<Revision>();
    var source = source("statement", movement.documentId, sources);
    var fields = new ArrayList<Field>();
    addFields(fields, "", record.identity(), MOVEMENT_FIELDS, record, source.id(), null);
    return new Snapshot(Kind.MOVEMENT, record.revision(), movement.documentId,
        List.copyOf(sources), List.of(source), List.copyOf(fields), record.identity());
  }

  private Snapshot allocationReview(JobQueue.Claim job) {
    var allocation = store.get(Allocation.class, job.target());
    if (!allocation.active || allocation.version != job.revision()) return null;
    var invoice = store.get(Invoice.class, allocation.invoiceId);
    var movement = store.get(BankMovement.class, allocation.movementId);
    if (invoice.deleted || movement.deleted) return null;
    var invoiceRecord = invoiceRecord(invoice);
    var movementRecord = movementRecord(movement);
    var revisions = new ArrayList<>(List.of(invoiceRecord.revision(), movementRecord.revision()));
    var sources = List.of(source("invoice", invoice.documentId, revisions),
        source("statement", movement.documentId, revisions));
    var link = map("invoiceReference", invoice.number, "amount", Money.text(allocation.amount),
        "origin", allocation.origin, "reversesId", allocation.reversesId, "evidence", allocation.evidence,
        "manuallyConfirmed", "MANUAL".equals(allocation.origin));
    var fields = new ArrayList<Field>();
    var target = new Revision(Kind.ALLOCATION, allocation.id, allocation.version);
    var record = new SavedRecord(target, invoice.documentId, link,
        map("invoiceReference", map("origin", allocation.origin)), "MANUAL".equals(allocation.origin), false, false);
    addFields(fields, "allocation.", link, List.of("invoiceReference"), record, "statement", null);
    return new Snapshot(Kind.ALLOCATION, target,
        invoice.documentId, List.copyOf(revisions), sources, List.copyOf(fields),
        map("allocation", link, "invoice", invoiceRecord.identity(), "movement", movementRecord.identity()));
  }

  private Snapshot documentReview(JobQueue.Claim job) {
    var doc = store.get(SourceDocument.class, job.target());
    if (doc.deleted || doc.version != job.revision()) return null;
    var revisions = new ArrayList<Revision>();
    var source = source("document", doc, revisions);
    var draft = Json.object(doc.draft);
    var fields = new ArrayList<Field>();
    switch (doc.kind) {
      case "INVOICE" -> documentInvoices(doc, draft, fields, revisions);
      case "STATEMENT" -> documentMovements(doc, draft, fields, revisions);
      case "HISTORY" -> historicalFields(draft, fields);
      default -> { }
    }
    return new Snapshot(Kind.DOCUMENT, new Revision(Kind.DOCUMENT, doc.id, doc.version), doc.id,
        List.copyOf(revisions), List.of(source), List.copyOf(fields),
        map("documentKind", doc.kind, "historySheets", historySheets.stream().sorted().toList()));
  }

  private void documentInvoices(
      SourceDocument doc, Map<String, Object> draft, List<Field> fields, List<Revision> revisions) {
    var invoices = store.all(Invoice.class).stream()
        .filter(i -> !i.deleted && doc.id.equals(i.documentId))
        .collect(Collectors.toMap(i -> i.id.toString(), i -> i));
    boolean root = protection.root("INVOICE", draft);
    var rows = protection.entries("INVOICE", draft);
    for (int n = 0; n < rows.size(); n++) {
      var row = rows.get(n);
      var invoice = invoices.get(Objects.toString(row.get("id"), ""));
      String prefix = root ? "" : "invoices[" + n + "].";
      var saved = draftRecord(doc, row, root);
      var record = invoice == null ? saved : withDraftOwnership(invoiceRecord(invoice), saved);
      if (invoice != null) revisions.add(record.revision());
      addInvoice(fields, prefix, row, record, "document");
    }
  }

  private void documentMovements(
      SourceDocument doc, Map<String, Object> draft, List<Field> fields, List<Revision> revisions) {
    var movements = store.all(BankMovement.class).stream()
        .filter(m -> !m.deleted && doc.id.equals(m.documentId))
        .collect(Collectors.toMap(m -> m.id.toString(), m -> m));
    var rows = rows(draft, "movements");
    for (int n = 0; n < rows.size(); n++) {
      var row = rows.get(n);
      var movement = movements.get(Objects.toString(row.get("id"), ""));
      var saved = draftRecord(doc, row, false);
      var record = movement == null ? saved : withDraftOwnership(movementRecord(movement), saved);
      if (movement != null) revisions.add(record.revision());
      addFields(fields, "movements[" + n + "].", row, MOVEMENT_FIELDS, record, "document", null);
    }
  }

  private void historicalFields(Map<String, Object> draft, List<Field> fields) {
    var rows = rows(draft, "rows");
    for (int n = 0; n < rows.size(); n++) {
      var row = rows.get(n);
      if (inHistoryScope(text(row, "sheet")))
        addFields(fields, "rows[" + n + "].", row,
            List.of("sheet", "rowNumber", "date", "description", "received", "payment", "balance"),
            null, "document", null);
    }
    var sheets = rows(draft, "sheets");
    for (int n = 0; n < sheets.size(); n++) {
      var sheet = sheets.get(n);
      if (!inHistoryScope(text(sheet, "name"))) continue;
      addFields(fields, "sheets[" + n + "].", sheet, List.of("name"), null, "document", null);
      addFields(fields, "sheets[" + n + "].controls.", protection.copy(sheet.get("controls")),
          List.of("opening", "received", "payment", "closing"), null, "document", null);
    }
  }

  private boolean inHistoryScope(String sheet) {
    return historySheets.isEmpty() || historySheets.contains(MatchingRules.normalize(sheet));
  }

  private Source source(String id, UUID document, List<Revision> revisions) {
    return document == null ? new Source(id, null, null, "", false, 0, List.of())
        : source(id, store.get(SourceDocument.class, document), revisions);
  }

  private Source source(String id, SourceDocument doc, List<Revision> revisions) {
    revisions.add(new Revision(Kind.DOCUMENT, doc.id, doc.version));
    if (doc.deleted) return new Source(id, doc.id, doc.kind, "", false, 0, List.of());
    String text = Objects.toString(doc.text, "");
    if ("HISTORY".equals(doc.kind) && !historySheets.isEmpty()) text = scopedHistoryText(text);
    var layout = Json.object(doc.layout);
    boolean complete = !text.isBlank() && layout.get("conversionError") == null;
    return new Source(id, doc.id, doc.kind, text, complete, invoiceCount(doc), pages(text, rows(layout, "pages")));
  }

  private List<Page> pages(String source, List<Map<String, Object>> layout) {
    var texts = layout.stream().map(page -> page.get("text")).filter(String.class::isInstance)
        .map(String.class::cast).toList();
    if (texts.isEmpty() || texts.size() != layout.size()) return List.of();
    String separator = source.equals(String.join("\n", texts)) ? "\n" : "\n\n";
    if (!source.equals(String.join(separator, texts))) return List.of();

    int offset = 0;
    var pages = new ArrayList<Page>();
    for (int index = 0; index < texts.size(); index++) {
      Object label = layout.get(index).get("page");
      if (!(label instanceof Number number) || number.intValue() != index + 1) return List.of();
      int end = offset + texts.get(index).length();
      pages.add(new Page(number.intValue(), offset, end));
      offset = end + separator.length();
    }
    return List.copyOf(pages);
  }

  private long invoiceCount(SourceDocument doc) {
    if (!"INVOICE".equals(doc.kind)) return 0;
    long saved = store.all(Invoice.class).stream().filter(i -> !i.deleted && doc.id.equals(i.documentId)).count();
    int draft = rows(Json.object(doc.draft), "invoices").size();
    return Math.max(1, Math.max(saved, draft));
  }

  private String scopedHistoryText(String source) {
    var sheets = Json.read(source);
    if (!sheets.isArray()) return "";
    var selected = new ArrayList<>();
    for (var sheet : sheets)
      if (inHistoryScope(sheet.path("name").asText())) selected.add(sheet);
    return selected.isEmpty() ? "" : Json.write(selected);
  }

  private SavedRecord invoiceRecord(Invoice invoice) {
    return new SavedRecord(new Revision(Kind.INVOICE, invoice.id, invoice.version), invoice.documentId,
        map("number", invoice.number, "supplier", invoice.supplier, "issuedOn", invoice.issuedOn,
            "total", Money.text(invoice.total), "currency", invoice.currency, "type", invoice.type),
        Json.object(invoice.fieldProvenance), invoice.manuallyConfirmed, invoice.excluded,
        invoice.reviewStatus == ReviewStatus.AUTOMATIC && !"MANUAL".equals(invoice.origin));
  }

  private Map<String, Object> invoiceFacts(Invoice invoice, SavedRecord record) {
    var lines = store.all(InvoiceLine.class).stream()
        .filter(line -> invoice.id.equals(line.invoiceId))
        .sorted(Comparator.comparingInt(line -> line.position))
        .map(line -> map("description", line.description, "quantity", line.quantity, "unitPrice", line.unitPrice,
            "net", Money.text(line.net), "tax", Money.text(line.tax), "discount", Money.text(line.discount),
            "total", Money.text(line.total)))
        .toList();
    var facts = new LinkedHashMap<>(record.identity());
    facts.putAll(map("supplierTaxId", invoice.supplierTaxId, "net", Money.text(invoice.net), "tax",
        Money.text(invoice.tax), "discount", Money.text(invoice.discount), "lines", lines));
    return facts;
  }

  private SavedRecord movementRecord(BankMovement movement) {
    return new SavedRecord(new Revision(Kind.MOVEMENT, movement.id, movement.version), movement.documentId,
        map("bookedOn", movement.bookedOn, "description", movement.description, "amount", Money.text(movement.amount),
            "direction", movement.direction, "bankReference", movement.bankReference),
        Json.object(movement.fieldProvenance), movement.manuallyConfirmed, movement.excluded,
        movement.reviewStatus == ReviewStatus.AUTOMATIC && !"MANUAL".equals(movement.origin));
  }

  private SavedRecord draftRecord(SourceDocument doc, Map<String, Object> row, boolean root) {
    return new SavedRecord(null, doc.id, Map.of(), protection.rowMetadata(
        Json.object(doc.fieldProvenance), protection.rowKey(row, root)), false, false, false);
  }

  private SavedRecord withDraftOwnership(SavedRecord record, SavedRecord draft) {
    var ownership = new LinkedHashMap<>(record.ownership());
    draft.ownership().forEach((field, value) -> {
      if (!ownership.containsKey(field) || protection.protectedField(draft.ownership(), field, "saved"))
        ownership.put(field, value);
    });
    return new SavedRecord(record.revision(), record.documentId(), record.identity(), ownership,
        record.manuallyConfirmed(), record.excluded(), record.automatic());
  }

  private void addInvoice(
      List<Field> fields, String prefix, Map<String, Object> row, SavedRecord record, String source) {
    addFields(fields, prefix, row, INVOICE_FIELDS, record, source, null);
    var lines = rows(row, "lines");
    for (int n = 0; n < lines.size(); n++)
      addFields(fields, prefix + "lines[" + n + "].", lines.get(n), ARTICLE_FIELDS, record, source, "lines");
  }

  private void addFields(
      List<Field> fields, String prefix, Map<String, Object> row, List<String> names,
      SavedRecord record, String source, String collection) {
    for (String name : names) {
      String value = text(row, name);
      if (value == null) continue;
      String ownershipKey = collection == null ? name : collection;
      var ownership = record == null ? Map.<String, Object>of() : record.ownership();
      String origin = text(protection.copy(ownership.get(ownershipKey)), "origin");
      boolean protectedValue = record != null && (record.manuallyConfirmed()
          || collection != null && ownership.get("_legacy") instanceof Map<?, ?>
          || protection.protectedField(ownership, ownershipKey, value)
          || origin != null && (origin.startsWith("HUMAN_") || origin.equals("MANUAL")));
      fields.add(new Field(prefix + name, name, value, source, Objects.requireNonNullElse(origin, "UNKNOWN"),
          protectedValue, record));
    }
  }
}

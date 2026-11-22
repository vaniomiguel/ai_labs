package pt.conta.document;

import static pt.conta.shared.Inputs.*;

import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import pt.conta.audit.AuditService;
import pt.conta.bank.BankMovement;
import pt.conta.bank.BankStatement;
import pt.conta.history.LegacyRow;
import pt.conta.invoice.Invoice;
import pt.conta.jobs.JobQueue;
import pt.conta.review.ReviewService;
import pt.conta.shared.*;
import pt.conta.shared.Types.ReviewStatus;

/** Determines which part of an upload belongs to the selected record. */
@Service
public class SourceDeletion {
  public enum Action { KEEP, REMOVE_ROW, ERASE }
  public record Plan(SourceDocument document, Action action, Map<String, Object> draft,
      List<Marker> removed) {}
  public record Marker(String id, String itemId, String facts) {}
  private record Removal(Marker marker, Map<String, Object> item) {}
  private record Record(UUID id, boolean deleted, ReviewStatus status, Map<String, Object> fields) {}

  private final Store store;
  private final JdbcTemplate jdbc;
  private final FieldProtection protection;
  private final ReviewService review;
  private final AuditService audit;
  private final DocumentFileDeletion files;
  private final JobQueue jobs;

  public SourceDeletion(Store store, JdbcTemplate jdbc, FieldProtection protection, ReviewService review,
      AuditService audit, DocumentFileDeletion files, JobQueue jobs) {
    this.store = store;
    this.jdbc = jdbc;
    this.protection = protection;
    this.review = review;
    this.audit = audit;
    this.files = files;
    this.jobs = jobs;
  }

  public Plan forRecord(UUID sourceId, UUID recordId) {
    if (sourceId == null) return null;
    return store.find(SourceDocument.class, sourceId).filter(doc -> !doc.deleted)
        .map(doc -> plan(doc, recordId)).orElse(null);
  }

  public Plan forOrphan(UUID id) {
    var doc = store.find(SourceDocument.class, id).filter(candidate -> !candidate.deleted).orElseThrow(DomainException::missing);
    var plan = plan(doc, null);
    if (plan.action != Action.ERASE)
      throw new DomainException(409, "Esta extração ainda está em uso ou não corresponde a um registo eliminado.");
    return plan;
  }

  public Set<UUID> deletedConsumers(UUID document) {
    var result = new HashSet<UUID>();
    records(document).stream().filter(Record::deleted).forEach(record -> result.add(record.id));
    return result;
  }

  public boolean canExtract(SourceDocument doc) {
    if (doc.deleted) return false;
    var draft = Json.object(doc.draft);
    if (noDraftItems(doc, draft) && !markers(doc).isEmpty()) return false;
    return plan(doc, null).action != Action.ERASE;
  }

  private boolean noDraftItems(SourceDocument doc, Map<String, Object> draft) {
    if (draft.isEmpty()) return true;
    if ("STATEMENT".equals(doc.kind)) return rows(draft, "movements").isEmpty();
    return "INVOICE".equals(doc.kind) && draft.containsKey("invoices")
        && rows(draft, "invoices").isEmpty() && !markers(doc).isEmpty();
  }

  /** Only load full drafts for documents with a previously deleted consumer and no live one. */
  Set<UUID> orphanIds() {
    var candidates = jdbc.queryForList("""
        with linked as (
          select document_id, deleted from invoice
          union all
          select coalesce(m.document_id,s.document_id), m.deleted from bank_movement m
            left join bank_statement s on s.id=m.statement_id
        )
        select d.id from source_document d join linked l on l.document_id=d.id
        where not d.deleted and d.kind in ('INVOICE','STATEMENT','SUPPORT')
        group by d.id having bool_and(l.deleted)
        """, UUID.class);
    var eligible = new HashSet<UUID>();
    for (UUID id : candidates) {
      try {
        forOrphan(id);
        eligible.add(id);
      } catch (DomainException ignored) {
        // Leave an unidentifiable or still-used draft accessible for review.
      }
    }
    return eligible;
  }

  private Plan plan(SourceDocument doc, UUID recordId) {
    var records = records(doc.id);
    var survivors = records.stream().filter(record -> !record.deleted && !record.id.equals(recordId)).toList();
    var selected = records.stream().filter(record -> recordId == null ? record.deleted : record.id.equals(recordId)).toList();
    if (selected.isEmpty() || recordId == null && !survivors.isEmpty()
        || !Set.of("INVOICE", "STATEMENT", "SUPPORT").contains(doc.kind))
      return new Plan(doc, Action.KEEP, null, List.of());

    var draft = Json.object(doc.draft);
    boolean root = protection.root(doc.kind, draft);
    var items = noDraftItems(doc, draft) ? List.<Map<String, Object>>of() : protection.entries(doc.kind, draft);
    var removals = new ArrayList<>(selected.stream().map(record -> removal(doc.kind, record, records, items, root))
        .flatMap(Optional::stream).toList());
    if (recordId != null) {
      for (var prior : records) {
        if (!prior.deleted) continue;
        removal(doc.kind, prior, records, items.stream().filter(row -> text(row, "id") != null).toList(), false)
            .ifPresent(removals::add);
      }
    }
    var removed = removals.stream().map(Removal::marker).toList();
    var remaining = items.stream().filter(item -> removals.stream().noneMatch(removal -> removal.item == item)).toList();
    if (survivors.isEmpty() && remaining.isEmpty() && !hasOtherConsumer(doc))
      return new Plan(doc, Action.ERASE, null, removed);
    if (removed.isEmpty()) return new Plan(doc, Action.KEEP, null, List.of());
    if (root) draft.clear();
    else draft.put(collection(doc.kind), remaining);
    return new Plan(doc, Action.REMOVE_ROW, draft, removed);
  }

  private Optional<Removal> removal(String kind, Record record, List<Record> records,
      List<Map<String, Object>> items, boolean root) {
    var exact = items.stream().filter(item -> record.id.toString().equals(text(item, "id"))).toList();
    if (exact.size() > 1) throw ambiguous();
    var anonymous = items.stream().filter(item -> text(item, "id") == null).toList();
    String facts = facts(kind, record.fields);
    if (exact.isEmpty() && !anonymous.isEmpty()) {
      exact = legacyMatches(kind, record, records, anonymous, root, facts);
      if (exact.size() != 1) throw ambiguous();
    }
    if (exact.isEmpty()) return Optional.empty();
    var item = exact.getFirst();
    return Optional.of(new Removal(new Marker(record.id.toString(), text(item, "_itemId"), facts(kind, item)), item));
  }

  private List<Map<String, Object>> legacyMatches(String kind, Record record, List<Record> records,
      List<Map<String, Object>> anonymous, boolean root, String facts) {
    if (facts != null && records.stream().filter(other -> facts.equals(facts(kind, other.fields))).count() == 1) {
      var matches = anonymous.stream().filter(item -> facts.equals(facts(kind, item))).toList();
      if (!matches.isEmpty()) return matches;
    }
    String bankReference = text(record.fields, "bankReference");
    if (bankReference != null && records.stream()
        .filter(other -> bankReference.equals(text(other.fields, "bankReference"))).count() == 1)
      return anonymous.stream().filter(item -> bankReference.equals(text(item, "bankReference"))).toList();
    if (root && records.size() == 1 && anonymous.size() == 1
        && (facts == null || facts(kind, anonymous.getFirst()) == null)) return anonymous;
    return List.of();
  }

  private static boolean matches(Marker marker, Map<String, Object> item, String kind) {
    String id = text(item, "id");
    if (id != null) return marker.id.equals(id);
    if (marker.itemId != null && marker.itemId.equals(text(item, "_itemId"))) return true;
    return marker.facts != null && marker.facts.equals(facts(kind, item));
  }

  private List<Record> records(UUID document) {
    var statements = new HashSet<UUID>();
    store.all(BankStatement.class).stream().filter(s -> document.equals(s.documentId)).forEach(s -> statements.add(s.id));
    var records = new ArrayList<Record>();
    store.all(Invoice.class).stream().filter(i -> document.equals(i.documentId)).forEach(i ->
        records.add(new Record(i.id, i.deleted, i.reviewStatus, SourceRecordSync.invoiceFields(i))));
    store.all(BankMovement.class).stream().filter(m -> document.equals(m.documentId)
        || m.documentId == null && statements.contains(m.statementId)).forEach(m ->
        records.add(new Record(m.id, m.deleted, m.reviewStatus, SourceRecordSync.movementFields(m))));
    return records;
  }

  private boolean hasOtherConsumer(SourceDocument doc) {
    return store.all(LegacyRow.class).stream().anyMatch(row -> doc.id.equals(row.documentId));
  }

  public void apply(Plan plan) {
    if (plan == null) return;
    switch (plan.action) {
      case KEEP -> { }
      case REMOVE_ROW -> removeRow(plan);
      case ERASE -> erase(plan.document);
    }
  }

  private void removeRow(Plan plan) {
    var doc = plan.document;
    var metadata = Json.object(doc.fieldProvenance);
    if (protection.root(doc.kind, Json.object(doc.draft))) metadata.remove("root");
    var removed = new ArrayList<>(markers(doc));
    for (var marker : plan.removed) {
      metadata.remove(marker.id);
      if (marker.itemId != null) metadata.remove(marker.itemId);
      removed.add(marker);
    }
    doc.removedDraftRows = Json.write(removed);
    doc.fieldProvenance = Json.write(metadata);
    review.invalidateSourceProposals(doc.id);
    boolean validated = records(doc.id).stream().filter(record -> !record.deleted)
        .noneMatch(record -> record.status == ReviewStatus.OPEN)
        && (plan.draft.isEmpty() || protection.entries(doc.kind, plan.draft).stream().allMatch(row -> flag(row, "validate")))
        && !review.hasPendingReview("document", doc.id);
    if (!plan.draft.isEmpty()) plan.draft.put("validate", validated);
    doc.draft = Json.write(plan.draft);
    doc.status = validated ? "DONE" : "OPEN";
    updateStatements(doc, plan.draft, validated);
    store.flush();
    jobs.invalidateDocumentWork(doc.id, doc.version);
    audit.record("REMOVE_EXTRACTION_ROW", "document", doc.id, null, plan.removed.stream().map(Marker::id).toList());
  }

  private void updateStatements(SourceDocument doc, Map<String, Object> draft, boolean validated) {
    var dates = rows(draft, "movements").stream().map(row -> text(row, "bookedOn"))
        .filter(Objects::nonNull).map(SourceDeletion::parseDate).filter(Objects::nonNull).sorted().toList();
    for (var statement : store.all(BankStatement.class)) {
      if (!doc.id.equals(statement.documentId)) continue;
      statement.periodStart = dates.isEmpty() ? null : dates.getFirst();
      statement.periodEnd = dates.isEmpty() ? null : dates.getLast();
      statement.validated = validated;
      statement.deleted = doc.deleted;
    }
  }

  private void erase(SourceDocument doc) {
    var key = doc.storageKey;
    jdbc.update("delete from extraction_attempt where document_id=?", doc.id);
    jdbc.update("delete from audit_event where entity_type='document' and entity_id=?", doc.id);
    doc.deleted = true;
    doc.storageKey = doc.sha256 = doc.filename = doc.sourcePath = null;
    doc.text = "";
    doc.draft = doc.layout = doc.fieldProvenance = "{}";
    doc.removedDraftRows = "[]";
    doc.pages = 0;
    doc.size = 0;
    updateStatements(doc, Map.of(), false);
    if (key != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists (select 1 from source_document where storage_key=? and id<>? and not deleted)
        """, Boolean.class, key, doc.id))) files.enqueue(key);
    audit.record("DELETE_EXTRACTION", "document", doc.id, null, null);
  }

  /** Filters automatic proposals against durable identities without treating a repeated fact as unique. */
  static Map<String, Object> suppressRemoved(SourceDocument doc, Map<String, Object> incoming) {
    var removed = markers(doc);
    if (removed.isEmpty()) return incoming;
    String collection = collection(doc.kind);
    var current = Json.object(doc.draft);
    if ("INVOICE".equals(doc.kind) && !incoming.containsKey(collection)
        && removed.stream().anyMatch(marker -> matches(marker, incoming, doc.kind))) return current;
    var items = rows(incoming, collection);
    var live = rows(current, collection);
    var filtered = items.stream().filter(item -> !suppressed(doc.kind, item, items, live, removed)).toList();
    var result = new LinkedHashMap<>(incoming);
    if (incoming.containsKey(collection)) result.put(collection, filtered);
    return result;
  }

  private static boolean suppressed(String kind, Map<String, Object> item, List<Map<String, Object>> items,
      List<Map<String, Object>> live, List<Marker> removed) {
    return removed.stream().filter(marker -> matches(marker, item, kind)).anyMatch(marker ->
        marker.id.equals(text(item, "id")) || marker.itemId != null && marker.itemId.equals(text(item, "_itemId"))
            || live.stream().noneMatch(row -> marker.facts.equals(facts(kind, row)))
            && items.stream().filter(row -> marker.facts.equals(facts(kind, row))).count() == 1);
  }

  private static List<Marker> markers(SourceDocument doc) {
    return rows(map("rows", Json.value(doc.removedDraftRows)), "rows").stream()
        .map(row -> new Marker(text(row, "id"), text(row, "itemId"), text(row, "facts"))).toList();
  }

  private static String facts(String kind, Map<String, Object> row) {
    try { return SourceIdentity.key(row, "INVOICE".equals(kind) ? "invoice" : "movement"); }
    catch (IllegalArgumentException | java.time.DateTimeException e) { return null; }
  }

  private static LocalDate parseDate(String date) {
    try { return LocalDate.parse(date); }
    catch (java.time.DateTimeException e) { return null; }
  }

  private static String collection(String kind) { return "INVOICE".equals(kind) ? "invoices" : "movements"; }

  private static DomainException ambiguous() {
    return new DomainException(409, "Não foi possível identificar este item na extração. Revê os itens do documento antes de eliminar o registo.");
  }
}

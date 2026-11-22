package pt.conta.document;

import static pt.conta.shared.Inputs.*;

import java.time.*;
import java.util.*;
import java.util.function.*;
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
  private final FieldProtection protection;
  private final SourceDeletion deletion;
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
      pt.conta.jobs.JobQueue jobs, FieldProtection protection, SourceDeletion deletion) {
    this.protection=protection;
    this.deletion = deletion;
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
    return extracted(id, version, converted, draft, false);
  }

  @Transactional
  public boolean extracted(
      UUID id, long version, DocumentConverter.Converted converted, Map<String, Object> draft, boolean followUp) {
    store.lockFinancialWrites();
    var doc = store.get(SourceDocument.class, id);
    if (doc.version != version || !deletion.canExtract(doc)) return false;
    boolean reprocessing = !Json.object(doc.draft).isEmpty();
    draft = SourceDeletion.suppressRemoved(doc, DraftSanitizer.sanitize(doc.kind, draft));
    doc.text = converted.text();
    doc.pages = converted.pages();
    doc.layout = Json.write(converted.layout());
    String rawDraft=Json.write(draft);
    draft=automaticDraft(doc,draft);
    doc.draft = Json.write(draft);
    var attempt = new ExtractionAttempt();
    attempt.documentId = id;
    attempt.engine = converted.engine();
    attempt.outcome = "COMPLETE";
    attempt.result = rawDraft;
    attempt.evidence = doc.layout;
    store.add(attempt);
    publish(doc, draft, converted.engine(), false);
    store.flush();
    if (reprocessing) proposeDifferences(doc, Json.object(rawDraft), doc.layout);
    if (followUp && Set.of("INVOICE", "STATEMENT", "HISTORY").contains(doc.kind)) {
      String kind = doc.status.equals("OPEN") && !doc.kind.equals("HISTORY") ? "AI_EXTRACT_DOCUMENT" : "REVIEW_DOCUMENT";
      jobs.enqueue(kind, id, doc.version, "{}");
    }
    return true;
  }

  /** Saves raw draft edits even when invalid, excluding prior business records until resolution. */
  @Transactional
  public Map<String, Object> edit(UUID id, Map<String, Object> body, DocumentService documents) {
    store.lockFinancialWrites();
    var doc = documents.reprocessable(id);
    store.checkVersion(doc, version(body));
    String before = doc.draft;
    Object raw = body.get("draft");
    var draft = protection.initialize(doc.kind, SourceDeletion.suppressRemoved(doc, Json.object(Json.write(raw))));
    boolean resolve = flag(body, "resolve");
    if (!resolve && financial(doc)) draft = preserveConfirmedRows(doc, Json.object(before), draft);
    if(Set.of("INVOICE","STATEMENT").contains(doc.kind))
      doc.fieldProvenance=Json.write(protection.humanDocument(doc.kind,Json.object(before),draft,Json.object(doc.fieldProvenance),resolve));
    doc.draft = Json.write(draft);
    if (resolve) {
      draft.put("validate", true);
      publish(doc, draft, "MANUAL", true);
      review.resolveFor("document", id);
    } else {
      saveUnresolved(doc, draft);
    }
    store.flush();
    audit.record("EDIT_EXTRACTION", "document", id, Json.read(before), Json.read(doc.draft));
    jobs.enqueue("REVIEW_DOCUMENT", id, doc.version, "{}");
    return documents.view(doc);
  }

  private void saveUnresolved(SourceDocument doc, Map<String, Object> draft) {
    String message = "Alterações guardadas; falta validar e resolver";
    if (financial(doc) && onlyConfirmedRows(doc, draft)) return;
    UUID invoice = linkedRootInvoice(doc, draft);
    if (invoice != null) {
      review.open(doc.id, "invoice", invoice, "DRAFT_REVIEW", message);
    } else {
      review.open(doc.id, "document", doc.id, "DRAFT_REVIEW", message);
    }
    suspend(doc);
    doc.status = "OPEN";
  }

  private UUID linkedRootInvoice(SourceDocument doc, Map<String, Object> draft) {
    if (!protection.root(doc.kind, draft)) return null;
    UUID id = uuid(draft, "id");
    if (id == null) return null;
    var invoice = store.get(Invoice.class, id);
    return !invoice.deleted && doc.id.equals(invoice.documentId) ? id : null;
  }

  private boolean onlyConfirmedRows(SourceDocument doc, Map<String, Object> draft) {
    var rows = protection.entries(doc.kind, draft);
    return !rows.isEmpty() && rows.stream().allMatch(row -> confirmedSourceRow(doc, row));
  }

  private boolean confirmedSourceRow(SourceDocument doc, Map<String, Object> row) {
    UUID id = uuid(row, "id");
    if (id == null) return false;
    if (doc.kind.equals("INVOICE")) {
      var invoice = store.get(Invoice.class, id);
      return !invoice.deleted && invoice.manuallyConfirmed && doc.id.equals(invoice.documentId);
    }
    var movement = store.get(BankMovement.class, id);
    return !movement.deleted && movement.manuallyConfirmed && doc.id.equals(movement.documentId);
  }

  private Map<String, Object> preserveConfirmedRows(
      SourceDocument doc, Map<String, Object> before, Map<String, Object> draft) {
    var confirmed = protection.entries(doc.kind, before).stream()
        .filter(row -> confirmedSourceRow(doc, row))
        .toList();
    if (confirmed.isEmpty()) return draft;
    if (protection.root(doc.kind, before)) return before;
    if (protection.root(doc.kind, draft)) throw protection.ambiguous();
    var rows = new ArrayList<>(protection.entries(doc.kind, draft));
    for (var row : confirmed) {
      int index = rows.stream().map(other -> text(other, "id")).toList().indexOf(text(row, "id"));
      if (index < 0) rows.add(row);
      else rows.set(index, row);
    }
    draft.put(doc.kind.equals("INVOICE") ? "invoices" : "movements", rows);
    return draft;
  }

  private boolean financial(SourceDocument doc) {
    return Set.of("INVOICE", "STATEMENT").contains(doc.kind);
  }

  /**
   * Applies a proposed AI extraction only when it targets the same still-current draft revision.
   */
  @Transactional
  public void aiExtracted(
      UUID id, long version, Map<String, Object> draft, String evidence,
      List<ExtractionAttempt.Prompt> prompts) {
    store.lockFinancialWrites();
    var doc = store.get(SourceDocument.class, id);
    if (doc.version != version || !deletion.canExtract(doc)) return;
    boolean reprocessing = !Json.object(doc.draft).isEmpty();
    draft = SourceDeletion.suppressRemoved(doc, DraftSanitizer.sanitize(doc.kind, draft));
    String rawDraft=Json.write(draft);
    draft=automaticDraft(doc,draft);
    doc.draft = Json.write(draft);
    var attempt = new ExtractionAttempt();
    attempt.documentId = id;
    attempt.engine = "AI";
    attempt.outcome = "COMPLETE";
    attempt.result = rawDraft;
    attempt.evidence = evidence;
    attempt.prompts = prompts.isEmpty() ? null : Json.write(prompts);
    store.add(attempt);
    publish(doc, draft, "AI", false);
    store.flush();
    if (reprocessing) proposeDifferences(doc, Json.object(rawDraft), evidence);
    jobs.enqueue("REVIEW_DOCUMENT", doc.id, doc.version, "{}");
  }

  private Map<String,Object> automaticDraft(SourceDocument doc,Map<String,Object> incoming) {
    if(!Set.of("INVOICE","STATEMENT").contains(doc.kind))return incoming;
    if(doc.kind.equals("STATEMENT")) {
      var existing=store.all(BankMovement.class).stream().filter(m->doc.id.equals(m.documentId)&&!m.deleted).toList();
      if(existing.stream().anyMatch(m->m.excluded))identities(existing,rows(incoming,"movements"),book::movementView,m->m.excluded,"movement");
    }else if(!rows(incoming,"invoices").isEmpty()) {
      var existing=store.all(Invoice.class).stream().filter(i->doc.id.equals(i.documentId)&&!i.deleted).toList();
      if(existing.stream().anyMatch(i->i.excluded))identities(existing,rows(incoming,"invoices"),book::invoiceView,i->i.excluded,"invoice");
    }
    try{return protection.automaticDocument(doc.kind,Json.object(doc.draft),incoming,Json.object(doc.fieldProvenance));}
    catch(DomainException e){
      review.open(doc.id,"document",doc.id,"AI_STRUCTURE_REVIEW",e.getMessage());
      return Json.object(doc.draft);
    }
  }

  private void proposeDifferences(SourceDocument doc,Map<String,Object> incoming,String evidence) {
    if (!financial(doc)) return;
    try {
      var current = Json.object(doc.draft);
      var aligned = protection.align(doc.kind, current, incoming);
      var changes = protection.changes(doc.kind, current, aligned, Json.object(doc.fieldProvenance));
      Object proof = Json.value(evidence);
      if (doc.kind.equals("STATEMENT")) proof = DraftSanitizer.withoutStatementValueDateEvidence(proof);
      offerDifferences(doc, current, aligned, changes, proof);
    } catch (DomainException e) {
      review.open(doc.id, "document", doc.id, "AI_STRUCTURE_REVIEW", e.getMessage());
    }
  }

  private void offerDifferences(SourceDocument doc, Map<String, Object> current, Map<String, Object> aligned,
      List<Map<String, Object>> changes, Object evidence) {
    String type = doc.kind.equals("INVOICE") ? "invoice" : "movement";
    boolean root = protection.root(doc.kind, current);
    var rows = protection.entries(doc.kind, current);
    var candidates = protection.entries(doc.kind, aligned);
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i);
      String key = protection.rowKey(row, root);
      var concerns = changes.stream().filter(change -> key.equals(change.get("itemId")))
          .map(change -> "A IA sugeriu rever " + change.get("field") + ".").toList();
      if (concerns.isEmpty()) continue;
      UUID id = uuid(row, "id");
      var alert = review.openAll(doc.id, id == null ? "document" : type, id == null ? doc.id : id,
          "AI_FIELD_SUGGESTIONS", concerns);
      if (alert != null && (alert.proposal == null || !Json.read(alert.proposal).hasNonNull("jobId")))
        alert.proposal = Json.write(map("draft", aligned, "documentVersion", doc.version,
            "issues", candidates.get(i).get("issues"), "evidence", evidence));
    }
  }

  /** Saves only selected children; draft changes never withdraw a validated sibling or record. */
  @Transactional
  public Map<String,Object> applySelected(UUID id,Map<String,Object> draft,Map<String,Object> provenance,Set<String> selected,boolean validate,DocumentService documents) {
    var doc=store.get(SourceDocument.class,id);String before=doc.draft;
    doc.draft=Json.write(draft);doc.fieldProvenance=Json.write(provenance);
    var saved=new HashSet<String>();
    for(var row:protection.entries(doc.kind,draft)) {
      String key=protection.rowKey(row,protection.root(doc.kind,draft));if(!selected.contains(key))continue;
      var input=new LinkedHashMap<>(row);input.put("documentId",doc.id.toString());input.put("validate",validate);
      for(String derived:List.of("categories","categoryDetails","categoryIds","classification","excluded","fieldProvenance"))input.remove(derived);
      var decisions=protection.rowMetadata(provenance,key);UUID record=uuid(row,"id");
      if(doc.kind.equals("INVOICE")) {
        Invoice existing=record==null?store.all(Invoice.class).stream().filter(i->doc.id.equals(i.documentId)&&!i.deleted).filter(_->protection.root(doc.kind,draft)).findFirst().orElse(null):store.get(Invoice.class,record);
        if(existing!=null){if(!doc.id.equals(existing.documentId))throw protection.ambiguous();input.put("version",existing.version);}
        if(validate||existing==null||existing.reviewStatus==ReviewStatus.OPEN) {
          var result=book.saveReviewedInvoice(existing==null?null:existing.id,input,decisions);
          row.put("id",result.get("id").toString());saved.add(result.get("id").toString());
          provenance.put(key,result.get("fieldProvenance"));
        }
      }else {
        var existing=record==null?null:store.get(BankMovement.class,record);
        if(existing!=null){if(!doc.id.equals(existing.documentId))throw protection.ambiguous();input.put("version",existing.version);input.put("statementId",existing.statementId);}
        if(validate||existing==null||existing.reviewStatus==ReviewStatus.OPEN) {
          if(!input.containsKey("accountId"))input.put("accountId",doc.accountId==null?null:doc.accountId.toString());
          var result=book.saveReviewedMovement(record,input,decisions);row.put("id",result.get("id").toString());saved.add(result.get("id").toString());
          provenance.put(key,result.get("fieldProvenance"));
        }
      }
      row.put("validate",validate);
    }
    // SourceRecordSync may have copied normalized fields while each selected item was saved.
    var normalized=Json.object(doc.draft);
    for(var row:protection.entries(doc.kind,draft)) {
      var actual=protection.entries(doc.kind,normalized).stream().filter(r->Objects.equals(text(r,"id"),text(row,"id"))).findFirst().orElse(null);
      if(actual!=null&&row.get("id")!=null&&saved.contains(row.get("id").toString()))row.putAll(actual);
      if(selected.contains(protection.rowKey(row,protection.root(doc.kind,draft)))||saved.contains(Objects.toString(row.get("id"),"")))row.put("validate",validate);
    }
    doc.draft=Json.write(draft);
    var mergedMetadata=Json.object(doc.fieldProvenance);mergedMetadata.putAll(provenance);doc.fieldProvenance=Json.write(mergedMetadata);
    if(!validate)doc.status="OPEN";
    store.flush();audit.record("APPLY_AI_FIELDS","document",id,Json.read(before),map("draft",draft,"fieldProvenance",mergedMetadata));
    return documents.view(doc);
  }

  /** Flags grounded AI concerns on automatic data, without overriding owner-confirmed decisions. */
  @Transactional
  public void suspend(SourceDocument doc) {
    store.all(Invoice.class).stream()
        .filter(i -> Objects.equals(i.documentId, doc.id) && !i.deleted && !i.manuallyConfirmed)
        .forEach(i -> i.reviewStatus = ReviewStatus.OPEN);
    store.all(BankMovement.class).stream()
        .filter(m -> Objects.equals(m.documentId, doc.id) && !m.deleted && !m.manuallyConfirmed)
        .forEach(m -> m.reviewStatus = ReviewStatus.OPEN);
    book.synchronize();
  }

  private void publish(
      SourceDocument doc, Map<String, Object> input, String origin, boolean human) {
    var draft = new LinkedHashMap<>(input);
    draft.put("documentId", doc.id.toString());
    boolean issues = draft.get("issues") instanceof List<?> list && !list.isEmpty();
    if (!human && issues && (!financial(doc) || !protection.root(doc.kind, draft)))
      draft.put("validate", false);
    switch (doc.kind) {
      case "HISTORY" -> {
        if (human) history.validateDraft(draft);
        history.importRows(doc, draft);
        doc.status = human || !issues ? "DONE" : "OPEN";
      }
      case "INVOICE" -> publishInvoiceDraft(doc, draft, origin, human);
      case "STATEMENT" -> publishStatement(doc, draft, origin, human);
      default -> { }
    }
    if (issues && !human && (!financial(doc) || !protection.root(doc.kind, draft)))
      review.openAll(doc.id, "document", doc.id, "EXTRACTION_REVIEW", review.messages(draft.get("issues")));
    if(!human&&Set.of("INVOICE","STATEMENT").contains(doc.kind)&&protection.entries(doc.kind,draft).stream().anyMatch(row->Boolean.FALSE.equals(row.get("validate"))))doc.status="OPEN";
    if (doc.status.equals("DONE")) {
      if (human || !financial(doc)) review.resolveFor("document", doc.id);
      else if (review.hasPendingReview("document", doc.id)) doc.status = "OPEN";
      if (human) {
        draft.put("issues", List.of());
        for (String key : List.of("lines", "invoices", "movements", "rows"))
          rows(draft, key).forEach(row -> row.remove("issues"));
        doc.draft = Json.write(draft);
      }
    }
    if(Set.of("INVOICE","STATEMENT").contains(doc.kind)) {
      var source=Json.object(doc.draft);var metadata=Json.object(doc.fieldProvenance);
      for(var row:protection.entries(doc.kind,source)) {
        String key=protection.rowKey(row,protection.root(doc.kind,source));
        var rowMeta=protection.rowMetadata(metadata,key);
        // New publications replace temporary identities with the persisted record identity.
        if(row.get("_itemId")!=null&&metadata.containsKey(row.get("_itemId").toString()))rowMeta.putAll(protection.copy(metadata.remove(row.get("_itemId").toString())));
        metadata.put(key,protection.automaticOrigins(doc.kind,row,rowMeta));
      }
      doc.fieldProvenance=Json.write(metadata);
    }
    history.refresh();
  }

  /** Publishes independent receipts in one original as separate identities, never summed lines. */
  private void publishInvoiceDraft(
      SourceDocument doc, Map<String, Object> draft, String origin, boolean human) {
    if (!rows(draft, "invoices").isEmpty()) {
      publishInvoices(doc, draft, origin, human);
      return;
    }
    var existing =
        store.all(Invoice.class).stream()
            .filter(i -> Objects.equals(i.documentId, doc.id) && !i.deleted)
            .findFirst();
    existing.ifPresent(i -> draft.put("version", i.version));
    var published = new ArrayList<Map<String, Object>>();
    book.batch(() -> published.add(book.saveInvoice(existing.map(i -> i.id).orElse(null), draft, origin, human)));
    var result = published.getFirst();
    draft.put("id", result.get("id").toString());
    if (!human && result.get("reviewStatus") == ReviewStatus.OPEN && review.openFor("invoice", uuid(draft, "id")).isEmpty())
      review.open(doc.id, "invoice", uuid(draft, "id"), "EXTRACTION_REVIEW", "Confirma os dados extraídos.");
    if (Boolean.TRUE.equals(result.get("manuallyConfirmed"))) {
      if (!Boolean.FALSE.equals(draft.get("validate")) || !existing.map(i -> i.manuallyConfirmed).orElse(false) || human)
        draft.put("validate", true);
      draft.put("issues", List.of());
    }
    doc.draft = Json.write(draft);
    doc.status = result.get("reviewStatus") == ReviewStatus.OPEN ? "OPEN" : "DONE";
  }

  private void publishInvoices(
      SourceDocument doc, Map<String, Object> draft, String origin, boolean human) {
    var existing =
        store.all(Invoice.class).stream()
            .filter(i -> doc.id.equals(i.documentId) && !i.deleted)
            .toList();
    var receipts = rows(draft, "invoices");
    var identities = identities(existing, receipts, book::invoiceView, i -> i.excluded, "invoice");
    var results = new ArrayList<Map<String, Object>>();
    book.batch(
        () -> {
          for (int n = 0; n < receipts.size(); n++) {
            var child = new LinkedHashMap<>(receipts.get(n));
            child.put("documentId", doc.id.toString());
            child.put("validate", human || flag(child, "validate") && flag(draft, "validate"));
            var old = identities.get(n);
            if (old != null) child.put("version", old.version);
            var result = book.saveInvoice(old == null ? null : old.id, child, origin, human);
            receipts.get(n).put("id", result.get("id").toString());
            recordUnverifiedItem(doc, "invoice", child, result, human);
            if(human||old==null||!old.manuallyConfirmed||!Boolean.FALSE.equals(receipts.get(n).get("validate")))
              receipts.get(n).put("validate",result.get("reviewStatus")!=ReviewStatus.OPEN);
            results.add(result);
          }
          for (var old : existing)
            if (!identities.contains(old) && (human || !old.manuallyConfirmed))
              book.delete("invoice", old.id, old.version);
        });
    doc.draft = Json.write(draft);
    doc.status =
        results.stream().anyMatch(r -> r.get("reviewStatus") == ReviewStatus.OPEN)
            ? "OPEN"
            : "DONE";
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
    // Historical balance columns are retained in storage only; movements determine validation.
    statement.openingBalance = null;
    statement.closingBalance = null;
    for (String ignored :
        List.of(
            "openingBalance", "closingBalance", "balanceDerivation", "periodStart", "periodEnd"))
      draft.remove(ignored);
    var rows = rows(draft, "movements");
    var dates =
        rows.stream().map(r -> date(r, "bookedOn")).filter(Objects::nonNull).sorted().toList();
    statement.periodStart = dates.isEmpty() ? null : dates.getFirst();
    statement.periodEnd = dates.isEmpty() ? null : dates.getLast();
    var errors = new ArrayList<String>();
    if (rows.isEmpty()) errors.add("Não existem movimentos para confirmar");
    if (statement.accountId == null) errors.add("Falta a conta bancária");
    if (human && flag(draft, "validate") && !errors.isEmpty())
      throw DomainException.invalid(String.join("; ", errors));
    for (var row : rows) {
      row.remove("postBalance");
      row.remove("balance");
    }
    doc.draft = Json.write(draft);
    boolean validate = flag(draft, "validate") && errors.isEmpty();
    book.batch(() -> publishStatementRows(doc, statement, rows, origin, human, validate));
    if (!errors.isEmpty()) review.openAll(doc.id, "document", doc.id, "STATEMENT_INTEGRITY", errors);
    statement.validated = errors.isEmpty() && rows.stream().allMatch(row -> flag(row, "validate"))
        && (human || review.messages(draft.get("issues")).isEmpty()
            && !review.hasPendingReview("document", doc.id));
    doc.status = statement.validated ? "DONE" : "OPEN";
    doc.draft = Json.write(draft);
  }

  private void publishStatementRows(SourceDocument doc, BankStatement statement,
      List<Map<String, Object>> rows, String origin, boolean human, boolean validate) {
    var old = store.all(BankMovement.class).stream()
        .filter(m -> !m.deleted && doc.id.equals(m.documentId)
            && (m.statementId == null || statement.id.equals(m.statementId))).toList();
    var identities = identities(old, rows, book::movementView, m -> m.excluded, "movement");
    for (int n = 0; n < rows.size(); n++) {
      var row = rows.get(n);
      var existing = identities.get(n);
      var input = new LinkedHashMap<>(row);
      input.put("documentId", doc.id);
      input.put("statementId", statement.id);
      input.put("accountId", statement.accountId);
      input.put("validate", human || validate && !Boolean.FALSE.equals(row.get("validate")));
      if (existing != null) input.put("version", existing.version);
      boolean duplicate = !human && duplicateMovement(doc, input);
      if (duplicate) input.put("validate", false);
      var result = book.saveMovement(existing == null ? null : existing.id, input, origin, human);
      UUID id = (UUID) result.get("id");
      if (duplicate) review.open(doc.id, "movement", id, "POSSIBLE_DUPLICATE",
          "Existe um movimento semelhante noutro extrato; confirma a sobreposição");
      if (!duplicate) recordUnverifiedItem(doc, "movement", row, result, human);
      row.put("id", id.toString());
      if (human || existing == null || !existing.manuallyConfirmed || !Boolean.FALSE.equals(row.get("validate")))
        row.put("validate", result.get("reviewStatus") != ReviewStatus.OPEN);
      if (Boolean.TRUE.equals(result.get("manuallyConfirmed"))) row.remove("issues");
    }
    for (var movement : old) {
      if (!identities.contains(movement) && (human || !movement.manuallyConfirmed)) {
        movement.deleted = true;
        review.resolveFor("movement", movement.id);
      }
    }
  }

  private boolean duplicateMovement(SourceDocument doc, Map<String, Object> row) {
    var amount = Money.optional(row.get("amount"));
    var bookedOn = date(row, "bookedOn");
    String description = text(row, "description");
    return amount != null && bookedOn != null && description != null
        && store.all(BankMovement.class).stream().anyMatch(m -> !m.deleted
            && !Objects.equals(m.documentId, doc.id) && Objects.equals(m.accountId, doc.accountId)
            && bookedOn.equals(m.bookedOn) && description.equals(m.description)
            && m.amount != null && m.amount.compareTo(amount) == 0);
  }

  private void recordUnverifiedItem(SourceDocument doc, String type, Map<String, Object> row,
      Map<String, Object> result, boolean human) {
    UUID id = (UUID) result.get("id");
    if (!human && Boolean.FALSE.equals(row.get("validate")) && result.get("reviewStatus") == ReviewStatus.OPEN
        && review.openFor(type, id).isEmpty())
      review.open(doc.id, type, id, "EXTRACTION_REVIEW", "Confirma os dados extraídos.");
  }

  /** Carries human participation decisions by identity, never by a changed extraction's position. */
  private <T extends BaseEntity> List<T> identities(List<T> old, List<Map<String, Object>> rows,
      Function<T, Map<String, Object>> view, Predicate<T> excluded, String type) {
    var selected = new ArrayList<T>(Collections.nCopies(rows.size(), null));
    var used = new HashSet<UUID>();
    var keys = new HashMap<UUID, String>();
    old.forEach(item -> keys.put(item.id, identityKey(view.apply(item), type)));
    for (int n = 0; n < rows.size(); n++) {
      UUID id = uuid(rows.get(n), "id");
      if (id == null) continue;
      var item = old.stream().filter(record -> record.id.equals(id)).findFirst()
          .orElseThrow(() -> DomainException.invalid("O registo indicado não pertence a este documento"));
      if (!used.add(id)) throw DomainException.invalid("O mesmo registo aparece duas vezes no documento");
      selected.set(n, item);
    }
    for (int n = 0; n < rows.size(); n++) {
      if (selected.get(n) != null) continue;
      String key = identityKey(rows.get(n), type);
      if (key == null) continue;
      var item = old.stream().filter(record -> !used.contains(record.id) && key.equals(keys.get(record.id))).findFirst();
      if (item.isPresent()) { selected.set(n, item.get()); used.add(item.get().id); }
    }
    // Bank references can repeat. Prefer the complete facts above, using a reference alone
    // only when exactly one remaining record carries it.
    if (type.equals("movement")) for (int n = 0; n < rows.size(); n++) {
      if (selected.get(n) != null) continue;
      String reference = text(rows.get(n), "bankReference");
      if (reference == null) continue;
      var matches = old.stream().filter(item -> !used.contains(item.id)
          && reference.equals(text(view.apply(item), "bankReference"))).toList();
      if (matches.size() == 1) { selected.set(n, matches.getFirst()); used.add(matches.getFirst().id); }
    }
    if (selected.contains(null) && old.stream().anyMatch(item -> excluded.test(item) && !used.contains(item.id)))
      throw DomainException.invalid("A nova extração não identifica um registo excluído. Edita o registo existente ou volta a incluí-lo antes de extrair novamente; a exclusão não foi transferida para outra linha.");
    // Preserve the former edit behavior for unmatched records without a human exclusion.
    var remaining = old.stream().filter(item -> !used.contains(item.id)).iterator();
    for (int n = 0; n < selected.size(); n++)
      if (selected.get(n) == null && remaining.hasNext()) selected.set(n, remaining.next());
    return selected;
  }

  private String identityKey(Map<String, Object> row, String type) {
    return SourceIdentity.key(row, type);
  }
}

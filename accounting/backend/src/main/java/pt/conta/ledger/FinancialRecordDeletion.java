package pt.conta.ledger;

import static pt.conta.shared.Inputs.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.audit.AuditService;
import pt.conta.bank.BankMovement;
import pt.conta.bank.BankStatement;
import pt.conta.document.FieldProtection;
import pt.conta.document.SourceDeletion;
import pt.conta.document.SourceRecordSync;
import pt.conta.history.HistoryService;
import pt.conta.history.LegacyRow;
import pt.conta.invoice.Invoice;
import pt.conta.reconciliation.Allocation;
import pt.conta.reconciliation.MatchingRules;
import pt.conta.review.Alert;
import pt.conta.review.ReviewService;
import pt.conta.shared.*;
import pt.conta.shared.Types.InvoiceType;
import pt.conta.shared.Types.ReviewStatus;

/** Previews and applies one user-confirmed deletion from the same persisted facts. */
@Service
@Transactional
public class FinancialRecordDeletion {
  public enum Type { INVOICE, MOVEMENT, DOCUMENT }
  public enum Relationship { PAYMENT, REVERSAL, PAYMENT_REFERENCE, ORIGINAL_INVOICE, RELATED_INVOICE, CREDIT_NOTE }
  public record RecordView(Type type, UUID id, long version, String label) {}
  public record Link(RecordView record, Relationship relationship, UUID allocationId, String amount) {}
  public record BalanceChange(RecordView record, LocalDate date, String change) {}
  public record AlertChange(UUID id, long version, String message) {}
  public record UploadChange(UUID id, long version, String filename, SourceDeletion.Action action) {}
  public record Impact(RecordView record, List<Link> links, List<BalanceChange> balances, List<AlertChange> alerts,
      int historicalLinks, UploadChange upload, String impactToken) {}

  private record Plan(RecordView record, BaseEntity target, List<Invoice> dependents, List<Allocation> allocations,
      SourceDeletion.Plan source, List<LegacyRow> historical, Set<UUID> obsoleteTargets, Set<UUID> retiredInvoices) {}

  private final Store store;
  private final BookkeepingService book;
  private final SourceDeletion sources;
  private final SourceRecordSync sync;
  private final HistoryService history;
  private final ReviewService review;
  private final AuditService audit;
  private final FieldProtection protection;

  public FinancialRecordDeletion(Store store, BookkeepingService book, SourceDeletion sources, SourceRecordSync sync,
      HistoryService history, ReviewService review, AuditService audit, FieldProtection protection) {
    this.store = store;
    this.book = book;
    this.sources = sources;
    this.sync = sync;
    this.history = history;
    this.review = review;
    this.audit = audit;
    this.protection = protection;
  }

  public Impact preview(Type type, UUID id) {
    store.lockFinancialWrites();
    return impact(plan(type, id));
  }

  public void delete(Type type, UUID id, long version, String impactToken) {
    store.lockFinancialWrites();
    var plan = plan(type, id);
    store.checkVersion(plan.target, version);
    if (!impact(plan).impactToken.equals(impactToken))
      throw new DomainException(409, "As consequências da eliminação mudaram. Confirma os dados atualizados.");

    review.purgeDeletion(plan.obsoleteTargets, erasedSource(plan));
    if (type != Type.DOCUMENT) markDeleted(plan);
    detachAllocations(plan.allocations);
    sources.apply(plan.source);
    plan.dependents.forEach(this::detachInvoiceReference);
    store.flush();
    if (type != Type.DOCUMENT || !plan.allocations.isEmpty() || !plan.dependents.isEmpty()) book.automatic();
    history.refreshAfterDeletion(plan.retiredInvoices, affectedInvoices(plan.allocations, plan.dependents));
  }

  private Plan plan(Type type, UUID id) {
    if (type == Type.DOCUMENT) return orphanPlan(id);
    var entity = record(type, id);
    return plan(view(entity), entity, sources.forRecord(sourceId(entity), id), new HashSet<>(Set.of(id)));
  }

  private Plan orphanPlan(UUID id) {
    var source = sources.forOrphan(id);
    var doc = source.document();
    return plan(new RecordView(Type.DOCUMENT, id, doc.version, doc.filename), doc, source, new HashSet<>());
  }

  private Plan plan(RecordView target, BaseEntity entity, SourceDeletion.Plan source, Set<UUID> recordsToRemove) {
    if (source != null && source.action() == SourceDeletion.Action.ERASE)
      recordsToRemove.addAll(sources.deletedConsumers(source.document().id));
    var invoices = store.all(Invoice.class);
    var retiredInvoices = invoices.stream().filter(i -> recordsToRemove.contains(i.id)).map(i -> i.id).collect(Collectors.toSet());
    var dependents = invoices.stream().filter(i -> !i.deleted && !retiredInvoices.contains(i.id)
        && retiredInvoices.contains(i.relatedInvoiceId)).toList();
    var allocations = affectedAllocations(recordsToRemove, dependents);
    var impacted = affectedInvoices(allocations, dependents);
    impacted.addAll(retiredInvoices);
    var historical = store.all(LegacyRow.class).stream().filter(row -> impacted.contains(row.invoiceId)).toList();
    return new Plan(target, entity, dependents, allocations, source, historical,
        obsoleteTargets(recordsToRemove, allocations), retiredInvoices);
  }

  private Set<UUID> affectedInvoices(List<Allocation> allocations, List<Invoice> dependents) {
    var invoices = allocations.stream().map(allocation -> allocation.invoiceId).collect(Collectors.toSet());
    dependents.forEach(invoice -> invoices.add(invoice.id));
    return invoices;
  }

  private Set<UUID> obsoleteTargets(Set<UUID> records, List<Allocation> detached) {
    var allocations = store.all(Allocation.class);
    var obsoleteAllocations = detached.stream().map(a -> a.id).collect(Collectors.toSet());
    allocations.stream().filter(a -> records.contains(a.invoiceId) || records.contains(a.movementId))
        .forEach(a -> obsoleteAllocations.add(a.id));
    var result = new HashSet<>(records);
    result.addAll(obsoleteAllocations);
    allocations.stream().filter(a -> obsoleteAllocations.contains(a.reversesId)).forEach(a -> result.add(a.id));
    return result;
  }

  private List<Allocation> affectedAllocations(Set<UUID> records, List<Invoice> dependents) {
    var dependentCreditNotes = dependents.stream().filter(i -> i.type == InvoiceType.CREDIT_NOTE)
        .map(i -> i.id).collect(Collectors.toSet());
    var active = store.all(Allocation.class).stream().filter(a -> a.active).toList();
    var direct = active.stream().filter(a -> records.contains(a.invoiceId) || records.contains(a.movementId)
        || dependentCreditNotes.contains(a.invoiceId)).map(a -> a.id).collect(Collectors.toSet());
    // A reversal can only reverse an original allocation.
    return active.stream().filter(a -> direct.contains(a.id) || direct.contains(a.reversesId)).toList();
  }

  private Impact impact(Plan plan) {
    var links = links(plan);
    var balances = balanceChanges(plan);
    var upload = uploadChange(plan.source);
    var alerts = review.deletionAlerts(plan.obsoleteTargets, erasedSource(plan)).stream()
        .filter(alert -> alert.mergedInto == null).map(FinancialRecordDeletion::alertChange).toList();
    var impact = new Impact(plan.record, links, balances, alerts, plan.historical.size(), upload, null);
    String token = Hashes.sha256(Json.write(List.of(impact,
        plan.allocations.stream().map(a -> List.of(a.id, a.version)).toList(),
        plan.historical.stream().map(row -> List.of(row.id, row.version)).toList())));
    return new Impact(plan.record, links, balances, alerts, plan.historical.size(), upload, token);
  }

  private List<Link> links(Plan plan) {
    var links = new ArrayList<Link>();
    for (var allocation : plan.allocations) {
      addAllocationLink(plan, links, allocation, Type.INVOICE, allocation.invoiceId);
      addAllocationLink(plan, links, allocation, Type.MOVEMENT, allocation.movementId);
    }
    if (plan.target instanceof Invoice invoice && invoice.relatedInvoiceId != null)
      store.find(Invoice.class, invoice.relatedInvoiceId).filter(i -> !i.deleted).ifPresent(original ->
          links.add(new Link(view(original), Relationship.ORIGINAL_INVOICE, null, null)));
    for (var dependent : plan.dependents)
      links.add(new Link(view(dependent), dependent.type == InvoiceType.CREDIT_NOTE ? Relationship.CREDIT_NOTE
          : Relationship.RELATED_INVOICE, null, null));
    if (plan.record.type != Type.DOCUMENT) addPaymentReferences(plan.record, links);
    return List.copyOf(links);
  }

  private void addAllocationLink(Plan plan, List<Link> links, Allocation allocation, Type type, UUID id) {
    if (plan.record.id.equals(id)) return;
    var record = type == Type.INVOICE ? store.find(Invoice.class, id).filter(i -> !i.deleted)
        : store.find(BankMovement.class, id).filter(m -> !m.deleted);
    record.ifPresent(other -> links.add(new Link(view(other), allocation.reversesId == null ? Relationship.PAYMENT
        : Relationship.REVERSAL, allocation.id, Money.text(allocation.amount))));
  }

  private void addPaymentReferences(RecordView target, List<Link> links) {
    var linked = links.stream().map(link -> link.record.id).collect(Collectors.toSet());
    boolean invoiceTarget = target.type == Type.INVOICE;
    var candidates = book.candidates(invoiceTarget ? target.id : null, invoiceTarget ? null : target.id);
    for (var candidate : candidates) {
      UUID invoice = (UUID) candidate.get("invoiceId");
      UUID movement = (UUID) candidate.get("movementId");
      UUID other = invoiceTarget ? movement : invoice;
      if (linked.contains(other)) continue;
      if (!MatchingRules.reference(store.get(Invoice.class, invoice).number, text(candidate, "movementDescription"))) continue;
      links.add(new Link(view(record(invoiceTarget ? Type.MOVEMENT : Type.INVOICE, other)),
          Relationship.PAYMENT_REFERENCE, null, text(candidate, "amount")));
    }
  }

  private List<BalanceChange> balanceChanges(Plan plan) {
    var changes = new ArrayList<BalanceChange>();
    switch (plan.target) {
      case Invoice invoice -> addBalanceChange(changes, invoice, invoice.issuedOn, BookkeepingService.invoiceEffect(invoice));
      case BankMovement movement -> addBalanceChange(changes, movement, movement.bookedOn, BookkeepingService.movementEffect(movement));
      default -> { }
    }
    for (var invoice : plan.dependents)
      if (invoice.type == InvoiceType.CREDIT_NOTE)
        addBalanceChange(changes, invoice, invoice.issuedOn, BookkeepingService.invoiceEffect(invoice));
    return List.copyOf(changes);
  }

  private void addBalanceChange(List<BalanceChange> changes, BaseEntity record, LocalDate date, BigDecimal effect) {
    if (effect != null && effect.signum() != 0) changes.add(new BalanceChange(view(record), date, Money.text(effect.negate())));
  }

  private BaseEntity record(Type type, UUID id) {
    return switch (type) {
      case INVOICE -> store.find(Invoice.class, id).filter(i -> !i.deleted).orElseThrow(DomainException::missing);
      case MOVEMENT -> store.find(BankMovement.class, id).filter(m -> !m.deleted).orElseThrow(DomainException::missing);
      case DOCUMENT -> throw new IllegalArgumentException("Expected a financial record");
    };
  }

  private RecordView view(BaseEntity record) {
    return switch (record) {
      case Invoice i -> new RecordView(Type.INVOICE, i.id, i.version,
          i.number == null ? Objects.requireNonNullElse(i.supplier, "Fatura")
              : i.supplier == null ? i.number : i.number + " · " + i.supplier);
      case BankMovement m -> new RecordView(Type.MOVEMENT, m.id, m.version, Objects.requireNonNullElse(m.description, "Movimento"));
      default -> throw new IllegalArgumentException("Expected a financial record");
    };
  }

  private UUID sourceId(BaseEntity record) {
    if (record instanceof Invoice invoice) return invoice.documentId;
    var movement = (BankMovement) record;
    if (movement.documentId != null || movement.statementId == null) return movement.documentId;
    return store.get(BankStatement.class, movement.statementId).documentId;
  }

  private static AlertChange alertChange(Alert alert) { return new AlertChange(alert.id, alert.version, alert.message); }

  private static UploadChange uploadChange(SourceDeletion.Plan plan) {
    if (plan == null) return null;
    var doc = plan.document();
    return new UploadChange(doc.id, doc.version, doc.filename, plan.action());
  }

  private static UUID erasedSource(Plan plan) {
    return plan.source != null && plan.source.action() == SourceDeletion.Action.ERASE ? plan.source.document().id : null;
  }

  private void markDeleted(Plan plan) {
    Object before;
    if (plan.target instanceof Invoice invoice) {
      before = book.invoiceView(invoice);
      invoice.deleted = true;
    } else {
      var movement = (BankMovement) plan.target;
      before = book.movementView(movement);
      movement.deleted = true;
    }
    audit.record("DELETE", plan.record.type.name().toLowerCase(Locale.ROOT), plan.target.id, before, null);
  }

  private void detachAllocations(List<Allocation> allocations) {
    for (var allocation : allocations) {
      var before = book.allocationView(allocation);
      allocation.active = false;
      audit.record("DETACH_DELETED", "allocation", allocation.id, before, book.allocationView(allocation));
    }
  }

  private void detachInvoiceReference(Invoice invoice) {
    var before = book.invoiceView(invoice);
    var edit = sync.prepare(invoice, Map.of());
    var metadata = protection.human("INVOICE", before, map("relatedInvoiceId", null), Json.object(invoice.fieldProvenance), false);
    invoice.relatedInvoiceId = null;
    invoice.fieldProvenance = Json.write(metadata);
    if (invoice.type == InvoiceType.CREDIT_NOTE) {
      invoice.reviewStatus = ReviewStatus.OPEN;
      invoice.manuallyConfirmed = false;
      review.open(invoice.documentId, "invoice", invoice.id, "ORIGINAL_INVOICE_DELETED", "Escolhe a fatura original da nota de crédito.");
    }
    sync.sync(edit, invoice, book.invoiceErrors(invoice));
    audit.record("DETACH_INVOICE_REFERENCE", "invoice", invoice.id, before, book.invoiceView(invoice));
  }
}

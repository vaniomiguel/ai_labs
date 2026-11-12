package pt.conta.ledger;

import static pt.conta.shared.Inputs.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.conta.audit.AuditService;
import pt.conta.bank.*;
import pt.conta.document.SourceDocument;
import pt.conta.invoice.*;
import pt.conta.jobs.JobQueue;
import pt.conta.reconciliation.*;
import pt.conta.review.ReviewService;
import pt.conta.shared.*;
import pt.conta.shared.Types.*;

/** Coordinates transactional edits, monetary allocations and append-only financial effects. */
@Service
@Transactional
public class BookkeepingService {
  private final ThreadLocal<Boolean> batching = ThreadLocal.withInitial(() -> false);
  private final Store store;
  private final LedgerService ledger;
  private final AuditService audit;
  private final ReviewService review;
  private final Settings settings;
  private final AccountingProperties properties;
  private final JobQueue jobs;
  private final pt.conta.ai.AiReviewStatus aiReview;

  public BookkeepingService(
      Store store,
      LedgerService ledger,
      AuditService audit,
      ReviewService review,
      Settings settings,
      AccountingProperties properties,
      JobQueue jobs,
      pt.conta.ai.AiReviewStatus aiReview) {
    this.store = store;
    this.ledger = ledger;
    this.audit = audit;
    this.review = review;
    this.settings = settings;
    this.properties = properties;
    this.jobs = jobs;
    this.aiReview = aiReview;
  }

  /** Publishes all rows of a statement before matching or checking their aggregate effects. */
  public void batch(Runnable action) {
    boolean previous = batching.get();
    batching.set(true);
    try {
      action.run();
    } finally {
      batching.set(previous);
    }
    if (!previous) {
      store.flush();
      synchronize();
      automatic();
    }
  }

  /** Creates or edits an invoice; drafts never contribute to balances. */
  public Map<String, Object> saveInvoice(UUID id, Map<String, Object> input) {
    return saveInvoice(id, input, "MANUAL", true);
  }

  /** Publishes extraction data through the same validations as a manual edit. */
  public Map<String, Object> saveInvoice(
      UUID id, Map<String, Object> input, String origin, boolean human) {
    store.lockFinancialWrites();
    Invoice invoice = id == null ? store.add(new Invoice()) : store.get(Invoice.class, id);
    Object before = id == null ? null : invoiceView(invoice);
    if (id != null && human) store.checkVersion(invoice, version(input));
    var previous = invoice.reviewStatus;
    invoice.issuedOn = date(input, "issuedOn");
    invoice.dueOn = date(input, "dueOn");
    invoice.number = text(input, "number");
    invoice.supplier = text(input, "supplier");
    invoice.supplierTaxId = text(input, "supplierTaxId");
    invoice.description = text(input, "description");
    invoice.currency = Objects.requireNonNullElse(text(input, "currency"), "EUR");
    invoice.total = Money.optional(input.get("total"));
    invoice.net = Money.optional(input.get("net"));
    invoice.tax = Money.optional(input.get("tax"));
    invoice.discount = Money.optional(input.get("discount"));
    invoice.type = enumeration(input, "type", InvoiceType.class, InvoiceType.INVOICE);
    invoice.relatedInvoiceId = uuid(input, "relatedInvoiceId");
    if (input.containsKey("documentId")) invoice.documentId = uuid(input, "documentId");
    if (invoice.documentId != null) store.get(SourceDocument.class, invoice.documentId);
    invoice.origin = origin;
    invoice.author = human ? audit.actor() : origin;
    invoice.manuallyConfirmed = human && flag(input, "validate");
    invoice.categoryIds.clear();
    Object tags = input.get("categories");
    if (tags instanceof List<?> list)
      for (Object tag : list) {
        String name = tag.toString().trim();
        if (!name.isBlank()) invoice.categoryIds.add(category(name).id);
      }
    store.all(InvoiceLine.class).stream()
        .filter(l -> l.invoiceId.equals(invoice.id))
        .toList()
        .forEach(store::remove);
    int index = 0;
    for (var item : rows(input, "lines")) {
      var line = new InvoiceLine();
      line.invoiceId = invoice.id;
      line.position = index++;
      line.description = text(item, "description");
      line.quantity = precise(item.get("quantity"));
      line.unitPrice = precise(item.get("unitPrice"));
      line.net = Money.optional(item.get("net"));
      line.tax = Money.optional(item.get("tax"));
      line.discount = Money.optional(item.get("discount"));
      line.total = Money.optional(item.get("total"));
      store.add(line);
    }
    var errors = invoiceErrors(invoice);
    boolean requested = flag(input, "validate");
    if (human && requested && !errors.isEmpty())
      throw DomainException.invalid(String.join("; ", errors));
    invoice.reviewStatus =
        requested && errors.isEmpty()
            ? human
                ? id == null
                    ? ReviewStatus.MANUAL
                    : previous == ReviewStatus.OPEN ? ReviewStatus.CORRECTED : ReviewStatus.EDITED
                : ReviewStatus.AUTOMATIC
            : ReviewStatus.OPEN;
    if (invoice.reviewStatus == ReviewStatus.OPEN && !errors.isEmpty())
      review.open(
          invoice.documentId, "invoice", invoice.id, "INVALID_INVOICE", String.join("; ", errors));
    else if (invoice.reviewStatus != ReviewStatus.OPEN) review.resolveFor("invoice", invoice.id);
    store.flush();
    if (!batching.get()) {
      synchronize();
      automatic();
    }
    jobs.enqueue("REVIEW_INVOICE", invoice.id, invoice.version, "{}");
    if (!human && !invoice.categoryIds.isEmpty())
      jobs.enqueue("NORMALIZE_CATEGORIES", invoice.id, invoice.version, "{}");
    if (human && invoice.documentId != null) {
      var doc = store.get(SourceDocument.class, invoice.documentId);
      if (doc.kind.equals("INVOICE")) {
        doc.draft = Json.write(invoiceView(invoice));
        doc.status = invoice.reviewStatus == ReviewStatus.OPEN ? "OPEN" : "DONE";
      }
    }
    audit.record(
        id == null ? "CREATE" : "UPDATE", "invoice", invoice.id, before, invoiceView(invoice));
    return invoiceView(invoice);
  }

  /**
   * Creates or edits a bank movement, keeping its classification separate from its signed effect.
   */
  public Map<String, Object> saveMovement(UUID id, Map<String, Object> input) {
    return saveMovement(id, input, "MANUAL", true);
  }

  /** Publishes a parsed movement, retaining source statement and document identities. */
  public Map<String, Object> saveMovement(
      UUID id, Map<String, Object> input, String origin, boolean human) {
    store.lockFinancialWrites();
    var movement = id == null ? store.add(new BankMovement()) : store.get(BankMovement.class, id);
    Object before = id == null ? null : movementView(movement);
    if (id != null && human) store.checkVersion(movement, version(input));
    var previous = movement.reviewStatus;
    movement.accountId = uuid(input, "accountId");
    if (movement.accountId != null) store.get(BankAccount.class, movement.accountId);
    if (input.containsKey("documentId")) movement.documentId = uuid(input, "documentId");
    if (input.containsKey("statementId")) movement.statementId = uuid(input, "statementId");
    movement.bankReference = text(input, "bankReference");
    movement.bookedOn = date(input, "bookedOn");
    movement.valueOn = date(input, "valueOn");
    movement.occurredAt =
        text(input, "occurredAt") == null ? null : LocalTime.parse(text(input, "occurredAt"));
    movement.description = text(input, "description");
    movement.amount = Money.optional(input.get("amount"));
    movement.currency = Objects.requireNonNullElse(text(input, "currency"), "EUR");
    movement.direction =
        input.containsKey("direction") && text(input, "direction") == null
            ? null
            : enumeration(input, "direction", Direction.class, human ? Direction.DEBIT : null);
    movement.kind = enumeration(input, "kind", MovementKind.class, MovementKind.UNCLASSIFIED);
    movement.origin = origin;
    movement.author = human ? audit.actor() : origin;
    movement.manuallyConfirmed = human && flag(input, "validate");
    var errors = movementErrors(movement);
    if (human && flag(input, "validate") && !errors.isEmpty())
      throw DomainException.invalid(String.join("; ", errors));
    movement.reviewStatus =
        flag(input, "validate") && errors.isEmpty()
            ? human
                ? id == null
                    ? ReviewStatus.MANUAL
                    : previous == ReviewStatus.OPEN ? ReviewStatus.CORRECTED : ReviewStatus.EDITED
                : ReviewStatus.AUTOMATIC
            : ReviewStatus.OPEN;
    if (movement.reviewStatus == ReviewStatus.OPEN && !errors.isEmpty())
      review.open(
          movement.documentId,
          "movement",
          movement.id,
          "INVALID_MOVEMENT",
          String.join("; ", errors));
    else if (movement.reviewStatus != ReviewStatus.OPEN) review.resolveFor("movement", movement.id);
    if (!batching.get() && movement.statementId != null && !errors.isEmpty()) {
      var statement = store.get(BankStatement.class, movement.statementId);
      statement.validated = false;
      store.all(BankMovement.class).stream()
          .filter(m -> movement.statementId.equals(m.statementId))
          .forEach(m -> m.reviewStatus = ReviewStatus.OPEN);
      review.open(
          statement.documentId,
          "document",
          statement.documentId,
          "STATEMENT_INTEGRITY",
          String.join("; ", errors));
    }
    store.flush();
    if (!batching.get()) {
      synchronize();
      automatic();
    }
    jobs.enqueue("REVIEW_MOVEMENT", movement.id, movement.version, "{}");
    if (!batching.get() && human && movement.statementId != null) {
      var statement = store.get(BankStatement.class, movement.statementId);
      var doc = store.get(SourceDocument.class, statement.documentId);
      var draft = Json.object(doc.draft);
      draft.put(
          "movements",
          store.all(BankMovement.class).stream()
              .filter(m -> movement.statementId.equals(m.statementId) && !m.deleted)
              .map(this::movementView)
              .toList());
      doc.draft = Json.write(draft);
      doc.status = statement.validated ? "DONE" : "OPEN";
    }
    audit.record(
        id == null ? "CREATE" : "UPDATE", "movement", movement.id, before, movementView(movement));
    return movementView(movement);
  }

  /** Reports invoice inconsistencies, including incomplete or inconsistent article totals. */
  public List<String> invoiceErrors(Invoice invoice) {
    var errors = new ArrayList<String>();
    if (invoice.issuedOn == null) errors.add("Falta a data da fatura");
    if (invoice.supplier == null) errors.add("Falta o fornecedor");
    if (invoice.total == null || invoice.total.signum() < 0) errors.add("Falta um total válido");
    if (!"EUR".equals(invoice.currency))
      errors.add("A moeda precisa de revisão: apenas EUR é suportado");
    if (invoice.net != null
        && invoice.tax != null
        && invoice.total != null
        && invoice.net.add(invoice.tax).compareTo(invoice.total) != 0)
      errors.add("Base e IVA não coincidem com o total");
    var lines =
        store.all(InvoiceLine.class).stream().filter(l -> l.invoiceId.equals(invoice.id)).toList();
    if (lines.stream().anyMatch(l -> l.total == null)) errors.add("Existem artigos sem total");
    if (lines.stream()
        .anyMatch(
            l ->
                l.net != null
                    && l.tax != null
                    && l.total != null
                    && l.net.add(l.tax).compareTo(l.total) != 0))
      errors.add("A base e o IVA de um artigo não coincidem com o seu total");
    if (!lines.isEmpty()
        && lines.stream().allMatch(l -> l.total != null)
        && invoice.total != null) {
      var sum = lines.stream().map(l -> l.total).reduce(Money.ZERO, BigDecimal::add);
      if (sum.compareTo(invoice.total) != 0
          && sum.subtract(Money.orZero(invoice.discount)).compareTo(invoice.total) != 0)
        errors.add("Os artigos não explicam o total da fatura");
    }
    if (invoice.type == InvoiceType.CREDIT_NOTE && invoice.relatedInvoiceId == null)
      errors.add("A nota de crédito precisa da fatura original");
    if (invoice.relatedInvoiceId != null && invoice.relatedInvoiceId.equals(invoice.id))
      errors.add("Uma fatura não pode referir-se a si própria");
    else if (invoice.relatedInvoiceId != null) {
      var original = store.get(Invoice.class, invoice.relatedInvoiceId);
      if (original.deleted
          || original.type != InvoiceType.INVOICE
          || !original.currency.equals(invoice.currency))
        errors.add("A fatura original é incompatível com a nota de crédito");
    }
    var duplicate =
        store.all(Invoice.class).stream()
            .anyMatch(
                i ->
                    !i.deleted
                        && !i.id.equals(invoice.id)
                        && invoice.number != null
                        && invoice.number.equals(i.number)
                        && Objects.equals(
                            invoice.supplierTaxId != null
                                ? invoice.supplierTaxId
                                : MatchingRules.normalize(invoice.supplier),
                            i.supplierTaxId != null
                                ? i.supplierTaxId
                                : MatchingRules.normalize(i.supplier)));
    if (duplicate) errors.add("Existe outra fatura com o mesmo fornecedor e número");
    return errors;
  }

  /** Reports required movement data, statement integrity and classification-direction conflicts. */
  public List<String> movementErrors(BankMovement movement) {
    var errors = new ArrayList<String>();
    if (movement.accountId == null) errors.add("Falta a conta bancária");
    if (movement.bookedOn == null) errors.add("Falta a data do movimento");
    if (movement.description == null) errors.add("Falta a descrição");
    if (movement.direction == null) errors.add("Falta indicar débito ou crédito");
    if (movement.amount == null || movement.amount.signum() <= 0)
      errors.add("Falta um montante positivo");
    if (!"EUR".equals(movement.currency)) errors.add("Apenas EUR é suportado");
    if (Set.of(MovementKind.REIMBURSEMENT, MovementKind.ADVANCE, MovementKind.INVOICE_PAYMENT)
            .contains(movement.kind)
        && movement.direction != Direction.DEBIT) errors.add("A classificação exige um débito");
    if (Set.of(MovementKind.RETURN, MovementKind.REFUND).contains(movement.kind)
        && movement.direction != Direction.CREDIT) errors.add("A classificação exige um crédito");
    if (movement.statementId != null) {
      var statement = store.get(BankStatement.class, movement.statementId);
      if (!statement.validated) errors.add("O extrato tem inconsistências por resolver");
    }
    return errors;
  }

  /** Soft-deletes an invoice or movement and reverses every directly affected financial effect. */
  public void delete(String type, UUID id, long version) {
    store.lockFinancialWrites();
    BaseEntity entity =
        type.equals("invoice") ? store.get(Invoice.class, id) : store.get(BankMovement.class, id);
    store.checkVersion(entity, version);
    Object before =
        type.equals("invoice")
            ? invoiceView((Invoice) entity)
            : movementView((BankMovement) entity);
    if (entity instanceof Invoice i) i.deleted = true;
    else ((BankMovement) entity).deleted = true;
    store.flush();
    synchronize();
    automatic();
    audit.record("DELETE", type, id, before, null);
  }

  /**
   * Creates a checked partial allocation; monetary values and dates cannot be bypassed manually.
   */
  public Map<String, Object> allocate(Map<String, Object> input) {
    store.lockFinancialWrites();
    var invoice = store.get(Invoice.class, uuid(input, "invoiceId"));
    var movement = store.get(BankMovement.class, uuid(input, "movementId"));
    BigDecimal amount = Money.positive(input.get("amount"));
    UUID reverses = uuid(input, "reversesId");
    var allocation =
        createAllocation(
            invoice,
            movement,
            amount,
            reverses,
            "MANUAL",
            Objects.requireNonNullElse(text(input, "evidence"), "Confirmado pelo utilizador"));
    store.flush();
    synchronize();
    audit.record("ALLOCATE", "allocation", allocation.id, null, allocationView(allocation));
    return allocationView(allocation);
  }

  /** Reverts an allocation; reversing transactions must be detached first to preserve causality. */
  public void unallocate(UUID id) {
    store.lockFinancialWrites();
    var allocation = store.get(Allocation.class, id);
    if (store.all(Allocation.class).stream().anyMatch(a -> a.active && id.equals(a.reversesId)))
      throw DomainException.invalid("Remove primeiro os estornos associados");
    allocation.active = false;
    allocation.evidence = allocation.evidence + " · desfeito por " + audit.actor();
    // The same source revision must not immediately recreate an explicitly rejected match.
    settings.set("rejected:" + allocation.invoiceId + ":" + allocation.movementId, "true");
    store.flush();
    synchronize();
    audit.record("UNALLOCATE", "allocation", id, null, allocationView(allocation));
  }

  /** Changes participation atomically without rejecting any historical matching evidence. */
  public void exclude(ExclusionRequest request) {
    store.lockFinancialWrites();
    var entities = new LinkedHashMap<ExclusionRequest.Item, BaseEntity>();
    var seen = new HashSet<UUID>();
    for (var item : request.items()) {
      if (!seen.add(item.id())) throw DomainException.invalid("Registo repetido na seleção");
      BaseEntity entity = item.type().equals("invoice")
          ? store.get(Invoice.class, item.id()) : store.get(BankMovement.class, item.id());
      store.checkVersion(entity, item.version());
      if (entity instanceof Invoice i ? i.deleted : ((BankMovement) entity).deleted)
        throw DomainException.invalid("Um registo eliminado não pode ser incluído ou excluído");
      entities.put(item, entity);
    }
    for (var selected : entities.entrySet()) {
      var item = selected.getKey();
      var entity = selected.getValue();
      boolean previous = entity instanceof Invoice i ? i.excluded : ((BankMovement) entity).excluded;
      if (previous == request.excluded()) continue;
      if (entity instanceof Invoice i) i.excluded = request.excluded();
      else ((BankMovement) entity).excluded = request.excluded();
      if (!request.excluded()) {
        var errors = entity instanceof Invoice i ? invoiceErrors(i) : movementErrors((BankMovement) entity);
        if (!errors.isEmpty()) {
          UUID doc;
          if (entity instanceof Invoice i) { i.reviewStatus = ReviewStatus.OPEN; doc = i.documentId; }
          else { var m = (BankMovement) entity; m.reviewStatus = ReviewStatus.OPEN; doc = m.documentId; }
          review.open(doc, item.type(), item.id(), "INVALID_REINCLUDED_RECORD", String.join("; ", errors));
        }
      }
      audit.record(request.excluded() ? "EXCLUDE" : "INCLUDE", item.type(), item.id(),
          map("excluded", previous), map("excluded", request.excluded(), "reason", request.reason()));
    }
    // Detach intentionally, including dependent reversals, without creating invalid-data alerts
    // or permanent rejected-pair settings. Other manual decisions remain protected.
    var detached = new HashSet<UUID>();
    boolean changed;
    do {
      changed = false;
      for (var a : store.all(Allocation.class)) {
        if (a.active && (store.get(Invoice.class, a.invoiceId).excluded
            || store.get(BankMovement.class, a.movementId).excluded
            || detached.contains(a.reversesId))) {
          a.active = false;
          detached.add(a.id);
          review.resolveFor("allocation", a.id);
          audit.record("DETACH_EXCLUDED", "allocation", a.id, null, allocationView(a));
          changed = true;
        }
      }
    } while (changed);
    store.flush();
    synchronize();
    automatic();
    for (var selected : entities.entrySet()) {
      if (!request.excluded()) {
        var entity = selected.getValue();
        jobs.enqueue(entity instanceof Invoice ? "REVIEW_INVOICE" : "REVIEW_MOVEMENT",
            entity.id, entity.version, "{}");
      }
    }
  }

  /** Invalidates allocations affected by factual changes and updates append-only ledger effects. */
  public void synchronize() {
    store.lockFinancialWrites();
    var invoices = store.all(Invoice.class);
    var movements = store.all(BankMovement.class);
    var allocations = store.all(Allocation.class);
    var invoiceMap = new HashMap<UUID, Invoice>();
    invoices.forEach(i -> invoiceMap.put(i.id, i));
    var movementMap = new HashMap<UUID, BankMovement>();
    movements.forEach(m -> movementMap.put(m.id, m));
    var invoiceUsed = new HashMap<UUID, BigDecimal>();
    var movementUsed = new HashMap<UUID, BigDecimal>();
    var reversedUsed = new HashMap<UUID, BigDecimal>();
    for (var allocation :
        allocations.stream().sorted(Comparator.comparing((Allocation a) -> a.createdAt)).toList()) {
      var invoice = invoiceMap.get(allocation.invoiceId);
      var movement = movementMap.get(allocation.movementId);
      if (allocation.active) {
        boolean valid =
            invoice != null
                && movement != null
                && eligible(invoice)
                && eligible(movement)
                && MatchingRules.eligible(invoice.issuedOn, movement.bookedOn, window())
                && compatible(invoice, movement, allocation.reversesId != null)
                && movementUsed
                        .getOrDefault(movement.id, Money.ZERO)
                        .add(allocation.amount)
                        .compareTo(movement.amount)
                    <= 0;
        if (valid && allocation.reversesId == null)
          valid =
              invoiceUsed
                      .getOrDefault(invoice.id, Money.ZERO)
                      .add(allocation.amount)
                      .compareTo(invoice.total)
                  <= 0;
        if (valid && allocation.reversesId != null) {
          var original =
              allocations.stream()
                  .filter(a -> a.id.equals(allocation.reversesId))
                  .findFirst()
                  .orElse(null);
          valid =
              original != null
                  && original.active
                  && original.reversesId == null
                  && original.invoiceId.equals(invoice.id)
                  && reversedUsed
                          .getOrDefault(original.id, Money.ZERO)
                          .add(allocation.amount)
                          .compareTo(original.amount)
                      <= 0;
          if (valid) reversedUsed.merge(original.id, allocation.amount, BigDecimal::add);
        }
        if (!valid) {
          allocation.active = false;
          review.open(
              invoice == null ? null : invoice.documentId,
              "allocation",
              allocation.id,
              "INVALIDATED_ALLOCATION",
              "Uma alteração invalidou a associação. Confirma os dados e volta a reconciliar.");
        } else {
          movementUsed.merge(movement.id, allocation.amount, BigDecimal::add);
          invoiceUsed.merge(
              invoice.id,
              allocation.reversesId == null ? allocation.amount : allocation.amount.negate(),
              BigDecimal::add);
        }
      }
      ledger.replace(
          "ALLOCATION",
          allocation.id,
          allocation.version,
          null,
          null,
          "O movimento bancário regista o efeito financeiro integral");
    }
    for (var invoice : invoices)
      ledger.replace(
          "INVOICE",
          invoice.id,
          invoice.version,
          eligible(invoice) ? invoice.issuedOn : null,
          invoiceEffect(invoice),
          "Fatura " + Objects.requireNonNullElse(invoice.number, invoice.id.toString()));
    for (var movement : movements) {
      BigDecimal effect = movementEffect(movement);
      ledger.replace(
          "MOVEMENT",
          movement.id,
          movement.version,
          effect == null ? null : movement.bookedOn,
          effect,
          "Movimento bancário: " + movement.direction);
    }
    store.flush();
  }

  /**
   * Matches only a unique evidence-supported candidate on both sides, never equal amounts alone.
   */
  public void automatic() {
    store.lockFinancialWrites();
    reassessAutomaticConfidence();
    store.flush();
    synchronize();
    var candidates = candidates();
    for (var candidate : candidates) {
      if (!(boolean) candidate.get("strong")) continue;
      UUID invoiceId = (UUID) candidate.get("invoiceId"),
          movementId = (UUID) candidate.get("movementId");
      long invoiceChoices =
          candidates.stream()
              .filter(
                  c -> Boolean.TRUE.equals(c.get("strong")) && c.get("invoiceId").equals(invoiceId))
              .count();
      long movementChoices =
          candidates.stream()
              .filter(
                  c ->
                      Boolean.TRUE.equals(c.get("strong"))
                          && c.get("movementId").equals(movementId))
              .count();
      if (invoiceChoices == 1 && movementChoices == 1) {
        var invoice = store.get(Invoice.class, invoiceId);
        var movement = store.get(BankMovement.class, movementId);
        var amount = remaining(movement).min(remaining(invoice));
        if (amount.signum() > 0)
          createAllocation(
              invoice, movement, amount, null, "RULES", candidate.get("reason").toString());
      }
    }
    automaticGroups();
    store.flush();
    synchronize();
    for (var invoice : store.all(Invoice.class))
      if (eligible(invoice) && remaining(invoice).signum() > 0) {
        var context = candidateContext(invoice.id, candidates);
        if (context.isEmpty()) continue;
        String fingerprint = Hashes.sha256(Json.write(context));
        jobs.enqueue(
            "SUGGEST_RECONCILIATION",
            invoice.id,
            invoice.version,
            Json.write(map("fingerprint", fingerprint)),
            fingerprint);
      }
  }

  /** Produces bounded, explainable candidate data for UI and AI review. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> candidates() {
    var result = new ArrayList<Map<String, Object>>();
    var invoiceAvailable = new HashMap<UUID, BigDecimal>();
    var movementAvailable = new HashMap<UUID, BigDecimal>();
    var invoices = store.all(Invoice.class);
    var movements = store.all(BankMovement.class);
    var allocations = store.all(Allocation.class);
    invoices.forEach(i -> invoiceAvailable.put(i.id, Money.orZero(i.total)));
    movements.forEach(m -> movementAvailable.put(m.id, Money.orZero(m.amount)));
    for (var a : allocations)
      if (a.active) {
        invoiceAvailable.merge(
            a.invoiceId, a.reversesId == null ? a.amount.negate() : a.amount, BigDecimal::add);
        movementAvailable.merge(a.movementId, a.amount.negate(), BigDecimal::add);
      }
    int months = window();
    var rejected = new HashSet<String>();
    store.all(AppSetting.class).stream()
        .filter(s -> s.name.startsWith("rejected:") && s.value.equals("true"))
        .forEach(s -> rejected.add(s.name));
    for (var invoice : invoices)
      if (eligible(invoice) && invoiceAvailable.get(invoice.id).signum() > 0) {
        for (var movement : movements)
          if (eligible(movement)
              && compatible(invoice, movement, false)
              && movementAvailable.get(movement.id).signum() > 0
              && MatchingRules.eligible(invoice.issuedOn, movement.bookedOn, months)
              && !rejected.contains("rejected:" + invoice.id + ":" + movement.id)) {
            boolean ref = MatchingRules.reference(invoice.number, movement.description),
                supplier = MatchingRules.supplier(invoice.supplier, movement.description);
            boolean exact =
                invoiceAvailable.get(invoice.id).compareTo(movementAvailable.get(movement.id)) == 0;
            if (!exact && !ref && !supplier) continue;
            boolean strong = ref || supplier && exact;
            result.add(
                map(
                    "invoiceId",
                    invoice.id,
                    "movementId",
                    movement.id,
                    "movementDescription",
                    movement.description,
                    "amount",
                    Money.text(
                        movementAvailable.get(movement.id).min(invoiceAvailable.get(invoice.id))),
                    "strong",
                    strong,
                    "score",
                    (ref ? 100 : supplier ? 80 : 20)
                        + (int)
                            Math.max(
                                0,
                                10
                                    - Math.abs(
                                            java.time.temporal.ChronoUnit.DAYS.between(
                                                invoice.issuedOn, movement.bookedOn))
                                        / 30),
                    "reason",
                    ref
                        ? "Referência da fatura presente no movimento"
                        : supplier
                            ? "Fornecedor identificado e montante compatível"
                            : "Apenas montante compatível; requer confirmação"));
          }
      }
    result.sort(Comparator.comparingInt(c -> -(int) c.get("score")));
    return result;
  }

  /** Builds a stable bounded candidate snapshot with bank revisions for non-binding AI ranking. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> candidateContext(
      UUID invoiceId, List<Map<String, Object>> candidates) {
    return candidates.stream()
        .filter(c -> c.get("invoiceId").equals(invoiceId))
        .filter(
            c -> remaining(store.get(BankMovement.class, (UUID) c.get("movementId"))).signum() > 0)
        .limit(20)
        .map(
            c -> {
              var movement =
                  movementView(store.get(BankMovement.class, (UUID) c.get("movementId")));
              movement.remove("aiReview");
              return map("candidate", c, "movement", movement);
            })
        .toList();
  }

  /** Returns a safe invoice DTO, including individually editable articles and remaining amount. */
  @Transactional(readOnly = true)
  public Map<String, Object> invoiceView(Invoice supplied) {
    var i = store.get(Invoice.class, supplied.id);
    var categories =
        store.all(Category.class).stream()
            .filter(c -> i.categoryIds.contains(c.id))
            .map(c -> c.name)
            .toList();
    var lines =
        store.all(InvoiceLine.class).stream()
            .filter(l -> l.invoiceId.equals(i.id))
            .sorted(Comparator.comparingInt(l -> l.position))
            .map(
                l ->
                    map(
                        "description",
                        l.description,
                        "quantity",
                        l.quantity == null ? null : l.quantity.toPlainString(),
                        "unitPrice",
                        l.unitPrice == null ? null : l.unitPrice.toPlainString(),
                        "net",
                        Money.text(l.net),
                        "tax",
                        Money.text(l.tax),
                        "discount",
                        Money.text(l.discount),
                        "total",
                        Money.text(l.total)))
            .toList();
    var remaining = remaining(i);
    return map(
        "id",
        i.id,
        "version",
        i.version,
        "issuedOn",
        i.issuedOn,
        "dueOn",
        i.dueOn,
        "number",
        i.number,
        "supplier",
        i.supplier,
        "supplierTaxId",
        i.supplierTaxId,
        "description",
        i.description,
        "currency",
        i.currency,
        "total",
        Money.text(i.total),
        "net",
        Money.text(i.net),
        "tax",
        Money.text(i.tax),
        "discount",
        Money.text(i.discount),
        "type",
        i.type,
        "relatedInvoiceId",
        i.relatedInvoiceId,
        "documentId",
        i.documentId,
        "reviewStatus",
        i.reviewStatus,
        "excluded",
        i.excluded,
        "origin",
        i.origin,
        "author",
        i.author,
        "updatedAt",
        i.updatedAt,
        "categories",
        categories,
        "lines",
        lines,
        "remaining",
        Money.text(i.total == null ? null : remaining),
        "reconciliationStatus",
        i.excluded ? "EXCLUDED" : i.reviewStatus == ReviewStatus.OPEN ? "PENDING_REVIEW"
            : i.total != null && remaining.signum() == 0
            ? "RECONCILED"
            : i.total != null && remaining.compareTo(i.total) < 0 ? "PARTIAL" : "UNMATCHED_INVOICE",
        "aiReview",
        aiReview.describe(i.id, i.version));
  }

  /** Returns a safe bank DTO with monetary strings and nullable original timestamps. */
  @Transactional(readOnly = true)
  public Map<String, Object> movementView(BankMovement m) {
    return map(
        "id",
        m.id,
        "version",
        m.version,
        "accountId",
        m.accountId,
        "statementId",
        m.statementId,
        "documentId",
        m.documentId,
        "bankReference",
        m.bankReference,
        "bookedOn",
        m.bookedOn,
        "valueOn",
        m.valueOn,
        "occurredAt",
        m.occurredAt,
        "description",
        m.description,
        "amount",
        Money.text(m.amount),
        "currency",
        m.currency,
        "direction",
        m.direction,
        "kind",
        m.kind,
        "reviewStatus",
        m.reviewStatus,
        "excluded",
        m.excluded,
        "origin",
        m.origin,
        "author",
        m.author,
        "updatedAt",
        m.updatedAt,
        "remaining",
        Money.text(m.amount == null ? null : remaining(m)),
        "aiReview",
        aiReview.describe(m.id, m.version));
  }

  /** Exposes association amounts and evidence without returning persistence entities. */
  public Map<String, Object> allocationView(Allocation a) {
    return map(
        "id",
        a.id,
        "version",
        a.version,
        "invoiceId",
        a.invoiceId,
        "movementId",
        a.movementId,
        "amount",
        Money.text(a.amount),
        "reversesId",
        a.reversesId,
        "origin",
        a.origin,
        "author",
        a.author,
        "evidence",
        a.evidence,
        "active",
        a.active);
  }

  /** Calculates outstanding invoice value, including reversals of earlier payments. */
  public BigDecimal remaining(Invoice invoice) {
    var paid =
        store.all(Allocation.class).stream()
            .filter(a -> a.active && a.invoiceId.equals(invoice.id))
            .map(a -> a.reversesId == null ? a.amount : a.amount.negate())
            .reduce(Money.ZERO, BigDecimal::add);
    return Money.orZero(invoice.total).subtract(paid);
  }

  /** Calculates the still-unallocated part of a bank movement. */
  public BigDecimal remaining(BankMovement movement) {
    return Money.orZero(movement.amount)
        .subtract(
            store.all(Allocation.class).stream()
                .filter(a -> a.active && a.movementId.equals(movement.id))
                .map(a -> a.amount)
                .reduce(Money.ZERO, BigDecimal::add));
  }

  /** Determines ledger eligibility without conflating it with reconciliation completeness. */
  public static boolean eligible(Invoice i) {
    return !i.deleted
        && !i.excluded
        && i.reviewStatus != ReviewStatus.OPEN
        && i.total != null
        && i.issuedOn != null
        && "EUR".equals(i.currency);
  }

  /** Determines whether a bank movement is validated and usable. */
  public static boolean eligible(BankMovement m) {
    return !m.deleted
        && !m.excluded
        && m.reviewStatus != ReviewStatus.OPEN
        && m.amount != null
        && m.amount.signum() > 0
        && m.direction != null
        && m.bookedOn != null
        && "EUR".equals(m.currency);
  }

  public static BigDecimal invoiceEffect(Invoice i) {
    return eligible(i) ? (i.type == InvoiceType.CREDIT_NOTE ? i.total.negate() : i.total) : null;
  }

  public static BigDecimal movementEffect(BankMovement m) {
    return eligible(m) ? (m.direction == Direction.DEBIT ? m.amount.negate() : m.amount) : null;
  }

  /**
   * Checks available cents, source direction, time window and reversal budget before assigning
   * money.
   */
  private Allocation createAllocation(
      Invoice i, BankMovement m, BigDecimal amount, UUID reverses, String origin, String evidence) {
    if (!eligible(i) || !eligible(m))
      throw DomainException.invalid("Só é possível reconciliar dados validados");
    if (!MatchingRules.eligible(i.issuedOn, m.bookedOn, window()))
      throw DomainException.invalid("Correspondência fora do período configurado");
    if (!compatible(i, m, reverses != null))
      throw DomainException.invalid("Direção ou classificação incompatível com a fatura");
    if (remaining(m).compareTo(amount) < 0)
      throw DomainException.invalid("O movimento já tem esse valor atribuído");
    if (reverses == null && remaining(i).compareTo(amount) < 0)
      throw DomainException.invalid("O valor excede o saldo da fatura");
    if (reverses != null) {
      var original = store.get(Allocation.class, reverses);
      var used =
          store.all(Allocation.class).stream()
              .filter(a -> a.active && reverses.equals(a.reversesId))
              .map(a -> a.amount)
              .reduce(Money.ZERO, BigDecimal::add);
      if (!original.active
          || original.reversesId != null
          || !original.invoiceId.equals(i.id)
          || original.amount.subtract(used).compareTo(amount) < 0)
        throw DomainException.invalid("Estorno incompatível com o pagamento original");
    }
    var a = new Allocation();
    a.invoiceId = i.id;
    a.movementId = m.id;
    a.amount = amount;
    a.reversesId = reverses;
    a.origin = origin;
    a.author = origin.equals("MANUAL") ? audit.actor() : origin;
    a.evidence = evidence;
    store.add(a);
    store.flush();
    boolean invoiceComplete = remaining(i).signum() == 0,
        movementComplete = remaining(m).signum() == 0;
    store.all(Allocation.class).stream()
        .filter(
            old ->
                !old.active
                    && ((old.invoiceId.equals(i.id) && old.movementId.equals(m.id))
                        || (invoiceComplete && old.invoiceId.equals(i.id))
                        || (movementComplete && old.movementId.equals(m.id))))
        .forEach(old -> review.resolveFor("allocation", old.id));
    if (origin.equals("RULES"))
      audit.record("AUTO_ALLOCATE", "allocation", a.id, null, allocationView(a));
    jobs.enqueue("REVIEW_ALLOCATION", a.id, a.version, "{}");
    return a;
  }

  /**
   * Auto-allocates a referenced batch only with one supplier, an exact sum and no competing
   * movement.
   */
  private void automaticGroups() {
    var invoices = store.all(Invoice.class);
    var movements = store.all(BankMovement.class);
    for (var movement : movements)
      if (eligible(movement) && remaining(movement).signum() > 0) {
        var referenced =
            invoices.stream()
                .filter(BookkeepingService::eligible)
                .filter(
                    i ->
                        compatible(i, movement, false)
                            && MatchingRules.eligible(i.issuedOn, movement.bookedOn, window())
                            && MatchingRules.reference(i.number, movement.description)
                            && MatchingRules.supplier(i.supplier, movement.description)
                            && remaining(i).signum() > 0)
                .filter(
                    i ->
                        !settings
                            .get("rejected:" + i.id + ":" + movement.id, "false")
                            .equals("true"))
                .toList();
        if (referenced.size() < 2 || referenced.size() > 20) continue;
        if (referenced.stream().map(i -> MatchingRules.normalize(i.supplier)).distinct().count()
                != 1
            || referenced.stream().map(i -> MatchingRules.normalize(i.number)).distinct().count()
                != referenced.size()) continue;
        if (referenced.stream()
                .map(this::remaining)
                .reduce(Money.ZERO, BigDecimal::add)
                .compareTo(remaining(movement))
            != 0) continue;
        boolean competing =
            referenced.stream()
                .anyMatch(
                    i ->
                        movements.stream()
                            .anyMatch(
                                m ->
                                    !m.id.equals(movement.id)
                                        && eligible(m)
                                        && compatible(i, m, false)
                                        && MatchingRules.eligible(i.issuedOn, m.bookedOn, window())
                                        && MatchingRules.reference(i.number, m.description)
                                        && remaining(m).signum() > 0));
        if (!competing)
          for (var invoice : referenced)
            createAllocation(
                invoice,
                movement,
                remaining(invoice),
                null,
                "RULES",
                "Pagamento agrupado: referências, fornecedor e soma exata");
      }
  }

  /** Withdraws machine matches if later facts introduce equally strong competing evidence. */
  private void reassessAutomaticConfidence() {
    var invoices = store.all(Invoice.class);
    var movements = store.all(BankMovement.class);
    var allocations = store.all(Allocation.class);
    var invoiceBudgets = new HashMap<UUID, BigDecimal>();
    var movementBudgets = new HashMap<UUID, BigDecimal>();
    invoices.forEach(i -> invoiceBudgets.put(i.id, remaining(i)));
    movements.forEach(m -> movementBudgets.put(m.id, remaining(m)));
    for (var a : allocations)
      if (a.active && a.origin.equals("RULES") && a.reversesId == null) {
        invoiceBudgets.merge(a.invoiceId, a.amount, BigDecimal::add);
        movementBudgets.merge(a.movementId, a.amount, BigDecimal::add);
      }
    for (var allocation : allocations)
      if (allocation.active && allocation.origin.equals("RULES") && allocation.reversesId == null) {
        if (allocations.stream()
            .anyMatch(
                a -> a.active && allocation.id.equals(a.reversesId) && a.origin.equals("MANUAL")))
          continue;
        var invoice = store.get(Invoice.class, allocation.invoiceId);
        var movement = store.get(BankMovement.class, allocation.movementId);
        int current = referenceTier(invoice, movement);
        boolean competingInvoice =
            invoices.stream()
                .anyMatch(
                    other ->
                        !other.id.equals(invoice.id)
                            && candidateTier(
                                    other,
                                    movement,
                                    invoiceBudgets.get(other.id),
                                    movementBudgets.get(movement.id))
                                >= current
                            && candidateTier(
                                    other,
                                    movement,
                                    invoiceBudgets.get(other.id),
                                    movementBudgets.get(movement.id))
                                > 0);
        boolean competingMovement =
            movements.stream()
                .anyMatch(
                    other ->
                        !other.id.equals(movement.id)
                            && candidateTier(
                                    invoice,
                                    other,
                                    invoiceBudgets.get(invoice.id),
                                    movementBudgets.get(other.id))
                                >= current
                            && candidateTier(
                                    invoice,
                                    other,
                                    invoiceBudgets.get(invoice.id),
                                    movementBudgets.get(other.id))
                                > 0);
        if (competingInvoice || competingMovement) {
          allocation.active = false;
          review.open(
              invoice.documentId,
              "allocation",
              allocation.id,
              "AMBIGUOUS_AUTO_MATCH",
              "Novos dados introduziram outra correspondência plausível. Confirma a associação.");
          audit.record(
              "WITHDRAW_AMBIGUOUS_MATCH",
              "allocation",
              allocation.id,
              null,
              allocationView(allocation));
        }
      }
  }

  /** Ranks reference evidence above supplier/amount evidence without choosing ambiguous matches. */
  private int referenceTier(Invoice invoice, BankMovement movement) {
    boolean ref = MatchingRules.reference(invoice.number, movement.description),
        supplier = MatchingRules.supplier(invoice.supplier, movement.description);
    return ref ? supplier ? 3 : 2 : supplier ? 1 : 0;
  }

  /** Tests a competing candidate against budgets that exclude protected manual assignments. */
  private int candidateTier(
      Invoice invoice, BankMovement movement, BigDecimal invoiceBudget, BigDecimal movementBudget) {
    if (!eligible(invoice)
        || !eligible(movement)
        || invoiceBudget == null
        || movementBudget == null
        || invoiceBudget.signum() <= 0
        || movementBudget.signum() <= 0
        || !compatible(invoice, movement, false)
        || !MatchingRules.eligible(invoice.issuedOn, movement.bookedOn, window())
        || settings.get("rejected:" + invoice.id + ":" + movement.id, "false").equals("true"))
      return 0;
    int tier = referenceTier(invoice, movement);
    return tier >= 2
        ? tier
        : tier == 1 && movementBudget.compareTo(invoiceBudget) == 0 ? 1 : 0;
  }

  /** Separates supplier payments/refunds from money transferred to or from the manager. */
  private boolean compatible(Invoice i, BankMovement m, boolean reversal) {
    boolean credit = (i.type == InvoiceType.CREDIT_NOTE) ^ reversal;
    return i.currency.equals(m.currency)
        && (credit
            ? m.direction == Direction.CREDIT
                && Set.of(MovementKind.UNCLASSIFIED, MovementKind.REFUND).contains(m.kind)
            : m.direction == Direction.DEBIT
                && Set.of(MovementKind.UNCLASSIFIED, MovementKind.INVOICE_PAYMENT)
                    .contains(m.kind));
  }

  /** Resolves the live cross-year window while same-year matching remains unrestricted. */
  private int window() {
    return Integer.parseInt(
        settings.get("crossYearMonths", Integer.toString(properties.crossYearMonths())));
  }

  /** Resolves canonical names and aliases before creating a new invoice tag. */
  private Category category(String name) {
    return store.all(Category.class).stream()
        .filter(
            c ->
                !c.deleted
                    && (MatchingRules.normalize(c.name).equals(MatchingRules.normalize(name))
                        || containsAlias(c.aliases, name)))
        .findFirst()
        .orElseGet(
            () -> {
              var c = new Category();
              c.name = name;
              return store.add(c);
            });
  }

  /** Compares aliases with the same accent/case normalization as canonical tags. */
  private boolean containsAlias(String aliases, String name) {
    for (var a : Json.read(aliases))
      if (MatchingRules.normalize(a.asText()).equals(MatchingRules.normalize(name))) return true;
    return false;
  }

  /** Retains six decimal places for quantities and unit prices without silently rounding. */
  private BigDecimal precise(Object value) {
    return value == null || value.toString().isBlank()
        ? null
        : new BigDecimal(value.toString()).setScale(6, RoundingMode.UNNECESSARY);
  }
}
